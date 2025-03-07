package scala.scalanative
package interflow

import java.{lang => jl}
import scalanative.linker._
import scalanative.util.{unreachable, And}
import nir.Bin.{And => Iand, _}
import nir.Comp._
import nir.Conv._

trait Combine { self: Interflow =>

  def combine(bin: nir.Bin, ty: nir.Type, l: nir.Val, r: nir.Val)(implicit
      state: State,
      srcPosition: nir.Position,
      scopeId: nir.ScopeId
  ): nir.Val = {
    import state.{materialize, delay, emit}

    def fallback = {
      if (nir.Op.Bin(bin, ty, l, r).isPure) {
        delay(nir.Op.Bin(bin, ty, l, r))
      } else {
        emit(nir.Op.Bin(bin, ty, materialize(l), materialize(r)))
      }
    }

    bin match {
      case Iadd =>
        (l, r) match {
          // x + 0 ==> x
          case (x, v) if v.isZero =>
            x

          // (x + b) + a ==> x + (a + b)
          case (BinRef(Iadd, x, nir.Val.Int(b)), nir.Val.Int(a)) =>
            combine(Iadd, ty, x, nir.Val.Int(a + b))
          case (BinRef(Iadd, x, nir.Val.Long(b)), nir.Val.Long(a)) =>
            combine(Iadd, ty, x, nir.Val.Long(a + b))

          // (x - b) + a ==> x + (a - b)
          case (BinRef(Isub, x, nir.Val.Int(b)), nir.Val.Int(a)) =>
            combine(Iadd, ty, x, nir.Val.Int(a - b))
          case (BinRef(Isub, x, nir.Val.Long(b)), nir.Val.Long(a)) =>
            combine(Iadd, ty, x, nir.Val.Long(a - b))

          // x + (0 - y) ==> x - y
          case (x, BinRef(Isub, v, y)) if v.isZero =>
            combine(Isub, ty, x, y)

          // (0 - x) + y ==> y - x
          case (BinRef(Isub, v, x), y) if v.isZero =>
            combine(Isub, ty, y, x)

          // (x - y) + y ==> x
          case (BinRef(Isub, x, y1), y2) if y1 == y2 =>
            x

          case _ =>
            fallback
        }

      case Isub =>
        (l, r) match {
          // x - 0 ==> x
          case (x, v) if v.isZero =>
            x

          // x - x ==> 0
          case (lhs, rhs) if lhs == rhs =>
            zero(ty)

          // (x - b) - a ==> x - (a + b)
          case (BinRef(Isub, x, nir.Val.Int(b)), nir.Val.Int(a)) =>
            combine(Isub, ty, x, nir.Val.Int(a + b))
          case (BinRef(Isub, x, nir.Val.Long(b)), nir.Val.Long(a)) =>
            combine(Isub, ty, x, nir.Val.Long(a + b))

          // (x + b) - a ==> x - (a - b)
          case (BinRef(Iadd, x, nir.Val.Int(b)), nir.Val.Int(a)) =>
            combine(Isub, ty, x, nir.Val.Int(a - b))
          case (BinRef(Iadd, x, nir.Val.Long(b)), nir.Val.Long(a)) =>
            combine(Isub, ty, x, nir.Val.Long(a - b))

          // x - (0 - y) ==> x + y
          case (x, BinRef(Isub, v, y)) if v.isZero =>
            combine(Iadd, ty, x, y)

          // (x + y) - y ==> x
          case (BinRef(Iadd, x, y1), y2) if y1 == y2 =>
            x

          // (x + y) - x ==> y
          case (BinRef(Iadd, x1, y), x2) if x1 == x2 =>
            y

          case _ =>
            fallback
        }

      case Imul =>
        (l, r) match {
          // x * 0 ==> 0
          case (lhs, v) if v.isZero =>
            zero(ty)

          // x * 1 ==> x
          case (lhs, v) if v.isOne =>
            lhs

          // x * -1 ==> -x
          case (lhs, v) if v.isMinusOne =>
            combine(Isub, ty, zero(ty), lhs)

          // x * 2^n ==> x << n
          case (lhs, nir.Val.Int(v)) if isPowerOfTwoOrMinValue(v) =>
            combine(
              Shl,
              ty,
              lhs,
              nir.Val.Int(jl.Integer.numberOfTrailingZeros(v))
            )
          case (lhs, nir.Val.Long(v)) if isPowerOfTwoOrMinValue(v) =>
            combine(
              Shl,
              ty,
              lhs,
              nir.Val.Long(jl.Long.numberOfTrailingZeros(v))
            )

          // (x * b) * a ==> x * (a * b)
          case (BinRef(Imul, x, nir.Val.Int(b)), nir.Val.Int(a)) =>
            combine(Imul, ty, x, nir.Val.Int(b * a))
          case (BinRef(Imul, x, nir.Val.Long(b)), nir.Val.Long(a)) =>
            combine(Imul, ty, x, nir.Val.Long(b * a))

          case _ =>
            fallback
        }

      case Sdiv =>
        (l, r) match {
          // x signed_/ 1 ==> x
          case (lhs, v) if v.isOne =>
            lhs

          // x signed_/ -1 ==> -x
          case (lhs, v) if v.isMinusOne =>
            combine(Isub, ty, zero(ty), lhs)

          case _ =>
            fallback
        }

      case Udiv =>
        (l, r) match {
          // x unsigned_/ 1 ==> x
          case (lhs, v) if v.isOne =>
            lhs

          // x unsigned_/ 2^n ==> x >> n
          case (lhs, nir.Val.Int(v)) if isPowerOfTwoOrMinValue(v) =>
            combine(
              Lshr,
              ty,
              lhs,
              nir.Val.Int(jl.Integer.numberOfTrailingZeros(v))
            )
          case (lhs, nir.Val.Long(v)) if isPowerOfTwoOrMinValue(v) =>
            combine(
              Lshr,
              ty,
              lhs,
              nir.Val.Long(jl.Long.numberOfTrailingZeros(v))
            )

          case _ =>
            fallback
        }

      case Srem =>
        (l, r) match {
          // x signed_% 1 ==> 0
          case (lhs, v) if v.isOne =>
            zero(ty)

          // x signed_% -1 ==> 0
          case (lhs, v) if v.isMinusOne =>
            zero(ty)

          case _ =>
            fallback
        }

      case Urem =>
        (l, r) match {
          // x unsigned_% 1 ==> 0
          case (lhs, v) if v.isOne =>
            zero(ty)

          case _ =>
            fallback
        }

      case Shl =>
        (l, r) match {
          // x << v ==> x if v & bitsize(x) - 1 == 0
          case (lhs, nir.Val.Int(v)) if (v & 31) == 0 =>
            lhs
          case (lhs, nir.Val.Long(v)) if (v & 63) == 0 =>
            lhs

          // 0 << x ==> 0
          case (v, _) if v.isZero =>
            zero(ty)

          // (x << a) << b ==> x << (a + b)
          case (BinRef(Shl, x, nir.Val.Int(a)), nir.Val.Int(b)) =>
            val dist = (a & 31) + (b & 31)
            if (dist >= 32) {
              nir.Val.Int(0)
            } else {
              combine(Shl, ty, x, nir.Val.Int(dist))
            }
          case (BinRef(Shl, x, nir.Val.Long(a)), nir.Val.Long(b)) =>
            val dist = (a & 63) + (b & 63)
            if (dist >= 64) {
              nir.Val.Long(0)
            } else {
              combine(Shl, ty, x, nir.Val.Long(dist))
            }

          case _ =>
            fallback
        }

      case Lshr =>
        (l, r) match {
          // x >>> v ==> x if v & bitsize(x) - 1 == 0
          case (lhs, nir.Val.Int(v)) if (v & 31) == 0 =>
            lhs
          case (lhs, nir.Val.Long(v)) if (v & 63) == 0 =>
            lhs

          // 0 >>> x ==> 0
          case (lhs, _) if lhs.isZero =>
            zero(ty)

          // (x >>> a) >>> b ==> x >>> (a + b)
          case (BinRef(Lshr, x, nir.Val.Int(a)), nir.Val.Int(b)) =>
            val dist = (a & 31) + (b & 31)
            if (dist >= 32) {
              nir.Val.Int(0)
            } else {
              combine(Lshr, ty, x, nir.Val.Int(dist))
            }
          case (BinRef(Lshr, x, nir.Val.Long(a)), nir.Val.Long(b)) =>
            val dist = (a & 63) + (b & 63)
            if (dist >= 64) {
              nir.Val.Int(0)
            } else {
              combine(Lshr, ty, x, nir.Val.Long(dist))
            }

          case _ =>
            fallback
        }

      case Ashr =>
        (l, r) match {
          // x >> v ==> x if v & bitsize(x) - 1 == 0
          case (lhs, nir.Val.Int(a)) if (a & 31) == 0 =>
            lhs
          case (lhs, nir.Val.Long(v)) if (v & 63) == 0 =>
            lhs

          // 0 >> x ==> 0
          case (lhs, _) if lhs.isZero =>
            zero(ty)

          // -1 >> x ==> -1
          case (v, rhs) if v.isMinusOne =>
            minusOne(ty)

          // (x >> a) >> b ==> x >> (a + b)
          case (BinRef(Ashr, x, nir.Val.Int(a)), nir.Val.Int(b)) =>
            val dist = Math.min((a & 31) + (b & 31), 31)
            combine(Ashr, ty, x, nir.Val.Int(dist))
          case (BinRef(Ashr, x, nir.Val.Long(a)), nir.Val.Long(b)) =>
            val dist = Math.min((a & 63) + (b & 63), 63)
            combine(Ashr, ty, x, nir.Val.Long(dist))

          case _ =>
            fallback
        }

      case Iand =>
        (l, r) match {
          // x & x ==> x
          case (lhs, rhs) if lhs == rhs =>
            lhs

          // x & 0 ==> 0
          case (lhs, v) if v.isZero =>
            zero(ty)

          // x & -1 ==> x
          case (lhs, v) if v.isMinusOne =>
            lhs

          // (x & a) & b ==> x & (a & b)
          case (BinRef(Iand, x, nir.Val.Int(a)), nir.Val.Int(b)) =>
            combine(Iand, ty, x, nir.Val.Int(a & b))
          case (BinRef(Iand, x, nir.Val.Long(a)), nir.Val.Long(b)) =>
            combine(Iand, ty, x, nir.Val.Long(a & b))

          // (x >= y) & (x <= y) ==> (x == y)
          case (CompRef(Sge, ty1, x1, y1), CompRef(Sle, _, x2, y2))
              if x1 == x2 && y1 == y2 =>
            combine(Ieq, ty1, x1, y1)
          case (CompRef(Uge, ty1, x1, y1), CompRef(Ule, _, x2, y2))
              if x1 == x2 && y1 == y2 =>
            combine(Ieq, ty1, x1, y1)

          case _ =>
            fallback
        }

      case Or =>
        (l, r) match {
          // x | x ==> x
          case (lhs, rhs) if lhs == rhs =>
            lhs

          // x | 0 ==> x
          case (lhs, v) if v.isZero =>
            lhs

          // x | -1 ==> -1
          case (lhs, v) if v.isMinusOne =>
            minusOne(ty)

          // (x or a) or b ==> x or (a or b)
          case (BinRef(Or, x, nir.Val.Int(a)), nir.Val.Int(b)) =>
            combine(Or, ty, x, nir.Val.Int(a | b))
          case (BinRef(Or, x, nir.Val.Long(a)), nir.Val.Long(b)) =>
            combine(Or, ty, x, nir.Val.Long(a | b))

          // (x > y) | (x == y) ==> (x >= y)
          case (CompRef(Sgt, ty1, x1, y1), CompRef(Ieq, _, x2, y2))
              if x1 == x2 && y1 == y2 =>
            combine(Sge, ty1, x1, y1)
          case (CompRef(Ugt, ty1, x1, y1), CompRef(Ieq, _, x2, y2))
              if x1 == x2 && y1 == y2 =>
            combine(Uge, ty1, x1, y1)

          // (x > y) | (y == x) ==> (x >= y)
          case (CompRef(Sgt, ty1, x1, y1), CompRef(Ieq, _, y2, x2))
              if x1 == x2 && y1 == y2 =>
            combine(Sge, ty1, x1, y1)
          case (CompRef(Ugt, ty1, x1, y1), CompRef(Ieq, _, y2, x2))
              if x1 == x2 && y1 == y2 =>
            combine(Uge, ty1, x1, y1)

          // (x < y) | (x == y) ==> (x <= y)
          case (CompRef(Slt, ty1, x1, y1), CompRef(Ieq, _, x2, y2))
              if x1 == x2 && y1 == y2 =>
            combine(Sle, ty1, x1, y1)
          case (CompRef(Ult, ty1, x1, y1), CompRef(Ieq, _, x2, y2))
              if x1 == x2 && y1 == y2 =>
            combine(Ule, ty1, x1, y1)

          // (x < y) | (y == x) ==> (x <= y)
          case (CompRef(Slt, ty1, x1, y1), CompRef(Ieq, _, y2, x2))
              if x1 == x2 && y1 == y2 =>
            combine(Sle, ty1, x1, y1)
          case (CompRef(Ult, ty1, x1, y1), CompRef(Ieq, _, y2, x2))
              if x1 == x2 && y1 == y2 =>
            combine(Ule, ty1, x1, y1)

          case _ =>
            fallback
        }

      case Xor =>
        (l, r) match {
          // x ^ x ==> 0
          case (lhs, rhs) if lhs == rhs =>
            zero(ty)

          // x ^ 0 ==> x
          case (lhs, v) if v.isZero =>
            lhs

          // (x ^ a) ^ b ==> x ^ (a ^ b)
          case (BinRef(Xor, x, nir.Val.Int(a)), nir.Val.Int(b)) =>
            combine(Xor, ty, x, nir.Val.Int(a ^ b))
          case (BinRef(Xor, x, nir.Val.Long(a)), nir.Val.Long(b)) =>
            combine(Xor, ty, x, nir.Val.Long(a ^ b))

          case _ =>
            fallback
        }

      case _ =>
        fallback
    }
  }

