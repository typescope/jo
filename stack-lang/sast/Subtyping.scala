package sast

import Types.*

import common.Debug

object Subtyping:
  var debug = false

  /** A subtyping task `left <: right` */
  case class Task(left: Type, right: Type)

  /** Whether `tp1` conforms to `tp2` */
  def conforms(tp1: Type, tp2: Type)(using defn: Definitions): Boolean =
    defn.cache.conforms(tp1, tp2, cache = true):
      checkConforms(tp1,tp2)(using new Context())

  def isEqualType(tp1: Type, tp2: Type)(using Definitions): Boolean =
    conforms(tp1, tp2) && conforms(tp2, tp1)

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
  class Context(subtypings: Map[ProxyType, List[ProxyType]]):
    def this() = this(Map.empty)

    def withSubtyping(tp1: ProxyType, tp2: ProxyType): Context =
      val subtypings2 = this.subtypings.updated(tp1, tp2 :: this.subtypings.getOrElse(tp1, Nil))
      new Context(subtypings2)

    def isSubtype(tp1: ProxyType, tp2: ProxyType): Boolean =
      this.subtypings.get(tp1) match
        case Some(tps) if tps.contains(tp2) => true
        case _ => false

    def hasAssumptions: Boolean = subtypings.nonEmpty

  /**
    * Check whether one type conforms to the other type
    *
    * We intentionally do not check subtyping of bound type. They may only
    * surface in deal with StaticRef, which is handled specially.
    */
  private def checkConforms(tp1: Type, tp2: Type)(using ctx: Context, defn: Definitions): Boolean = Debug.trace(s"${tp1.show} <: ${tp2.show}", enable = false) {
    // Each branch should be disjoint to avoid exponential blowup
    tp1.isError
    || tp2.isError
    || tp1.isBottom && tp2.isValueType
    || tp2.isAnyType && tp1.isValueType
    || ((tp1 `eq` tp2) || tp1.hashCode == tp2.hashCode && tp1 == tp2)
    || (tp1.is[ProxyType] || tp2.is[ProxyType])
       && checkConformsProxyType(tp1, tp2)
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
    || tp1.is[ConstantType]
       && recur(tp1.as[ConstantType].underlying, tp2)
    || tp1.is[ProcType] && tp2.is[ProcType]
       && checkConformsProcType(tp1.as[ProcType], tp2.as[ProcType])
    || tp1.is[TypeBound]
       && recur(tp1.as[TypeBound].hi, tp2)
    || tp2.is[TypeBound]
       && recur(tp1, tp2.as[TypeBound].lo)
  }

  private def recur(tp1: Type, tp2: Type)(using ctx: Context, defn: Definitions): Boolean =
    Debug.trace(s"${tp1.show} <: ${tp2.show}", enable = false) {
      defn.cache.conforms(tp1, tp2, cache = ctx.hasAssumptions):
        Subtyping.checkConforms(tp1, tp2)
    }

  private def recur(tp1: Type, tp2: Type, lessThan: Boolean)(using ctx: Context, defn: Definitions): Boolean =
    if lessThan then recur(tp1, tp2) else recur(tp2, tp1)

  private def checkConformsAppliedGrounded(tp1: AppliedType, tp2: AppliedType)(using ctx: Context, defn: Definitions): Boolean =
    val AppliedType(tref1: StaticRef, targs1) = tp1: @unchecked
    val AppliedType(tref2: StaticRef, targs2) = tp2: @unchecked
    tref1.refers(tref2.symbol) && {
      // TODO: follow variance spec
      targs1.zip(targs2).forall: (tp1, tp2) =>
        recur(tp1, tp2) && recur(tp2, tp1)
    }

  /** Either `tp1` or `tp2` is proxy type */
  private def checkConformsProxyType(tp1: Type, tp2: Type)(using ctx: Context, defn: Definitions): Boolean =
    if tp1.is[ProxyType] && tp2.is[ProxyType] then
      val proxy1 = tp1.as[ProxyType]
      val proxy2 = tp2.as[ProxyType]
      checkConformsBothProxyType(proxy1, proxy2)

    else if tp1.is[ProxyType] then
      val proxy1 = tp1.as[ProxyType]
      TypeOps.isGroundedProxy(proxy1) && reduceProxyType(proxy1, tp2, lessThan = true)

    else
      val proxy2 = tp2.as[ProxyType]
      TypeOps.isGroundedProxy(proxy2) && reduceProxyType(proxy2, tp1, lessThan = false)

  private def checkConformsBothProxyType(proxy1: ProxyType, proxy2: ProxyType)(using ctx: Context, defn: Definitions): Boolean =
    if ctx.isSubtype(proxy1, proxy2) then
      true

    else if proxy1.is[AppliedType] && proxy2.is[AppliedType] then
      val tctor1 = proxy1.as[AppliedType].tctor.as[StaticRef]
      val tctor2 = proxy2.as[AppliedType].tctor.as[StaticRef]

      if proxy1.isGrounded && proxy2.isGrounded then
        checkConformsAppliedGrounded(proxy1.as[AppliedType], proxy2.as[AppliedType])

      else
        ctx.isSubtype(tctor1, tctor2) || {
          given Context = ctx.withSubtyping(tctor1, tctor2)
          if !proxy1.isGrounded then
            TypeOps.isGroundedProxy(proxy1) && reduceProxyType(proxy1, proxy2, lessThan = true)

          else if !proxy2.isGrounded then
            TypeOps.isGroundedProxy(proxy2) && reduceProxyType(proxy2, proxy1, lessThan = false)

          else
            throw new Exception(s"Unexpected types tp1 = ${proxy1.show}, tp2 = ${proxy2.show}")
        }

    else if !proxy1.isGrounded || proxy1.is[TypeVar] || proxy1.isTermRef then
      given Context = ctx.withSubtyping(proxy1, proxy2)
      TypeOps.isGroundedProxy(proxy1) && reduceProxyType(proxy1, proxy2, lessThan = true)

    else if !proxy2.isGrounded || proxy2.is[TypeVar] || proxy2.isTermRef then
      given Context = ctx.withSubtyping(proxy1, proxy2)
      TypeOps.isGroundedProxy(proxy2) && reduceProxyType(proxy2, proxy1, lessThan = false)

    else
      // Give the bounds a try --- this can blow up
      TypeOps.isGroundedProxy(proxy1) && reduceProxyType(proxy1, proxy2, lessThan = true)
      || TypeOps.isGroundedProxy(proxy2) && reduceProxyType(proxy2, proxy1, lessThan = false)
    end if

  private def reduceProxyType(tp1: ProxyType, tp2: Type, lessThan: Boolean)(using ctx: Context, defn: Definitions): Boolean =
    def continue(tp1b: Type)(using Context): Boolean =
      recur(tp1b, tp2, lessThan)

    tp1 match
      case AppliedType(tctor, targs) =>
        tctor match
          case tref: StaticRef =>
            tref.symbol.info match
              case tl: TypeLambda =>
                val tp1Reduced = tl.instantiate(targs)
                continue(tp1Reduced)

              case tref: StaticRef =>
                // alias
                continue(AppliedType(tref, targs))

              case tctor =>
                false

          case ErrorType => true

          case _ =>
            throw new Exception("Unexpected type constructor: " + tctor.show)

      case StaticRef(sym) =>
        /* Reduce a type reference
         *
         * It's important to not return the original type reference if the type
         * cannot be reduced. Otherwise, the recursive subtyping can trivially
         * succeed for two unrelated symbolic types.
         */
        val tp1Reduced =
          sym.info match
            case bound: TypeBound =>
              if lessThan then bound.hi else bound.lo

            case tp =>
              // A term reference has the bottom type in T <: StaticRef(a)
              if sym.isType || lessThan then tp else BottomType

        continue(tp1Reduced)

      case mref: MemberRef =>
        val tp1Reduced = if mref.symbol.isType || lessThan then mref.info else BottomType
        continue(tp1Reduced)

      case tvar: TypeVar =>
        val tasks = if lessThan then tvar.isSubtype(tp2) else tvar.isSuptype(tp2)
        tasks.forall(task => recur(task.left, task.right))

  private def checkConformsProcType(tp1: ProcType, tp2: ProcType)
      (using ctx: Context, defn: Definitions)
  : Boolean =

    tp1.tparams.size == tp2.tparams.size
    && tp1.params.size == tp2.params.size
    && tp1.autos.size == tp2.autos.size
    && {
      // TODO: once type bounds are enabled, check type bounds
      given Context =
        if tp1.tparams.isEmpty then
          ctx
        else
          tp1.tparams.zip(tp2.tparams).foldLeft(ctx):
            case (ctx, (tparam1, tparam2)) =>
              val tref1 = StaticRef(tparam1)
              val tref2 = StaticRef(tparam2)
              ctx.withSubtyping(tref1, tref2).withSubtyping(tref2, tref1)

      tp1.paramTypes.zip(tp2.paramTypes).forall: (paramType1, paramType2) =>
        recur(paramType2, paramType1)
      && tp1.autoTypes.zip(tp2.autoTypes).forall: (autoType1, autoType2) =>
        recur(autoType2, autoType1)
      && recur(tp1.resultType, tp2.resultType)
    }
    && tp1.receives.forall(tp2.receives.contains)

  // TODO: loosen record typing and use coersion semantics
  private def checkConformsRecordType(tp1: RecordType, tp2: RecordType)(using Context, Definitions): Boolean =
    val names1 = tp1.fieldNames
    val names2 = tp2.fieldNames
    names1.size >= names2.size && names1.zip(names2).forall: (a, b) =>
      a == b && recur(tp1.fieldType(a), tp2.fieldType(b))

  private def checkConformsObjectType(tp1: ObjectType, tp2: ObjectType)(using Context, Definitions): Boolean =
    val fieldsConform =
      val names1 = tp1.fieldNames
      val names2 = tp2.fieldNames
      names1.size >= names2.size && names1.zip(names2).forall: (a, b) =>
        a == b
        && ({
         tp1.isMutable(a)
         && tp2.isMutable(b)
         && recur(tp1.termMember(a), tp2.termMember(b))
         && recur(tp2.termMember(a), tp1.termMember(b))
        } || {
         !tp1.isMutable(a)
         && !tp2.isMutable(b)
         && recur(tp1.termMember(a), tp2.termMember(b))
        })

    fieldsConform && {
      val names1 = tp1.methodNames
      val names2 = tp2.methodNames
      names1.size >= names2.size && names1.zip(names2).forall: (a, b) =>
        a == b
        && recur(tp1.termMember(a), tp2.termMember(b))
    }

  private def checkConformsUnionType(tp1: UnionType, tp2: UnionType)(using Context, Definitions): Boolean =
    // The ordering of the tags does not matter
    tp1.tags.forall: tag =>
      val tagType1 = tp1.tagType(tag)
      tp2.hasTag(tag) && {
        val tagType2 = tp2.tagType(tag)
        recur(tagType1, tagType2)
      }

  private def checkConformsTagType(tp1: TagType, tp2: TagType)(using Context, Definitions): Boolean =
    val shapeOK = tp1.tag == tp2.tag && tp1.paramTypes.size >= tp2.paramTypes.size
    shapeOK && tp1.paramTypes.zip(tp2.paramTypes).forall: (paramType1, paramType2) =>
      // param names do not matter
      recur(paramType1, paramType2)

  private def checkConformsTagTypeToUnionType(tp1: TagType, tp2: UnionType)(using Context, Definitions): Boolean =
    tp2.getTagType(tp1.tag) match
      case Some(tagType2) => recur(tp1, tagType2)
      case None => false
