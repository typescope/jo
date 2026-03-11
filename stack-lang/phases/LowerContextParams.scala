package phases

import ast.Positions.{ Source, Span }
import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import scala.collection.mutable

/** Lower context/effect parameter constructs to explicit immutable ctx plumbing.
  *
  * 1. Context access lowering
  *    - Rewrite context reads `Ident(sym)` (`sym.isContext`) to
  *      `getParam(ctx, paramKey(sym))`.
  *    - Lower `With(expr, bindings)` to immutable ctx extension via `bindParam`
  *      with lexical scoping (no backup/restore protocol).
  *    - Lazily materialize `emptyCtx()` per function/lambda scope only when ctx
  *      is actually needed.
  *
  * 2. Function call/definition materialization
  *    - For function symbols with `receives.nonEmpty` (from `prevInfo`), append
  *      hidden trailing `__ctx` parameter and pass ctx at call sites.
  *    - Install a symbol-info transform for function `ProcType` so later phases
  *      observe explicit ctx parameter shape.
  *
  * 3. Lambda lowering (before ElimCapture)
  *    - Remove lambda `receives` by materializing explicit ctx parameter(s).
  *    - Handle ambient ctx capture and capture+receives merge semantics by
  *      building a merged ctx when needed.
  *    - Rebuild lambda/encoded trees so type shape stays coherent.
  *
  * 4. Default/allow normalization
  *    - Lower `allow` by injecting defaults for rejected default effects.
  *    - At effect boundaries (`Effects.Policy.CheckBound`), inject missing
  *      default context bindings into function body.
  *
  * Backend/runtime contract required by this phase:
  *
  *   def paramKey[T](id: T): Key[T] = ...
  *   def emptyCtx(): Ctx = ...
  *   def getParam[T](ctx: Ctx, key: Key[T]): T = ...
  *   def bindParam[T](ctx: Ctx, key: Key[T], value: T): Ctx = ...
  */
class LowerContextParams(
  paramKeySym: Symbol,
  emptyCtxSym: Symbol,
  getParamSym: Symbol,
  bindParamSym: Symbol)
  (using defn: Definitions)