  def combine(comp: nir.Comp, ty: nir.Type, l: nir.Val, r: nir.Val)(implicit
      state: State,
      srcPosition: nir.Position,
      scopeId: nir.ScopeId
  ): nir.Val = {
    import state.{materialize, delay, emit}

    (comp, l, r) match {
      // Two virtual allocations will compare equal if
      // and only if they have the same virtual address.
      case (Ieq, nir.Val.Virtual(l), nir.Val.Virtual(r))
          if state.isVirtual(l) && state.isVirtual(r) =>
        nir.Val.Bool(l == r)
      case (Ine, nir.Val.Virtual(l), nir.Val.Virtual(r))
          if state.isVirtual(l) && state.isVirtual(r) =>
        nir.Val.Bool(l != r)

      // Not-yet-materialized virtual allocation will never be
      // the same as already existing allocation (be it null
      // or any other value).
      //
      // This is not true however for boxes and strings as
      // they may be interned and the virtual allocation may
      // alias pre-existing materialized allocation.
      case (Ieq, VirtualRef(ClassKind | ArrayKind, _, _), r) =>
        nir.Val.False
      case (Ieq, l, VirtualRef(ClassKind | ArrayKind, _, _)) =>
        nir.Val.False
      case (Ine, VirtualRef(ClassKind | ArrayKind, _, _), r) =>
        nir.Val.True
      case (Ine, l, VirtualRef(ClassKind | ArrayKind, _, _)) =>
        nir.Val.True

      // Comparing non-nullable value with null will always
      // yield the same result.
      case (Ieq, v @ nir.Of(ty: nir.Type.RefKind), nir.Val.Null)
          if !ty.isNullable =>
        nir.Val.False
      case (Ieq, nir.Val.Null, v @ nir.Of(ty: nir.Type.RefKind))
          if !ty.isNullable =>
        nir.Val.False
      case (Ine, v @ nir.Of(ty: nir.Type.RefKind), nir.Val.Null)
          if !ty.isNullable =>
        nir.Val.True
      case (Ine, nir.Val.Null, v @ nir.Of(ty: nir.Type.RefKind))
          if !ty.isNullable =>
        nir.Val.True

      // Ptr boxes are null if underlying pointer is null.
      case (Ieq, DelayedRef(nir.Op.Box(ty, x)), nir.Val.Null)
          if nir.Type.isPtrBox(ty) =>
        combine(Ieq, nir.Type.Ptr, x, nir.Val.Null)
      case (Ieq, nir.Val.Null, DelayedRef(nir.Op.Box(ty, x)))
          if nir.Type.isPtrBox(ty) =>
        combine(Ieq, nir.Type.Ptr, x, nir.Val.Null)
      case (Ine, DelayedRef(nir.Op.Box(ty, x)), nir.Val.Null)
          if nir.Type.isPtrBox(ty) =>
        combine(Ine, nir.Type.Ptr, x, nir.Val.Null)
      case (Ine, nir.Val.Null, DelayedRef(nir.Op.Box(ty, x)))
          if nir.Type.isPtrBox(ty) =>
        combine(Ine, nir.Type.Ptr, x, nir.Val.Null)

      // Comparing two non-null module references will
      // yield true only if it's the same module.
      case (
            Ieq,
            l @ nir.Of(And(lty: nir.Type.RefKind, ClassRef(lcls))),
            r @ nir.Of(And(rty: nir.Type.RefKind, ClassRef(rcls)))
          )
          if !lty.isNullable && lty.isExact && lcls.isModule
            && !rty.isNullable && rty.isExact && rcls.isModule =>
        nir.Val.Bool(lcls.name == rcls.name)
      case (
            Ine,
            l @ nir.Of(And(lty: nir.Type.RefKind, ClassRef(lcls))),
            r @ nir.Of(And(rty: nir.Type.RefKind, ClassRef(rcls)))
          )
          if !lty.isNullable && lty.isExact && lcls.isModule
            && !rty.isNullable && rty.isExact && rcls.isModule =>
        nir.Val.Bool(lcls.name != rcls.name)

      // Comparisons against the same SSA value or
      // against true/false are statically known.
      case (Ieq, lhs, rhs) if (lhs == rhs) =>
        nir.Val.True
      case (Ieq, lhs, nir.Val.True) =>
        lhs
      case (Ine, lhs, rhs) if (lhs == rhs) =>
        nir.Val.False
      case (Ine, lhs, nir.Val.False) =>
        lhs

      // Integer comparisons against corresponding
      // min/max value are often statically known.
      case (Ugt, lhs, v) if v.isUnsignedMaxValue =>
        nir.Val.False
      case (Uge, lhs, v) if v.isUnsignedMinValue =>
        nir.Val.True
      case (Ult, lhs, v) if v.isUnsignedMinValue =>
        nir.Val.False
      case (Ule, lhs, v) if v.isUnsignedMaxValue =>
        nir.Val.True
      case (Sgt, lhs, v) if v.isSignedMaxValue(platform.is32Bit) =>
        nir.Val.False
      case (Sge, lhs, v) if v.isSignedMinValue(platform.is32Bit) =>
        nir.Val.True
      case (Slt, lhs, v) if v.isSignedMinValue(platform.is32Bit) =>
        nir.Val.False
      case (Sle, lhs, v) if v.isSignedMaxValue(platform.is32Bit) =>
        nir.Val.True

      // ((x xor y) == 0) ==> (x == y)
      case (Ieq, BinRef(Xor, x, y), v) if v.isZero =>
        combine(Ieq, ty, x, y)

      // ((x xor y) != 0) ==> (x != y)
      case (Ine, BinRef(Xor, x, y), v) if v.isZero =>
        combine(Ine, ty, x, y)

      // ((x + a) == b) ==> (x == (b - a))
      case (Ieq, BinRef(Iadd, x, nir.Val.Char(a)), nir.Val.Char(b)) =>
        combine(Ieq, ty, x, nir.Val.Char((b - a).toChar))
      case (Ieq, BinRef(Iadd, x, nir.Val.Byte(a)), nir.Val.Byte(b)) =>
        combine(Ieq, ty, x, nir.Val.Byte((b - a).toByte))
      case (Ieq, BinRef(Iadd, x, nir.Val.Short(a)), nir.Val.Short(b)) =>
        combine(Ieq, ty, x, nir.Val.Short((b - a).toShort))
      case (Ieq, BinRef(Iadd, x, nir.Val.Int(a)), nir.Val.Int(b)) =>
        combine(Ieq, ty, x, nir.Val.Int(b - a))
      case (Ieq, BinRef(Iadd, x, nir.Val.Long(a)), nir.Val.Long(b)) =>
        combine(Ieq, ty, x, nir.Val.Long(b - a))

      // ((x - a) == b) ==> (x == (a + b))
      case (Ieq, BinRef(Isub, x, nir.Val.Char(a)), nir.Val.Char(b)) =>
        combine(Ieq, ty, x, nir.Val.Char((a + b).toChar))
      case (Ieq, BinRef(Isub, x, nir.Val.Byte(a)), nir.Val.Byte(b)) =>
        combine(Ieq, ty, x, nir.Val.Byte((a + b).toByte))
      case (Ieq, BinRef(Isub, x, nir.Val.Short(a)), nir.Val.Short(b)) =>
        combine(Ieq, ty, x, nir.Val.Short((a + b).toShort))
      case (Ieq, BinRef(Isub, x, nir.Val.Int(a)), nir.Val.Int(b)) =>
        combine(Ieq, ty, x, nir.Val.Int(a + b))
      case (Ieq, BinRef(Isub, x, nir.Val.Long(a)), nir.Val.Long(b)) =>
        combine(Ieq, ty, x, nir.Val.Long(a + b))

      // ((a - x) == b) ==> (x == (a - b))
      case (Ieq, BinRef(Isub, nir.Val.Char(a), x), nir.Val.Char(b)) =>
        combine(Ieq, ty, x, nir.Val.Char((a - b).toChar))
      case (Ieq, BinRef(Isub, nir.Val.Byte(a), x), nir.Val.Byte(b)) =>
        combine(Ieq, ty, x, nir.Val.Byte((a - b).toByte))
      case (Ieq, BinRef(Isub, nir.Val.Short(a), x), nir.Val.Short(b)) =>
        combine(Ieq, ty, x, nir.Val.Short((a - b).toShort))
      case (Ieq, BinRef(Isub, nir.Val.Int(a), x), nir.Val.Int(b)) =>
        combine(Ieq, ty, x, nir.Val.Int(a - b))
      case (Ieq, BinRef(Isub, nir.Val.Long(a), x), nir.Val.Long(b)) =>
        combine(Ieq, ty, x, nir.Val.Long(a - b))

      // ((x xor a) == b) ==> (x == (a xor b))
      case (Ieq, BinRef(Xor, x, nir.Val.Char(a)), nir.Val.Char(b)) =>
        combine(Ieq, ty, x, nir.Val.Char((a ^ b).toChar))
      case (Ieq, BinRef(Xor, x, nir.Val.Byte(a)), nir.Val.Byte(b)) =>
        combine(Ieq, ty, x, nir.Val.Byte((a ^ b).toByte))
      case (Ieq, BinRef(Xor, x, nir.Val.Short(a)), nir.Val.Short(b)) =>
        combine(Ieq, ty, x, nir.Val.Short((a ^ b).toShort))
      case (Ieq, BinRef(Xor, x, nir.Val.Int(a)), nir.Val.Int(b)) =>
        combine(Ieq, ty, x, nir.Val.Int(a ^ b))
      case (Ieq, BinRef(Xor, x, nir.Val.Long(a)), nir.Val.Long(b)) =>
        combine(Ieq, ty, x, nir.Val.Long(a ^ b))

      // ((x xor true) == y) ==> (x != y)
      case (Ieq, BinRef(Xor, x, nir.Val.True), y) =>
        combine(Ine, ty, x, y)

      // (x == (y xor true)) ==> (x != y)
      case (Ieq, x, BinRef(Xor, y, nir.Val.True)) =>
        combine(Ine, ty, x, y)

      case (_, l, r) =>
        delay(nir.Op.Comp(comp, ty, r, l))
    }
  }

