import Types.*
import Symbols.*

object Subtyping:
  /** Whether `tp1` conforms to `tp2` */
  def conforms(tp1: Type, tp2: Type): Boolean =
    checkConforms(tp1,tp2)(using new Context())

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
  private class Context(
    subtypings: Map[Type, List[Type]],
    reducing: List[Symbol]):       // symbols under reduction, used to avoid non-termination

    def this() = this(Map.empty, Nil)

    def withSubtyping(tp1: Type, tp2: Type): Context =
      val subtypings2 = this.subtypings.updated(tp1, tp2 :: this.subtypings.getOrElse(tp1, Nil))
      new Context(subtypings2, reducing)

    def isSubtype(tp1: Type, tp2: Type): Boolean =
      this.subtypings.get(tp1) match
        case Some(tps) if tps.contains(tp2) => true
        case _ => false

    def withReducing(sym: Symbol) =
      new Context(subtypings, sym :: reducing)

    def isReducing(sym: Symbol): Boolean =
      reducing.contains(sym)

  /** Check whether one type conforms to the other type */
  private def checkConforms(tp1: Type, tp2: Type)(using Context): Boolean = Debug.trace(s"${tp1.show} <: ${tp2.show}", enable = false) {
    tp1.isError
    || tp2.isError
    || tp1.isBottom
    || tp2.isAny && tp1.isValueType
    || tp1 == tp2
    || tp1.is[TypeRef] && tp2.is[TypeRef]
       && checkConformsTypeRef(tp1.as[TypeRef], tp2.as[TypeRef])
    || tp1.is[TypeRef]
       && reduceTypeAndThen(tp1.as[TypeRef]) { tp1b => checkConforms(tp1b, tp2) }
    || tp2.is[TypeRef]
       && reduceTypeAndThen(tp2.as[TypeRef]) { tp2b => checkConforms(tp1, tp2b) }
    || tp1.is[DelayedType] && checkConforms(tp1.as[DelayedType].underlying, tp2)
    || tp2.is[DelayedType] && checkConforms(tp1, tp2.as[DelayedType].underlying)
    || tp1.is[FunctionType] && tp2.is[FunctionType]
       && checkConformsFunctionType(tp1.as[FunctionType], tp2.as[FunctionType])
    || tp1.is[RecordType] && tp2.is[RecordType]
       && checkConformsRecordType(tp1.as[RecordType], tp2.as[RecordType])
    || tp1.is[AppliedType] && tp2.is[AppliedType]
       && checkConformsAppliedType(tp1.as[AppliedType], tp2.as[AppliedType])
    || tp1.is[AppliedType]
       && reduceTypeAndThen(tp1.as[AppliedType]) { tp1b => checkConforms(tp1b, tp2) }
    || tp2.is[AppliedType]
       && reduceTypeAndThen(tp2.as[AppliedType]) { tp2b => checkConforms(tp1, tp2b) }
    || tp1.is[TypeBound] && tp2.is[TypeBound]
       && checkConformsTypeBound(tp1.as[TypeBound], tp2.as[TypeBound])
    || tp2.is[TypeBound] && checkConforms(tp1, tp2.as[TypeBound].lo)
    || tp1.is[TypeBound] && checkConforms(tp1.as[TypeBound].hi, tp2)
  }

  private def checkConformsAppliedType(tp1: AppliedType, tp2: AppliedType)(using ctx: Context): Boolean =
    if ctx.isSubtype(tp1, tp2) then
      true
    else
      given Context = ctx.withSubtyping(tp1, tp2)
      reduceTypeAndThen(tp1): tp1b =>
        reduceTypeAndThen(tp2): tp2b =>
          checkConforms(tp1b, tp2b)

  private def checkConformsTypeRef(tp1: TypeRef, tp2: TypeRef)(using ctx: Context): Boolean =
    if ctx.isSubtype(tp1, tp2) then
      true
    else
      given Context = ctx.withSubtyping(tp1, tp2)
      reduceTypeAndThen(tp1): tp1b =>
        reduceTypeAndThen(tp2): tp2b =>
          checkConforms(tp1b, tp2b)

  private def reduceTypeAndThen
              (tp: AppliedType | TypeRef)
              (check: Context ?=> Type => Boolean)
              (using ctx: Context): Boolean =

    tp match
      case AppliedType(tctor, targs) =>
        tctor match
          case tref: TypeRef =>
            reduceTypeAndThen(tref): tctor2 =>
              tctor2 match
                case tl: TypeLambda =>
                  check(TypeOps.substTypeParams(tl.body, targs))
                case _ =>
                  check(tp)

          case _ => check(tp)

      case TypeRef(sym) =>
        if ctx.isReducing(sym) then
          // cycles detected
          false
        else
          given Context = ctx.withReducing(sym)
          check(TypeOps.stripDelayed(sym.info))

  private def checkConformsFunctionType(tp1: FunctionType, tp2: FunctionType)(using Context): Boolean =
    tp1.paramTypes.size == tp2.paramTypes.size
    && tp1.paramTypes.zip(tp2.paramTypes).forall: (paramType1, paramType2) =>
       checkConforms(paramType2, paramType1)
    && checkConforms(tp1.resultType, tp2.resultType)

  private def checkConformsRecordType(tp1: RecordType, tp2: RecordType)(using Context): Boolean =
    val names1 = tp1.fieldNames
    val names2 = tp2.fieldNames
    names1.size >= names2.size && names1.zip(names2).forall: (a, b) =>
      a == b && checkConforms(tp1.fieldType(a), tp2.fieldType(b))

  private def checkConformsTypeBound(tp1: TypeBound, tp2: TypeBound)(using Context): Boolean =
    checkConforms(tp2.lo, tp1.lo) && checkConforms(tp1.hi, tp2.hi)
