import Symbols.Symbol

import scala.collection.mutable
import scala.reflect.ClassTag

/** The type system of Stk.
  *
  * Stk has a structural type system, which means that the names of types
  * usually do not matter. Two types are equivalent if they refer to types that
  * are structurally the same.
  */
object Types:
  sealed abstract class Type:
    def isError: Boolean = this == ErrorType

    def isVoid: Boolean = this == VoidType

    def isAny: Boolean = this == AnyType

    def isBottom: Boolean = this == BottomType

    def isRecordType: Boolean =
      this.approx(isUp = true).isInstanceOf[RecordType]

    def isUnionType: Boolean =
      this.approx(isUp = true).isInstanceOf[UnionType]

    def isTypeLambda: Boolean =
      this.approx(isUp = true).isInstanceOf[TypeLambda]

    def isProcType: Boolean =
      this.approx(isUp = true).isInstanceOf[ProcType]

    def isPolyType: Boolean =
      this.approx(isUp = true).isInstanceOf[PolyType]

    def isValueType: Boolean =
      this.dealias match
        case VoidType | _: ProcType | _: TypeLambda | _: PolyType => false
        case _ => true

    def asRecordType: RecordType = this.approx(isUp = true).asInstanceOf[RecordType]

    def asUnionType: UnionType = this.approx(isUp = true).asInstanceOf[UnionType]

    def asTypeLambda: TypeLambda = this.approx(isUp = true).asInstanceOf[TypeLambda]

    def asProcType: ProcType = this.approx(isUp = true).asInstanceOf[ProcType]

    def asPolyType: PolyType = this.approx(isUp = true).asInstanceOf[PolyType]

    def is[T <: Type : ClassTag]: Boolean =
      this match
        case tp: T => true
        case _     => false

    def as[T <: Type]: T = this.asInstanceOf[T]

    /** A grounded type cannot be simplied further at the top-level
      *
      * The following types are grounded:
      *
      * - primitive types
      * - procedure types
      * - record types
      * - union types
      */
    def isGrounded: Boolean =
      this match
        case AnyType | BottomType | IntType | BoolType | ErrorType | VoidType => true
        case _: PolyType | _: ProcType | _: RecordType | _: UnionType | _: TypeBound => true
        case _: TypeLambda | _: TypeParamRef => true
        case _: TypeRef | _: AppliedType => false
        case _: DelayedType => false

    /** Erase a poly type by replacing type parameters with Any */
    def erasePolyType: Type =
      // implementation assumption: no nested poly types
      this.dealias match
        case PolyType(_, bounds, resType) =>
          // cannot subst with bounds as they might be recursive
          substTypeParams(resType, bounds.map(_ => AnyType))

        case tp => tp

    /** Transitively eliminate top-level type aliases, delayed types and applied types */
    def dealias: Type =
      // detect cycles in symbol definitions, e.g., type A = A
      val encountered = new mutable.ArrayBuffer[Symbol]
      def recur(tp: Type): Type = Debug.trace(s"$tp.dealias", enable = false):
        tp match
          case delayed: DelayedType =>
            recur(delayed.force())

          case tref @ TypeRef(sym) =>
            if encountered.contains(sym) then
              tref
            else
              encountered += sym
              recur(sym.info)
            end if

          case app @ AppliedType(tctor, targs) =>
            recur(tctor) match
              case tl: TypeLambda =>
                recur(substTypeParams(tl.body, targs))

              case _ =>
                app

          case tp => tp
      end recur
      recur(this)

    /** Approximate top-level type aliases, delayed types, applied types
      *
      *
      * The difference with `dealias` is that this method approximates type
      * bounds while `dealias` does not.
      *
      * It approximates a type to its upper bound or lower bound according to
      * the spec.
      */
    private def approx(isUp: Boolean): Type =
      // detect cycles in symbol definitions, e.g., type A = A
      val encountered = new mutable.ArrayBuffer[Symbol]
      def recur(tp: Type, isUp: Boolean): Type = Debug.trace(s"$tp.approx", enable = false):
        tp match
          case delayed: DelayedType =>
            recur(delayed.force(), isUp)

          case tref @ TypeRef(sym) =>
            if encountered.contains(sym) then
              tref
            else
              encountered += sym
              recur(sym.info, isUp)
            end if

          case TypeBound(lo, hi) =>
            if isUp then recur(hi, isUp) else recur(lo, isUp)

          case app @ AppliedType(tctor, targs) =>
            recur(tctor, isUp) match
              case tl: TypeLambda =>
                recur(substTypeParams(tl.body, targs), isUp)

              case _ =>
                app

          case tp => tp
      end recur
      recur(this, isUp)

    def show: String =
      this match
        case IntType | BoolType | VoidType | AnyType | BottomType | ErrorType =>
          this.toString

        case TypeRef(sym) =>
          sym.name

        case RecordType(fields) =>
          fields.map(_ + ": " + _.show).mkString("{", ", ", "}")

        case UnionType(branches) =>
          def concat(tps: List[Type]) = tps.map(_.show).mkString(" * ")
          branches.map(_ + " " + concat(_)).mkString("<", ", ", ">")

        case delay: DelayedType =>
          delay.underlying.show

        case AppliedType(tctor, targs) =>
          tctor.show + targs.map(_.show).mkString("[", ", ", "]")

        case TypeLambda(names, bounds, body) =>
          val tparams = names.zip(bounds).map(_ + " <: " + _.show).mkString("[", ", ", "]")
          tparams + " => " + body.show

        case PolyType(names, bounds, resType) =>
          val tparams = names.zip(bounds).map(_ + " <: " + _.show).mkString("[", ", ", "]")
          tparams + resType.show

        case TypeParamRef(name, _) =>
          name

        case TypeBound(lo, hi) =>
          lo.show + " .. " + hi.show

        case ProcType(names, paramTypes, resType) =>
          val params = names.zip(paramTypes).map(_ + " <: " + _.show).mkString("(", ", ", ")")
          params + ": " + resType.show
  end Type

  case object IntType extends Type

  case object BoolType extends Type

  case object VoidType extends Type

  case object AnyType extends Type

  case object BottomType extends Type

  case object ErrorType extends Type

  case class TypeRef(symbol: Symbol) extends Type

  /** A record type --- named tuples
    *
    * Warning: flattening of nested tuples is dangerous with subtyping
    * of records.
    */
  case class RecordType(fields: List[(String, Type)]) extends Type:
    val fieldNames: List[String] = fields.map(_._1)

    def getFieldType(field: String): Option[Type] =
      fields.collectFirst:
        case (f, tp) if f == field => tp

    def hasField(name: String): Boolean =
      fieldNames.contains(name)

    def fieldType(name: String): Type =
      getFieldType(name).get

  case class UnionType(branches: List[(String, List[Type])]) extends Type:
    val tags: List[String] = branches.map(_._1)

    def getTagType(tag: String): Option[List[Type]] =
      branches.collectFirst:
        case (t, tps) if t == tag => tps

    def hasTag(tag: String): Boolean =
      tags.contains(tag)

    def tagType(tag: String): List[Type] =
      getTagType(tag).get

    def tagIndex(tag: String): Int =
      branches.indexWhere:
        case (t, _) => t == tag

  case class PolyType
    (names: List[String], bounds: List[Type], resultType: Type)
  extends Type:
    val paramCount = bounds.size

  case class ProcType
    (names: List[String], paramTypes: List[Type], resultType: Type)
  extends Type:
    val paramCount = paramTypes.size
    val resCount = if resultType.isValueType then 1 else 0

  /** A type lambda */
  case class TypeLambda
    (names: List[String], bounds: List[Type], body: Type)
  extends Type:
    val paramCount = names.size

  /** An index reference to type parameter
    *
    * There is no support for nested type parameters. Therefore, there is no
    * need to distinguish the holders that the type parameters are attached
    * to.
    */
  case class TypeParamRef
    (name: String, index: Int)
  extends Type

  case class AppliedType
    (tctor: Type, targs: List[Type])
  extends Type

  /** Represents upper and lower bounds of type parameters */
  case class TypeBound
    (lo: Type, hi: Type)
  extends Type

  /** Delayed type for symbols to enable type inference and recursive types */
  case class DelayedType
    ()
    (infoCompleter: => Type)
  extends Type:
    private var _underlying: Type = null

    def underlying: Type = force()

    private def complete(): Unit =
      assert(_underlying == null, "Double completing: " + _underlying)
      _underlying = infoCompleter

    def isComplete: Boolean = _underlying != null

    def force(): Type =
      if !isComplete then complete()
      _underlying

    override def equals(that: Any): Boolean =
      if !isComplete then false
      else
        that match
          case tp: DelayedType =>
            tp.isComplete && tp._underlying == this._underlying

          case _ =>
            false
        end match

    override def hashCode(): Int =
      if !isComplete then throw new Exception("Hashing incomplete type")
      else _underlying.hashCode

    override def toString =
      "Delayed(" + _underlying + ")"
  end DelayedType


  /** Whether `tp1` conforms to `tp2` */
  def conforms(tp1: Type, tp2: Type): Boolean =
    checkConforms(tp1,tp2)(using new Assumptions())

  /** The assumption that a type A is a subtype of B
    *
    * In essence, the subtyping follows Amber's rule for recursive types.
    *
    *                    Γ, α <: β ⊢ S <: T
    *                   ---------------------
    *                      μα.S  <: μβ.T
    *
    * The rule is sound and sufficiently expressive in pratical usage. For
    * example, it rules out that μα.α → ⊥ is a subtype of μβ.β → ⊤.
    *
    * However, it cannot prove that μα.α → Int is a subtype of μβ.β → Int. The
    * original paper includes a rule that takes equality of recursive types
    * into consideration:
    *
    *                         A = B
    *                    ----------------
    *                       Γ ⊢ A <: B
    *
    * The equality above is defined not syntatically but semantically. Such
    * equality is only theoretically motivated, thus it is not implemented in
    * the current language.
    *
    * - Paper: Subtyping recursive types, Roberto M. Amadio, Luca Cardelli, 1993
    * - Link: https://dl.acm.org/doi/10.1145/155183.155231
    */
  private case class Assumptions(
    syms: Map[Symbol, List[Symbol]],            // non-parameter type symbols
    apps: Map[AppliedType, List[AppliedType]]   // applied types
  ):
    def this() = this(Map.empty, Map.empty)

  /** Check whether one type conforms to the other type */
  private def checkConforms(tp1: Type, tp2: Type)(using ass: Assumptions): Boolean = Debug.trace(s"${tp1.show} <: ${tp2.show}", enable = false) {
    tp1.isError
    || tp2.isError
    || tp1.isBottom
    || tp2.isAny && tp1.isValueType
    || tp1 == tp2
    || tp1.is[TypeRef] && tp2.is[TypeRef]
       && checkConformsTypeRef(tp1.as[TypeRef], tp2.as[TypeRef])
    || tp1.is[TypeRef] && checkConformsProxyType(tp1.as[TypeRef], tp2)
    || tp2.is[TypeRef] && checkConformsProxyType(tp1, tp2.as[TypeRef])
    || tp1.is[DelayedType] && checkConforms(tp1.as[DelayedType].underlying, tp2)
    || tp2.is[DelayedType] && checkConforms(tp1, tp2.as[DelayedType].underlying)
    || tp1.is[RecordType] && tp2.is[RecordType]
       && checkConformsRecordType(tp1.as[RecordType], tp2.as[RecordType])
    || tp1.is[AppliedType] && tp2.is[AppliedType]
       && checkConformsAppliedType(tp1.as[AppliedType], tp2.as[AppliedType])
    || tp1.is[AppliedType] && checkConformsProxyType(tp1.as[AppliedType], tp2)
    || tp2.is[AppliedType] && checkConformsProxyType(tp1, tp2.as[AppliedType])
    || tp1.is[TypeBound] && tp2.is[TypeBound]
       && checkConformsTypeBound(tp1.as[TypeBound], tp2.as[TypeBound])
    || tp2.is[TypeBound] && checkConforms(tp1, tp2.as[TypeBound].lo)
    || tp1.is[TypeBound] && checkConforms(tp1.as[TypeBound].hi, tp2)
  }

  private def checkConformsAppliedType(tp1: AppliedType, tp2: AppliedType)(using ass: Assumptions): Boolean =
    ass.apps.get(tp1) match
      case Some(tps) =>
        if tps.contains(tp2) then
          true
        else
          val ass2 = ass.copy(apps = ass.apps.updated(tp1, tp2 :: tps))
          val tp1b = tp1.dealias
          val tp2b = tp2.dealias
          tp1b.isGrounded && tp2b.isGrounded
          && checkConforms(tp1b, tp2b)(using ass2)

      case None =>
        val ass2 = ass.copy(apps = ass.apps.updated(tp1, tp2 :: Nil))
        checkConforms(tp1.dealias, tp2.dealias)(using ass2)

  private def checkConformsTypeRef(tp1: TypeRef, tp2: TypeRef)(using ass: Assumptions): Boolean =
    ass.syms.get(tp1.symbol) match
      case Some(syms) =>
        if syms.contains(tp2.symbol) then
          true
        else
          val ass2 = ass.copy(syms = ass.syms.updated(tp1.symbol, tp2.symbol :: syms))
          checkConforms(tp1.symbol.info, tp2.symbol.info)(using ass2)

      case None =>
        val ass2 = ass.copy(syms = ass.syms.updated(tp1.symbol, tp2.symbol :: Nil))
        checkConforms(tp1.symbol.info, tp2.symbol.info)(using ass2)

  private def checkConformsProxyType(tp1: AppliedType | TypeRef, tp2: Type)(using ass: Assumptions): Boolean =
    val tp1b = tp1.dealias
    tp1b.isGrounded && checkConforms(tp1b, tp2)

  private def checkConformsProxyType(tp1: Type, tp2: AppliedType | TypeRef)(using ass: Assumptions): Boolean =
    val tp2b = tp2.dealias
    tp2b.isGrounded && checkConforms(tp1, tp2b)

  private def checkConformsRecordType(tp1: RecordType, tp2: RecordType)(using Assumptions): Boolean =
    val names1 = tp1.fieldNames
    val names2 = tp2.fieldNames
    names1.size >= names2.size && names1.zip(names2).forall: (a, b) =>
      a == b && checkConforms(tp1.fieldType(a), tp2.fieldType(b))

  private def checkConformsTypeBound(tp1: TypeBound, tp2: TypeBound)(using Assumptions): Boolean =
    checkConforms(tp2.lo, tp1.lo) && checkConforms(tp1.hi, tp2.hi)

  /** The common result type of two different types.
    *
    * This method is used to compute the result type of if- and match-
    * expressions.
    *
    * The logic is different from computing join in the subtype lattice:
    *
    * - ErrorType always dominates
    * - VoidType dominates BottomType
    */
  def commonResultType(tp1: Type, tp2: Type): Option[Type] =
    if tp1.isError || tp2.isError then Some(ErrorType)
    else if tp1.isVoid && tp2.isBottom then Some(VoidType)
    else if tp1.isBottom && tp2.isVoid then Some(VoidType)
    else if conforms(tp1, tp2) then Some(tp2)
    else if conforms(tp2, tp1) then Some(tp1)
    else None

  /** Substitute type params with the given types */
  def substTypeParams(tpe: Type, to: List[Type]): Type =
    tpe match
      case TypeParamRef(_, index) =>
        to(index)

      case VoidType | ErrorType | AnyType | BottomType | IntType | BoolType =>
        tpe

      case _: TypeRef =>
        tpe

      case RecordType(fields) =>
        val fields2 =
          for (name, tpe) <- fields
          yield name -> substTypeParams(tpe, to)
        RecordType(fields2)

      case UnionType(branches) =>
        val branches2 =
          for
            (tag, tps) <- branches
          yield
            tag -> tps.map(tp => substTypeParams(tp, to))
        UnionType(branches2)

      case AppliedType(tctor, targs) =>
        // first-class type ctor might be supported later
        val tctor2 = substTypeParams(tctor, to)
        val targs2 = for targ <- targs yield substTypeParams(targ, to)
        AppliedType(tctor2, targs2)

      case _: TypeLambda | _: PolyType =>
        // nested type lambdas or polymorphic types are not supported
        tpe

      case TypeBound(lo, hi) =>
        TypeBound(substTypeParams(lo, to), substTypeParams(hi, to))

      case ProcType(names, paramTypes, resType) =>
        // proc can be nested inside poly type
        val paramTypes2 = paramTypes.map(tp => substTypeParams(tp, to))
        val resType2 = substTypeParams(resType, to)
        ProcType(names, paramTypes2, resType2)

      case tp: DelayedType =>
        substTypeParams(tp.underlying, to)

  /** Substitute type symbols with the supplied types.
    *
    * This method is used in type checking definitions with type parameters.
    */
  def substSymbols(tpe: Type, substs: Map[Symbol, Type]): Type =
    tpe match
      case VoidType | ErrorType | AnyType | BottomType | IntType | BoolType =>
        tpe

      case TypeRef(sym) =>
        substs.getOrElse(sym, tpe)

      case RecordType(fields) =>
        val fields2 =
          for (name, tpe) <- fields
          yield name -> substSymbols(tpe, substs)
        RecordType(fields2)

      case UnionType(branches) =>
        val branches2 =
          for
            (tag, tps) <- branches
          yield
            tag -> tps.map(tp => substSymbols(tp, substs))
        UnionType(branches2)

      case AppliedType(tctor, targs) =>
        // first-class type ctor might be supported later
        val tctor2 = substSymbols(tctor, substs)
        val targs2 = for targ <- targs yield substSymbols(targ, substs)
        AppliedType(tctor2, targs2)

      case TypeLambda(names, bounds, resType) =>
        val bounds2 = for bound <- bounds yield substSymbols(bound, substs)
        val resType2 = substSymbols(resType, substs)
        TypeLambda(names, bounds2, resType2)

      case PolyType(names, bounds, resType) =>
        val bounds2 = for bound <- bounds yield substSymbols(bound, substs)
        val resType2 = substSymbols(resType, substs)
        PolyType(names, bounds2, resType2)

      case _: TypeParamRef => tpe

      case TypeBound(lo, hi) =>
        TypeBound(substSymbols(lo, substs), substSymbols(hi, substs))

      case ProcType(names, paramTypes, resType) =>
        // proc can be nested inside poly type
        val paramTypes2 = paramTypes.map(tp => substSymbols(tp, substs))
        val resType2 = substSymbols(resType, substs)
        ProcType(names, paramTypes2, resType2)

      case tp: DelayedType =>
        substSymbols(tp.underlying, substs)