extends Phase:

  private val CtxType: Type = emptyCtxSym.info.asProcType.resultType

  private val currentCtxSym = new Phase.PhaseKey[Symbol]("currentCtxSym")

  override def initContext()(using Context): Unit =
    // Function symbols only. Lambdas are rewritten explicitly in transformLambda.
    defn.installTransform: (sym, tp) =>
      tp match
        case procType: ProcType if sym.isFunction && procType.receives.nonEmpty =>
          procType.append(NamedInfo("__ctx", CtxType) :: Nil)
        case lambdaType: LambdaType if sym.isFunction && lambdaType.receives.nonEmpty =>
          LambdaType(lambdaType.params :+ CtxType, lambdaType.resultType, lambdaType.receives)
        case _ =>
          tp

  private def withCtx[T](ctxOrNull: Symbol | Null)(work: => T)(using Context): T =
    val saved: Option[Symbol] = currentCtxSym.getOpt
    if ctxOrNull == null then currentCtxSym.unset() else currentCtxSym.set(ctxOrNull)
    try work
    finally
      saved match
        case Some(sym) => currentCtxSym.set(sym)
        case None => currentCtxSym.unset()

  private def withOwner[T](owner: Symbol)(work: => T)(using Context): T =
    val previous = Phase.owner.getOpt
    Phase.owner.set(owner)
    try work
    finally
      previous match
        case Some(sym) => Phase.owner.set(sym)
        case None => Phase.owner.unset()

  private def invokeReceives(tp: InvokableType)(using Definitions): List[Symbol] =
    tp match
      case pt: ProcType => pt.receives
      case lt: LambdaType => lt.receives

  private def appendCtxToInvokeType(tp: InvokableType): InvokableType =
    tp match
      case pt: ProcType =>
        pt.append(NamedInfo("__ctx", CtxType) :: Nil)
      case lt: LambdaType =>
        LambdaType(lt.params :+ CtxType, lt.resultType, lt.receives)

  private def shouldAddCtxParam(sym: Symbol): Boolean =
    defn.prevInfo(sym) match
      case pt: ProcType =>
        pt.receives.nonEmpty
      case _ =>
        false

  private def ensureCtx(span: Span)(using Context): Symbol =
    given Source = Phase.source.value
    assert(currentCtxSym.exists, "Missing current context at: " + span.toPos)
    currentCtxSym.value

  private def prependStmts(body: Word, stmts: List[Word], span: Span): Word =
    if stmts.isEmpty then
      body
    else
      body match
        case Block(words) => Block(stmts ++ words)(span)
        case _ => Block(stmts :+ body)(span)

  private def synthesizeDefaultBindings(params: List[Symbol], span: Span): List[Assign] =
    params.map: param =>
      val paramRef = Ident(param)(span)
      val defaultFunSym = param.defaultFunction
      val defaultValue = Ident(defaultFunSym)(span).appliedTo()
      Assign(paramRef, defaultValue)

  private def injectDefaultBindings(fdef: FunDef): Word =
    fdef.effectPolicy match
      case Effects.Policy.CheckBound(params) =>
        val effs = defn.effectEngine.getBodyEffects(fdef.symbol)
        val allowed = params.toSet

        val rejectedDefaults =
          for
            (eff, _) <- effs
            if eff.is(Flags.Default) && !allowed.exists(param => eff == param)
          yield eff

        if rejectedDefaults.isEmpty then fdef.body
        else With(fdef.body, synthesizeDefaultBindings(rejectedDefaults.toList, fdef.body.span))

      case _ =>
        fdef.body

  /** Create a call to paramKey(paramIdent)
    * where paramIdent is an Ident referring to the context parameter symbol.
    */
  private def makeParamSymbol(paramSym: Symbol, span: Span): Word =
    val paramIdent = Ident(paramSym)(span)
    val tparam = TypeTree(paramSym.info)(span)
    val funParamKey = TypeApply(Ident(paramKeySym)(span), tparam :: Nil)(span)
    funParamKey.appliedTo(paramIdent)

  private def mergedLambdaCtx(
    capturedCtx: Symbol,
    callCtx: Symbol,
    receives: List[Symbol],
    span: Span
  )(using Context): (Symbol, Assign) =
    val owner = Phase.owner.value
    val mergedSym = TermSymbol.create("__ctx2", CtxType, Flags.Synthetic, Visibility.Default, owner, owner.sourcePos)

    val mergedExpr =
      receives.foldLeft[Word](Ident(capturedCtx)(span)):
        case (acc, param) =>
          val key = makeParamSymbol(param, span)
          val tparam = TypeTree(param.info)(span)

          val getParamFun = TypeApply(Ident(getParamSym)(span), tparam :: Nil)(span)
          val value = getParamFun.appliedTo(Ident(callCtx)(span), key)

          val bindParamFun = TypeApply(Ident(bindParamSym)(span), tparam :: Nil)(span)
          bindParamFun.appliedTo(acc, key, value)

    (mergedSym, Assign(Ident(mergedSym)(span), mergedExpr))

  override def transformIdent(word: Ident)(using Context): Word =
    word match
      case Ident(sym) if sym.isAllOf(Flags.Context) =>
        val ctx = Ident(ensureCtx(word.span))(word.span)
        val key = makeParamSymbol(sym, word.span)
        val tparam = TypeTree(sym.info)(word.span)
        val getParamFun = TypeApply(Ident(getParamSym)(word.span), tparam :: Nil)(word.span)
        Encoded(getParamFun.appliedTo(ctx, key))(word.tpe)

      case Ident(sym) if sym.isFunction && shouldAddCtxParam(sym) =>
        // Rebuild function identifiers whose type changed via installTransform,
        // so enclosing TypeApply/Apply nodes can refresh their cached tpe.
        Ident(sym)(word.span)

      case _ =>
        word

  override def transformApply(apply: Apply)(using Context): Word =
    val Apply(fun, args, autos) = apply
    val baseInvokeType = fun.tpe.asInvokableType

    val fun2 = this(fun)
    val args2 = args.map(this(_))
    val autos2 = autos.map(this(_))
    val currInvokeType = fun2.tpe.asInvokableType

    val needCtx = invokeReceives(baseInvokeType).nonEmpty

    val changed = (fun2 ne fun) || args2.zip(args).exists((a, b) => a ne b) || autos2.zip(autos).exists((a, b) => a ne b)

    if needCtx then
      val ctxArg = Ident(ensureCtx(apply.span))(apply.span)
      val fun3 =
        if currInvokeType.paramTypes.size == args2.size + 1 then
          fun2
        else
          Encoded(fun2)(appendCtxToInvokeType(currInvokeType))

      Apply(fun3, args2 :+ ctxArg, autos2)(apply.span, apply.isPartialApply)
    else if changed then
      Apply(fun2, args2, autos2)(apply.span, apply.isPartialApply)
    else
      apply

  override def transformAllow(allowExpr: Allow)(using Context): Word =
    val expr2 = this(allowExpr.expr)

    given Source = Phase.source.value
    val effsInner = defn.effectEngine.effects(allowExpr.expr)
    val allowed = allowExpr.params.map(_.symbol).toSet

    val unprovided = effsInner.filter((k, _) => !allowed.exists(param => k == param))
    val rejectedDefaults = unprovided.keys.filter(_.is(Flags.Default)).toList

    if rejectedDefaults.isEmpty then
      expr2
    else
      this(With(expr2, synthesizeDefaultBindings(rejectedDefaults, allowExpr.span)))

  override def transformFunDef(fdef: FunDef)(using Context): FunDef = try
    val sym = fdef.symbol

    val maybeCtxParam =
      if shouldAddCtxParam(sym) then
        Some(TermSymbol.create("__ctx", CtxType, Flags.Param | Flags.Synthetic, Visibility.Default, sym, sym.sourcePos))
      else
        None

    val params2 = fdef.params ++ maybeCtxParam.toList
    val body0 = injectDefaultBindings(fdef)

    val bodyCore = withOwner(sym):
      withCtx(maybeCtxParam.getOrElse(null))
        this(body0)

    fdef.copy(params = params2, body = bodyCore)(fdef.span)
  catch case ex =>
    println(fdef.symbol.info.show)
    println(fdef.show)
    throw ex

  override def transformLambda(lam: Lambda)(using Context): Word =
    val Lambda(sym, params, _, body) = lam

    val receives = lam.tpe.asLambdaType.receives

    val capturedCtxOpt =
      given Source = Phase.source.value
      val ambientNeeds = defn.effectEngine.effects(body).keySet -- receives.toSet
      if ambientNeeds.nonEmpty then
        assert(currentCtxSym.exists, "Missing ambient context for captured lambda: " + lam.show)
        currentCtxSym.getOpt
      else
        None

    val maybeCallCtxParam =
      if receives.nonEmpty then
        Some(TermSymbol.create("__ctx1", CtxType, Flags.Param | Flags.Synthetic, Visibility.Default, sym, sym.sourcePos))
      else
        None

    val params2 = params ++ maybeCallCtxParam.toList

    val seedCtxOrNull: Symbol | Null =
      (capturedCtxOpt, maybeCallCtxParam) match
        case (None, Some(callCtx)) => callCtx
        case (Some(capturedCtx), None) => capturedCtx
        case _ => null

    val bodyCore = withOwner(sym):
      withCtx(seedCtxOrNull):
        (capturedCtxOpt, maybeCallCtxParam) match
          case (Some(capturedCtx), Some(callCtx)) =>
            val (merged, mergedInit) = mergedLambdaCtx(capturedCtx, callCtx, receives, lam.body.span)
            withCtx(merged):
              prependStmts(this(body), mergedInit :: Nil, lam.body.span)

          case _ =>
            this(body)

    Lambda(sym, params2, Nil, bodyCore)(lam.span)

  override def transformWith(word: With)(using Context): Word =
    val With(expr, args) = word

    val stats = new mutable.ArrayBuffer[Word]
    val owner = Phase.owner.value
    val outerCtxSym =
      currentCtxSym.getOpt match
        case Some(sym) =>
          sym
        case None =>
          val sym = TermSymbol.create("__ctx0", CtxType, Flags.Synthetic, Visibility.Default, owner, owner.sourcePos)
          stats += Assign(Ident(sym)(word.span), Ident(emptyCtxSym)(word.span).appliedTo())
          sym
    val outerCtxRef = Ident(outerCtxSym)(word.span)

    // 1. args are evaluated with the outer context (in source order)
    val argValueSyms = args.map: arg =>
      val paramName = arg.ident.symbol.fullName
      val argValueSym = TermSymbol.create("arg_" + paramName, arg.rhs.tpe, Flags.Synthetic, Visibility.Default, owner, owner.sourcePos)
      stats += Assign(Ident(argValueSym)(arg.rhs.span), this(arg.rhs))
      argValueSym

    // 2. val __ctxN = bindParam(... bindParam(outerCtx, k1, v1) ..., kn, vn)
    val ctxSym = TermSymbol.create("__ctx", CtxType, Flags.Synthetic, Visibility.Default, owner, owner.sourcePos)
    val ctxExpr =
      args.zip(argValueSyms).foldLeft[Word](outerCtxRef):
        case (ctxAcc, (arg, argValueSym)) =>
          val key = makeParamSymbol(arg.symbol, arg.ident.span)
          val value = Ident(argValueSym)(arg.rhs.span)
          val tparam = TypeTree(arg.symbol.info)(arg.span)
          val bindParamFun = TypeApply(Ident(bindParamSym)(arg.span), tparam :: Nil)(arg.span)
          bindParamFun.appliedTo(ctxAcc, key, value)

    stats += Assign(Ident(ctxSym)(expr.span), ctxExpr)

    // 3. evaluate expr under __ctxN
    val expr2 = withCtx(ctxSym):
      this(expr)

    stats += expr2

    Block(stats.toList)(word.span)