  def combine(conv: nir.Conv, ty: nir.Type, value: nir.Val)(implicit
      state: State,
      srcPosition: nir.Position,
      scopeId: nir.ScopeId
  ): nir.Val = {
    import state.{materialize, delay, emit}

    (conv, ty, value) match {
      // trunc[iN] (trunc[iM] x) ==> trunc[iN] x if N < M
      case (
            Trunc,
            nir.Type.FixedSizeI(n, _),
            ConvRef(Trunc, nir.Type.FixedSizeI(m, _), x)
          ) if n < m =>
        combine(Trunc, ty, x)

      // sext[iN] (sext[iM] x) ==> sext[iN] x if N > M
      case (
            Sext,
            nir.Type.FixedSizeI(n, _),
            ConvRef(Sext, nir.Type.FixedSizeI(m, _), x)
          ) if n > m =>
        combine(Sext, ty, x)

      // zext[iN] (zext[iM] x) ==> zext[iN] x if N > M
      case (
            Zext,
            nir.Type.FixedSizeI(n, _),
            ConvRef(Zext, nir.Type.FixedSizeI(m, _), x)
          ) if n > m =>
        combine(Zext, ty, x)

      // ptrtoint[long] (inttoptr[long] x) ==> x
      case (Ptrtoint, nir.Type.Long, ConvRef(Inttoptr, nir.Type.Long, x)) =>
        x

      // inttoptr[long] (ptrtoint[long] x) ==> x
      case (Inttoptr, nir.Type.Long, ConvRef(Ptrtoint, nir.Type.Long, x)) =>
        x

      // bitcast[ty1] (bitcast[ty2] x) ==> bitcast[ty1] x
      case (Bitcast, _, ConvRef(Bitcast, _, x)) =>
        combine(Bitcast, ty, x)

      // bitcast[ty] x ==> x if typeof(x) == ty
      case (Bitcast, ty, x) if x.ty == ty =>
        x

      case _ =>
        delay(nir.Op.Conv(conv, ty, value))
    }
  }

  private def zero(ty: nir.Type): nir.Val =
    nir.Val.Zero(ty).canonicalize

  private def minusOne(ty: nir.Type): nir.Val = ty match {
    case nir.Type.Byte =>
      nir.Val.Byte(-1)
    case nir.Type.Short =>
      nir.Val.Short(-1)
    case nir.Type.Int =>
      nir.Val.Int(-1)
    case nir.Type.Long =>
      nir.Val.Long(-1)
    case nir.Type.Float =>
      nir.Val.Float(-1)
    case nir.Type.Double =>
      nir.Val.Double(-1)
    case _ =>
      unreachable
  }

  private def isPowerOfTwoOrMinValue(x: Int): Boolean =
    (x & (x - 1)) == 0

  private def isPowerOfTwoOrMinValue(x: Long): Boolean =
    (x & (x - 1)) == 0

}
