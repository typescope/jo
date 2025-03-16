package sast

import Types.*

import common.Debug

object Subtyping:
  /** A subtyping task `left <: right` */
  case class Task(left: Type, right: Type)

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
  class Context(
    subtypings: Map[ProxyType, List[ProxyType]],
    reducingLeft: List[ProxyType],
    reducingRight: List[ProxyType]):

    def this() = this(Map.empty, Nil, Nil)

    def withSubtyping(tp1: ProxyType, tp2: ProxyType): Context =
      val subtypings2 = this.subtypings.updated(tp1, tp2 :: this.subtypings.getOrElse(tp1, Nil))
      new Context(subtypings2, reducingLeft, reducingRight)

    def isSubtype(tp1: ProxyType, tp2: ProxyType): Boolean =
      this.subtypings.get(tp1) match
        case Some(tps) if tps.contains(tp2) => true
        case _ => false

    def reduceLeft(tp: ProxyType): Context =
      new Context(subtypings, tp :: reducingLeft, reducingRight)

    def reduceRight(tp: ProxyType): Context =
      new Context(subtypings, reducingLeft, tp :: reducingRight)

    def isReducingLeft(tp: ProxyType): Boolean =
      reducingLeft.contains(tp)

    def isReducingRight(tp: ProxyType): Boolean =
      reducingRight.contains(tp)

  /**
    * Check whether one type conforms to the other type
    *
    * We intentionally do not check subtyping of bound type. They may only
    * surface in deal with TypeRef, which is handled specially.
    */
  private def checkConforms(tp1: Type, tp2: Type)(using ctx: Context): Boolean = Debug.trace(s"${tp1.show} <: ${tp2.show}", enable = false) {
    tp1.isError
    || tp2.isError
    || tp1.isBottom && tp2.isValueType
    || tp2.isAnyType && tp1.isValueType
    || tp1 == tp2
    || tp1.is[TypeVar]
       && checkConformsProxyType(tp1.as[ProxyType], tp2)
    || tp2.is[TypeVar]
       && checkConformsProxyType(tp1, tp2.as[ProxyType])
    || tp1.is[ProxyType] && tp2.is[ProxyType]
       && checkConformsProxyType(tp1.as[ProxyType], tp2.as[ProxyType])
    || tp1.is[ProxyType]
       && checkConformsProxyType(tp1.as[ProxyType], tp2)
    || tp2.is[ProxyType]
       && checkConformsProxyType(tp1, tp2.as[ProxyType])
    || tp1.is[ObjectType] && tp2.is[ObjectType]
       && checkConformsObjectType(tp1.as[ObjectType], tp2.as[ObjectType])
    || tp1.is[RecordType] && tp2.is[RecordType]
       && checkConformsRecordType(tp1.as[RecordType], tp2.as[RecordType])
    || tp1.is[TagType] && tp2.is[TagType]
       && checkConformsTagType(tp1.as[TagType], tp2.as[TagType])
    || tp1.is[TagType] && tp2.is[UnionType]
       && checkConformsTagTypeToUnionType(tp1.as[TagType], tp2.as[UnionType])
    || tp1.is[UnionType] && tp2.is[UnionType]
       && checkConformsUnionType(tp1.as[UnionType], tp2.as[UnionType])
    || tp1.is[ProcType] && tp2.is[ProcType]
       && checkConformsProcType(tp1.as[ProcType], tp2.as[ProcType])
  }

  private def checkConforms(tp1: Type, tp2: Type, lessThan: Boolean)(using ctx: Context): Boolean =
    if lessThan then checkConforms(tp1, tp2) else checkConforms(tp2, tp1)

  private def checkConformsProxyType(tp1: ProxyType, tp2: ProxyType)(using ctx: Context): Boolean =
    ctx.isSubtype(tp1, tp2) || {
      given Context = ctx.withSubtyping(tp1, tp2)
      checkConformsProxyType(tp1, tp2, lessThan = true)
    }

  private def checkConformsProxyType(tp1: ProxyType, tp2: Type)(using ctx: Context): Boolean =
    !ctx.isReducingLeft(tp1) && checkConformsProxyType(tp1, tp2, lessThan = true)

  private def checkConformsProxyType(tp1: Type, tp2: ProxyType)(using ctx: Context): Boolean =
    !ctx.isReducingRight(tp2) && checkConformsProxyType(tp2, tp1, lessThan = false)

  private def checkConformsProxyType(tp1: ProxyType, tp2: Type, lessThan: Boolean)(using ctx: Context): Boolean =
    def reducingCtx(tp: ProxyType): Context =
      if lessThan then ctx.reduceLeft(tp) else ctx.reduceRight(tp)

    def continue(tp1b: Type)(using Context): Boolean =
      checkConforms(tp1b, tp2, lessThan)

    tp1 match
      case AppliedType(tctor, targs) =>
        tctor match
          case tref: TypeRef =>
            val isReducing = if lessThan then ctx.isReducingLeft(tref) else ctx.isReducingRight(tref)
            !isReducing && reduce(tref, maximize = lessThan).match
              case tl: TypeLambda =>
                given Context = reducingCtx(tref)
                continue(TypeOps.substTypeParams(tl.body, targs))

              case tctor =>
                false

          case ErrorType => true

          case _ =>
            throw new Exception("Unexpected type constructor: " + tctor.show)

      case tref: TypeRef =>
        given Context = reducingCtx(tref)
        continue(reduce(tref, maximize = lessThan))

      case tvar: TypeVar =>
        given Context = reducingCtx(tvar)
        val tasks = if lessThan then tvar.isSubtype(tp2) else tvar.isSuptype(tp2)
        tasks.forall(task => checkConforms(task.left, task.right))

  /** Reduce a type reference
    *
    * It's important to not return the original type reference if the type
    * cannot be reduced. Otherwise, the recursive subtyping can trivially
    * succeed for two unrelated symbolic types.
    */
  private def reduce(tp: TypeRef, maximize: Boolean): Type =
    val sym = tp.symbol
    sym.info match
      case bound: TypeBound =>
        // Type definitions can also be bounded
        if sym.isTypeParameter then
          if maximize then bound.hi else bound.lo

        else
          // Treat type definition with only bounds as nominal type
          if maximize then AnyType else BottomType

      case tp =>
        tp

  private def checkConformsProcType(tp1: ProcType, tp2: ProcType)(using Context): Boolean =
    tp1.paramTypes.size == tp2.paramTypes.size
    && tp1.paramTypes.zip(tp2.paramTypes).forall: (paramType1, paramType2) =>
       checkConforms(paramType2, paramType1)
    && checkConforms(tp1.resultType, tp2.resultType)
    && {
      tp1.receives.isEmpty ||
      tp2.receives.nonEmpty && tp1.receives.get.forall { param =>
        tp2.receives.get.contains(param)
      }
    }

  // TODO: loosen record typing and use coersion semantics
  private def checkConformsRecordType(tp1: RecordType, tp2: RecordType)(using Context): Boolean =
    val names1 = tp1.fieldNames
    val names2 = tp2.fieldNames
    names1.size >= names2.size && names1.zip(names2).forall: (a, b) =>
      a == b && checkConforms(tp1.fieldType(a), tp2.fieldType(b))

  private def checkConformsObjectType(tp1: ObjectType, tp2: ObjectType)(using Context): Boolean =
    val fieldsConform =
      val names1 = tp1.fieldNames
      val names2 = tp2.fieldNames
      names1.size >= names2.size && names1.zip(names2).forall: (a, b) =>
        a == b
        && tp1.isMutable(a) == tp2.isMutable(b)
        && checkConforms(tp1.termMember(a), tp2.termMember(b))

    fieldsConform && {
      val names1 = tp1.methodNames
      val names2 = tp2.methodNames
      names1.size >= names2.size && names1.zip(names2).forall: (a, b) =>
        a == b
        && checkConforms(tp1.termMember(a), tp2.termMember(b))
    }

  private def checkConformsUnionType(tp1: UnionType, tp2: UnionType)(using Context): Boolean =
    // The ordering of the tags does not matter
    tp1.tags.forall: tag =>
      val tagType1 = tp1.tagType(tag)
      val tagType2 = tp2.tagType(tag)
      checkConforms(tagType1, tagType2)

  private def checkConformsTagType(tp1: TagType, tp2: TagType)(using Context): Boolean =
    val shapeOK = tp1.tag == tp2.tag && tp1.paramTypes.size >= tp2.paramTypes.size
    shapeOK && tp1.paramTypes.zip(tp2.paramTypes).forall: (paramType1, paramType2) =>
      // param names do not matter
      checkConforms(paramType1, paramType2)

  private def checkConformsTagTypeToUnionType(tp1: TagType, tp2: UnionType)(using Context): Boolean =
    tp2.getTagType(tp1.tag) match
      case Some(tagType2) => checkConforms(tp1, tagType2)
      case None => false
