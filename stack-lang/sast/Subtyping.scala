package sast

import Types.*
import Symbols.*

import common.Debug

/** Check subtyping of two types
  *
  * It is intentional that we disallow subtyping recursive types.
  */
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

  class Context(checking: Map[Symbol, List[Symbol]]):
    def this() = this(Map.empty)

    def withSubtyping(tp1: Symbol, tp2: Symbol): Context =
      val checking2 = this.checking.updated(tp1, tp2 :: this.checking.getOrElse(tp1, Nil))
      new Context(checking2)

    def isChecking(tp1: Symbol, tp2: Symbol): Boolean =
      this.checking.get(tp1) match
        case Some(tps) if tps.contains(tp2) => true
        case _ => false

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
    val fastPath =
        tp1.isError
      || tp2.isError
      || tp1.isBottom && tp2.isValueType && !tp2.is[TypeVar]
      || tp2.isAnyType && tp1.isValueType && !tp1.is[TypeVar]
      || ((tp1 `eq` tp2) || tp1.hashCode == tp2.hashCode && tp1 == tp2)

    if fastPath then
      true

    else if tp1.is[TypeVar] then
        checkConformsTypeVar(tp1.as[TypeVar], tp2, isLessThan = true)

    else if tp2.is[TypeVar] then
      checkConformsTypeVar(tp2.as[TypeVar], tp1, isLessThan = false)

    else if tp1.is[ProxyType] || tp2.is[ProxyType] then
      checkConformsProxyType(tp1, tp2)

    else if tp1.is[RecordType] && tp2.is[RecordType] then
      checkConformsRecordType(tp1.as[RecordType], tp2.as[RecordType])

    else if tp1.is[UnionType] && tp2.is[UnionType] then
     checkConformsUnionType(tp1.as[UnionType], tp2.as[UnionType])

    else if tp1.is[ProcType] && tp2.is[ProcType] then
      checkConformsProcType(tp1.as[ProcType], tp2.as[ProcType])

    else if tp1.is[LambdaType] && tp2.is[LambdaType] then
      checkConformsLambdaType(tp1.as[LambdaType], tp2.as[LambdaType])

    else
      false
  }

  private def recur(tp1: Type, tp2: Type)(using ctx: Context, defn: Definitions): Boolean =
    Debug.trace(s"${tp1.show} <: ${tp2.show}", enable = false):
      defn.cache.conforms(tp1, tp2, cache = true):
        Subtyping.checkConforms(tp1, tp2)

  /** Either `tp1` or `tp2` is proxy type */
  private def checkConformsProxyType(tp1: Type, tp2: Type)(using ctx: Context, defn: Definitions): Boolean =
    if tp1.is[ProxyType] && tp2.is[ProxyType] then
      val proxy1 = tp1.as[ProxyType]
      val proxy2 = tp2.as[ProxyType]

      if proxy1.is[StaticRef] && proxy2.is[StaticRef] then
        val tsym1 = proxy1.as[StaticRef].symbol
        val tsym2 = proxy2.as[StaticRef].symbol

        !ctx.isChecking(tsym1, tsym2) && {
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

        if ctx.isChecking(tctor1, tctor2) then
          false
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
        else if proxy1.isTermRef then
          recur(proxy1.widen, tp2)
        else
          // tp2 must be grouned, otherwise it's a proxy type and it goes to case 1
          tp2.is[UnionType] && checkConformsClassTypeToUnionType(proxy1, tp2.asUnionType)

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
          false
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
      checkDirectViewSubtyping(proxy1, proxy2)
      || {
        val AppliedType(tctor1, targs1) = proxy1: @unchecked
        val AppliedType(tctor2, targs2) = proxy2: @unchecked
        tctor1 == tctor2 && {
          targs1.zip(targs2).forall: (tp1, tp2) =>
            recur(tp1, tp2) && recur(tp2, tp1)
        }
      }

    else
      proxy1 match
        case _: AppliedType =>
          checkDirectViewSubtyping(proxy1, proxy2)

        case ref: RefType =>
          if ref.isTermRef then
            recur(proxy1.widenTermRef, proxy2)
          else
            checkDirectViewSubtyping(proxy1, proxy2)

  private def checkConformsLambdaType(tp1: LambdaType, tp2: LambdaType)
      (using ctx: Context, defn: Definitions)
  : Boolean =

    tp1.params.size == tp2.params.size
    && recur(tp1.resultType, tp2.resultType)
    && {
      tp1.params.zip(tp2.params).forall: (paramType1, paramType2) =>
        recur(paramType2, paramType1)
    }
    && tp1.receives.forall(eff => tp2.receives.contains(eff))

  private def checkConformsProcType(tp1: ProcType, tp2: ProcType)
      (using ctx: Context, defn: Definitions)
  : Boolean =

    tp1.tparams.size == tp2.tparams.size
    && tp1.params.size == tp2.params.size
    && tp1.autos.size == tp2.autos.size
    && {
      val subst: ProcType =
        if tp2.tparams.isEmpty then
          tp2
        else
          val tparamRefs = tp1.tparams.map(sym => StaticRef(sym))
          tp2.instantiate(tparamRefs)

      tp1.paramTypes.zip(subst.paramTypes).forall: (paramType1, paramType2) =>
        recur(paramType2, paramType1)
      && tp1.autoTypes.zip(subst.autoTypes).forall: (autoType1, autoType2) =>
        recur(autoType2, autoType1)
      && recur(tp1.resultType, subst.resultType)
    }
    && tp1.receives.forall(eff => tp2.receives.contains(eff))

  private def checkConformsRecordType(tp1: RecordType, tp2: RecordType)(using Context, Definitions): Boolean =
    val names1 = tp1.fieldNames
    val names2 = tp2.fieldNames
    names1.size >= names2.size && names1.zip(names2).forall: (a, b) =>
      a == b && recur(tp1.fieldType(a), tp2.fieldType(b))

  private def checkConformsUnionType(tp1: UnionType, tp2: UnionType)(using Context, Definitions): Boolean =
    // The ordering of the tags does not matter
    tp1.classes.forall: cls =>
      val classType1 = tp1.classType(cls)
      tp2.hasClass(cls) && {
        val classType2 = tp2.classType(cls)
        recur(classType1, classType2)
      }

  private def checkConformsClassTypeToUnionType(tp1: Type, tp2: UnionType)(using ctx: Context, defn: Definitions): Boolean =
    // Disallow numeric types to subtype to union types
    // This forces boxing via explicit coercion/adaptation
    if tp1.isNumericOrBoolType then
      false
    else
      def check(cls: Symbol): Boolean =
        tp2.getClassType(cls) match
          case Some(classType2) => recur(tp1, classType2)
          case None => false

      tp1 match
        case StaticRef(cls) => check(cls)
        case AppliedType(cls, _) => check(cls)
        case _ => false

  /** Check if tp1 is a class with a direct view that matches tp2
    *
    * If a class C declares `view I`, then C <: I
    */
  private def checkDirectViewSubtyping(tp1: Type, tp2: Type)(using ctx: Context, defn: Definitions): Boolean =
    // Check if any direct view matches tp2
    tp1.directViews.exists: viewType =>
      recur(viewType, tp2)
