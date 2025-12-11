package sast

import Types.*
import Symbols.*

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
  class Context(subtypings: Map[Symbol, List[Symbol]]):
    def this() = this(Map.empty)

    def withSubtyping(tp1: Symbol, tp2: Symbol): Context =
      val subtypings2 = this.subtypings.updated(tp1, tp2 :: this.subtypings.getOrElse(tp1, Nil))
      new Context(subtypings2)

    def isSubtype(tp1: Symbol, tp2: Symbol): Boolean =
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
  private def checkConforms
      (tp1: Type, tp2: Type)(using ctx: Context, defn: Definitions)
  : Boolean = Debug.trace(s"${tp1.show} <: ${tp2.show}", enable = false) {
    // Each branch should be disjoint to avoid exponential blowup
    tp1.isError
    || tp2.isError
    || tp1.isBottom && tp2.isValueType
    || tp2.isAnyType && tp1.isValueType
    || ((tp1 `eq` tp2) || tp1.hashCode == tp2.hashCode && tp1 == tp2)
    || tp1.is[TypeVar] && checkConformsTypeVar(tp1.as[TypeVar], tp2, isLessThan = true)
    || tp2.is[TypeVar] && checkConformsTypeVar(tp2.as[TypeVar], tp1, isLessThan = false)
    || (tp1.is[ProxyType] || tp2.is[ProxyType])
       && checkConformsProxyType(tp1, tp2)
    || tp1.is[ObjectType] && tp2.is[ObjectType]
       && checkConformsObjectType(tp1.as[ObjectType], tp2.as[ObjectType])
    || tp1.is[RecordType] && tp2.is[RecordType]
       && checkConformsRecordType(tp1.as[RecordType], tp2.as[RecordType])
    || tp1.is[UnionType] && tp2.is[UnionType]
       && checkConformsUnionType(tp1.as[UnionType], tp2.as[UnionType])
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

  /** Either `tp1` or `tp2` is proxy type */
  private def checkConformsProxyType(tp1: Type, tp2: Type)(using ctx: Context, defn: Definitions): Boolean =
    if tp1.is[ProxyType] && tp2.is[ProxyType] then
      val proxy1 = tp1.as[ProxyType]
      val proxy2 = tp2.as[ProxyType]

      if proxy1.is[StaticRef] && proxy2.is[StaticRef] then
        val tsym1 = proxy1.as[StaticRef].symbol
        val tsym2 = proxy2.as[StaticRef].symbol

        ctx.isSubtype(tsym1, tsym2) || {
          given Context = ctx.withSubtyping(tsym1, tsym2)

          if !TypeOps.isGrounded(proxy1) then
            recur(proxy1.dealias, tp2)

          else if !TypeOps.isGrounded(proxy2) then
            recur(tp1, proxy2.dealias)

          else
            checkConformsBothGroundedProxyType(proxy1, proxy2)
        }

      else if proxy1.is[AppliedType] && proxy2.is[AppliedType] then
        val appliedType1 = proxy1.as[AppliedType]
        val appliedType2 = proxy2.as[AppliedType]
        val tctor1 = appliedType1.tctor
        val tctor2 = appliedType2.tctor

        if ctx.isSubtype(tctor1, tctor2) then
          // If we already make an assumption, do not try reduction any more
          appliedType1.targs.size == appliedType2.targs.size
          && appliedType1.targs.zip(appliedType2.targs).forall: (tp1, tp2) =>
             recur(tp1, tp2) && recur(tp2, tp1)

        else
          if !TypeOps.isGrounded(proxy1) || !TypeOps.isGrounded(proxy2) then
            given Context = ctx.withSubtyping(tctor1, tctor2)
            recur(proxy1.dealias, proxy2.dealias)

          else
            checkConformsBothGroundedProxyType(proxy1, proxy2)

      else
        if !TypeOps.isGrounded(proxy1) then
          recur(proxy1.dealias, tp2)

        else if !TypeOps.isGrounded(proxy2) then
          recur(tp1, proxy2.dealias)

        else
          checkConformsBothGroundedProxyType(proxy1, proxy2)

    else if tp1.is[ProxyType] then
      val proxy1 = tp1.as[ProxyType]
      if TypeOps.isGrounded(proxy1) then
        if !TypeOps.isGrounded(tp2) then
          recur(proxy1, tp2.dealias)
        else if tp2.is[UnionType] then
          if proxy1.isTermRef then
            recur(proxy1.widen, tp2.asUnionType)
          else
            checkConformsClassTypeToUnionType(proxy1, tp2.asUnionType)
        else
          // tp2 must be grouned, otherwise it's a proxy type and it goes to case 1
          checkConforms(TypeOps.approx(proxy1, isUp = true), tp2)

      else
        recur(proxy1.dealias, tp2)

    else
      val proxy2 = tp2.as[ProxyType]
      if TypeOps.isGrounded(proxy2) then
        // tp1 must be grouned, otherwise it's a proxy type and it goes to case 1
        if !TypeOps.isGrounded(tp1) then
          recur(tp1.dealias, proxy2)
        else if tp1.is[ConstantType] then
          recur(tp1.widenConstType, proxy2)

        else
          recur(tp1, TypeOps.approx(proxy2, isUp = false))

      else
        recur(tp1, proxy2.dealias)

  /** Either `tp1` or `tp2` is proxy type */
  private def checkConformsTypeVar(tvar: TypeVar, tp2: Type, isLessThan: Boolean)(using ctx: Context, defn: Definitions): Boolean =
    if tvar.isInstantiated then
      if isLessThan then recur(tvar.instantiated, tp2)
      else recur(tp2, tvar.instantiated)
    else
      val tasks = if isLessThan then tvar.checkSubtype(tp2) else tvar.checkSuptype(tp2)
      tasks.forall(task => recur(task.left, task.right))

  private def checkConformsBothGroundedProxyType(proxy1: ProxyType, proxy2: ProxyType)(using ctx: Context, defn: Definitions): Boolean =
    if proxy1.is[AppliedType] && proxy2.is[AppliedType] then
      val AppliedType(tctor1, targs1) = proxy1: @unchecked
      val AppliedType(tctor2, targs2) = proxy2: @unchecked
      tctor1 == tctor2 && {
        targs1.zip(targs2).forall: (tp1, tp2) =>
          recur(tp1, tp2) && recur(tp2, tp1)
      }

    else
      proxy1 match
        case StaticRef(sym) if sym.info.is[TypeBound] =>
          recur(sym.info.as[TypeBound].hi, proxy2)

        case AppliedType(sym, targs) =>
          sym.info match
            case tl @ TypeLambda(_, _: TypeBound, _) =>
              recur(tl.instantiate(targs).as[TypeBound].hi, proxy2)

            case _ =>
              false

        case ref: RefType =>
          if ref.isTermRef then
            recur(proxy1.widenTermRef, proxy2)
          else
            false

  private def checkConformsProcType(tp1: ProcType, tp2: ProcType)
      (using ctx: Context, defn: Definitions)
  : Boolean =

    tp1.tparams.size == tp2.tparams.size
    && tp1.params.size == tp2.params.size
    && tp1.autos.size == tp2.autos.size
    && {
      given Context =
        if tp1.tparams.isEmpty then
          ctx
        else
          tp1.tparams.zip(tp2.tparams).foldLeft(ctx):
            case (ctx, (tparam1, tparam2)) =>
              ctx.withSubtyping(tparam1, tparam2).withSubtyping(tparam2, tparam1)

      tp1.paramTypes.zip(tp2.paramTypes).forall: (paramType1, paramType2) =>
        recur(paramType2, paramType1)
      && tp1.autoTypes.zip(tp2.autoTypes).forall: (autoType1, autoType2) =>
        recur(autoType2, autoType1)
      && recur(tp1.resultType, tp2.resultType)
    }
    && tp1.receives.forall(eff => tp2.receives.contains(eff))

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
    tp1.classes.forall: cls =>
      val classType1 = tp1.classType(cls)
      tp2.hasClass(cls) && {
        val classType2 = tp2.classType(cls)
        recur(classType1, classType2)
      }

  private def checkConformsClassTypeToUnionType(tp1: Type, tp2: UnionType)(using Context, Definitions): Boolean =
    def check(cls: Symbol): Boolean =
      tp2.getClassType(cls) match
        case Some(classType2) => recur(tp1, classType2)
        case None => false

    tp1 match
      case StaticRef(cls) => check(cls)
      case AppliedType(cls, _) => check(cls)
      case _ => false
