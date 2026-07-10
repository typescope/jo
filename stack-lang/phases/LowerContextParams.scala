package phases

import ast.Positions.{ Source, Span }
import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*
import sast.Denotations.*

import scala.collection.mutable

/** Lower context/effect parameter constructs to explicit immutable ctx plumbing.
  *
  * 1. Context access lowering
  *    - Rewrite context reads `Ident(sym)` to `getParam(ctx, paramKey(sym))`.
  *    - Lower `With(expr, bindings)` to immutable ctx extension via
  *      `startBatch`/`addBinding`/`finish`
  *      with lexical scoping (no backup/restore protocol).
  *    - Lazily materialize `emptyCtx()` per function/lambda scope only when ctx
  *      is actually needed.
  *
  * 2. Function call/definition materialization
  *    - For function symbols with `receives.nonEmpty`, append
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
  * Backend/runtime contract required by this phase:
  *
  *   def paramKey[T](id: T): Key[T] = ...
  *   def emptyCtx(): Ctx = ...
  *   def getParam[T](ctx: Ctx, key: Key[T]): T = ...
  *   def startBatch(ctx: Ctx, count: Int): Batch = ...
  *   def addBinding[T](batch: Batch, key: Key[T], value: T): Unit = ...
  *   def finish(batch: Batch): Ctx = ...
  */
class LowerContextParams(ParamSupport: Symbol)(using defn: Definitions)
extends Phase:
  val emptyCtxSym = ParamSupport.termMember("emptyCtx")
  val getParamSym = ParamSupport.termMember("getParam")
  val startBatchSym = ParamSupport.termMember("startBatch")
  val addBindingSym = ParamSupport.termMember("addBinding")
  val finishBatchSym = ParamSupport.termMember("finish")
  val paramKeySym = ParamSupport.termMember("paramKey")

  private val CtxType: Type = emptyCtxSym.tpe.asProcType.resultType
  private val BatchType: Type = startBatchSym.tpe.asProcType.resultType

  private val typeMap = new LowerContextParams.ContextTypeMap(CtxType)

  private val currentCtxSym = new Phase.PhaseKey[Symbol]("currentCtxSym")

  override def initContext()(using Context): Unit =
    defn.index.installTransform: (sym, denot) =>
      denot match
        case info: ClassInfo => info

        case toi: TypeOperatorInfo =>
          val body2 = typeMap(toi.body)(using ())
          if toi.body `eq` body2 then toi
          else TypeOperatorInfo(toi.tparams, body2, toi.preParamCount)

        case procType: ProcType => transformFunDenot(sym, procType)

        case tp: Type => typeMap(tp)(using ())

  private def withCtx[T](ctxOpt: Option[Symbol])(work: => T)(using Context): T =
    val saved: Option[Symbol] = currentCtxSym.getOpt
    ctxOpt match
      case Some(sym) => currentCtxSym.set(sym)
      case None => currentCtxSym.unset()
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

  /** Create a call to paramKey(paramIdent)
    * where paramIdent is an Ident referring to the context parameter symbol.
    */
  private def makeParamKey(paramSym: Symbol, span: Span): Word =
    val paramIdent = Ident(paramSym)(span)
    val tparam = TypeTree(paramSym.tpe)(span)
    val funParamKey = TypeApply(Ident(paramKeySym)(span), tparam :: Nil)(span)
    Apply(funParamKey, paramIdent :: Nil, autos = Nil)(span)

  private def mergedLambdaCtx(
    capturedCtx: Symbol,
    callCtx: Symbol,
    receives: List[Symbol],
    span: Span
  )(using Context): (Symbol, Word) =
    val owner = Phase.owner.value
    val batchSym = TermSymbol.create("__ctxBatch2", BatchType, Flags.Synthetic, Visibility.Default, owner, owner.sourcePos)
    val mergedSym = TermSymbol.create("__ctx2", CtxType, Flags.Synthetic, Visibility.Default, owner, owner.sourcePos)

    val startBatch = Ident(startBatchSym)(span).appliedTo(Ident(capturedCtx)(span), IntLit(receives.size)(span))
    val stmts = new mutable.ArrayBuffer[Word]
    stmts += Assign(Ident(batchSym)(span), startBatch)

    for param <- receives do
      val key = makeParamKey(param, span)
      val tparam = TypeTree(param.tpe)(span)
      val getParamFun = TypeApply(Ident(getParamSym)(span), tparam :: Nil)(span)
      val value = getParamFun.appliedTo(Ident(callCtx)(span), key)
      val addBindingFun = TypeApply(Ident(addBindingSym)(span), tparam :: Nil)(span)
      stmts += addBindingFun.appliedTo(Ident(batchSym)(span), key, value).dropValue

    val mergedExpr = Ident(finishBatchSym)(span).appliedTo(Ident(batchSym)(span))
    stmts += Assign(Ident(mergedSym)(span), mergedExpr)

    (mergedSym, Block(stmts.toList)(span))

  override def transformIdent(word: Ident)(using Context): Word =
    word match
      case Ident(sym) if sym.is(Flags.Context) =>
        val ctx = Ident(ensureCtx(word.span))(word.span)
        val key = makeParamKey(sym, word.span)
        val tparam = TypeTree(sym.tpe)(word.span)
        val getParamFun = TypeApply(Ident(getParamSym)(word.span), tparam :: Nil)(word.span)
        getParamFun.appliedTo(ctx, key)

      case _ =>
        word


  /** Does any of the implemented deferred methods have non-empty receives?
    *
    *  The ViewChecker ensures all implemented deferred methods
    *  either all have non-empty receives or all have empty receives.
    */
  private def implementedDefersReceives(meth: Symbol): Boolean =
    meth.implementedDefers.exists: deferMeth =>
      // Note: the receives spec is not erased during denotation transform
      //
      // However, to avoid cycles, we use previous info
      defn.index.prevInfo(deferMeth).as[ProcType].receives.nonEmpty

  override def transformApply(apply: Apply)(using Context): Word =
    val Apply(fun, args, autos) = apply
    val baseInvokeType = fun.tpe.asInvokableType

    val fun2 = this(fun)
    val args2 = args.map(this(_))
    val autos2 = autos.map(this(_))

    var isDummyCtxParam = false

    val needCtx =
      baseInvokeType match
        case pt: ProcType =>
          // Note: the receives spec is not erased during denotation transform
          if pt.receives.nonEmpty then
            true
          else
            apply.memberSymbol match
              case Some(sym) =>
                isDummyCtxParam = implementedDefersReceives(sym)
                isDummyCtxParam

              case _ => false

        case lt: LambdaType =>
          lt.receives.nonEmpty

    val changed = (fun2 ne fun) || args2.zip(args).exists((a, b) => a ne b) || autos2.zip(autos).exists((a, b) => a ne b)

    if needCtx then
      val ctxArg =
        if isDummyCtxParam then
          Ident(emptyCtxSym)(apply.span).appliedTo()
        else
          Ident(ensureCtx(apply.span))(apply.span)

      Apply(fun2, args2 :+ ctxArg, autos2)(apply.span, apply.isPartialApply)

    else if changed then
      Apply(fun2, args2, autos2)(apply.span, apply.isPartialApply)

    else
      apply

  override def transformTypeApply(tapply: TypeApply)(using Context): Word =
    val TypeApply(fun, targs) = tapply

    val procType = tapply.tpe.asProcType

    var changed =
      procType.receives.nonEmpty || {
        tapply.memberSymbol match
          case Some(meth) => implementedDefersReceives(meth)
          case _ => false
      }

    val fun2 = this(fun)

    changed ||= fun2 ne fun

    val targs2 = targs.map: targ =>
      val tp = targ.tpe
      val tp2 = typeMap(tp)(using ())
      changed ||= tp ne tp2
      TypeTree(tp2)(targ.span)

    if changed then TypeApply(fun2, targs2)(tapply.span) else tapply

  override def transformIf(ifElse: If)(using Context): Word =
    val If(cond, thenp, elsep) = ifElse
    val tp = ifElse.tpe
    val tp2 = typeMap(tp)(using ())
    val cond2 = this(cond)
    val thenp2 = this(thenp)
    val elsep2 = this(elsep)
    if tp2.eq(tp) && cond2.eq(cond) && thenp2.eq(thenp) && elsep2.eq(elsep) then
      ifElse
    else
      If(cond2, thenp2, elsep2)(tp2, ifElse.span)


  private def transformFunDenot(funSym: Symbol, procType: ProcType): Type =
    val params2 =
      val paramsTransformed =
        for param <- procType.params
        yield param.copy(info = this.typeMap(param.info)(using ()))

      if procType.receives.nonEmpty || funSym.isMethod && implementedDefersReceives(funSym) then
        paramsTransformed :+ NamedInfo("__ctx", CtxType)

      else
        paramsTransformed

    val autos2 =
      for auto <- procType.autos
      yield auto.copy(info = this.typeMap(auto.info)(using ()))

    val candidates2 = procType.candidates.map(_ => Nil)

    val resType2 = this.typeMap(procType.resultType)(using ())
    // DefaultValue contains no Types to map; thread defaultsFun through unchanged
    ProcType(
      procType.tparams, params2, autos2, candidates2, resType2, procType.receives,
      procType.preParamCount, procType.preTypeParamCount
    )(procType.defaultsLazy)


  override def transformFunDef(fdef: FunDef)(using Context): FunDef = try
    val sym = fdef.symbol

    val shouldAddCtxParam =
      // Note that the receives spec is not erased during denotation transform.
      sym.info match
        case pt: ProcType =>
          pt.receives.nonEmpty || sym.isMethod && implementedDefersReceives(sym)

        case _ =>
          false

    val maybeCtxParam =
      if shouldAddCtxParam then
        Some(TermSymbol.create("__ctx", CtxType, Flags.Param | Flags.Synthetic, Visibility.Default, sym, sym.sourcePos))

      else
        None

    val params2 = fdef.params ++ maybeCtxParam.toList

    val bodyCore = withOwner(sym):
      withCtx(maybeCtxParam):
        this(fdef.body)

    fdef.copy(params = params2, body = bodyCore)(fdef.annots, fdef.span)
  catch case ex =>
    println(fdef.symbol.tpe.show)
    println(fdef.show)
    throw ex

  override def transformLambda(lam: Lambda)(using Context): Word =
    val Lambda(sym, params, _, body) = lam

    val receives = lam.tpe.asLambdaType.receives

    val capturedCtxOpt =
      given Source = Phase.source.value
      val ambientNeeds = defn.index.effectEngine.effects(body).keySet -- receives.toSet
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

    val seedCtx: Option[Symbol] =
      (capturedCtxOpt, maybeCallCtxParam) match
        case (None, Some(callCtx)) => Some(callCtx)
        case (Some(capturedCtx), None) => Some(capturedCtx)
        case _ => None

    val bodyCore = withOwner(sym):
      withCtx(seedCtx):
        (capturedCtxOpt, maybeCallCtxParam) match
          case (Some(capturedCtx), Some(callCtx)) =>
            val (merged, mergedInit) = mergedLambdaCtx(capturedCtx, callCtx, receives, lam.body.span)
            withCtx(Some(merged)):
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
      val argValueSym = TermSymbol.create("arg_" + paramName, arg.rhs.tpe.widen, Flags.Synthetic, Visibility.Default, owner, owner.sourcePos)
      stats += Assign(Ident(argValueSym)(arg.rhs.span), this(arg.rhs))
      argValueSym

    // 2. Build batch in source order and finish to produce __ctxN
    val batchSym = TermSymbol.create("__ctxBatch", BatchType, Flags.Synthetic, Visibility.Default, owner, owner.sourcePos)
    val startBatch = Ident(startBatchSym)(word.span).appliedTo(outerCtxRef, IntLit(args.size)(word.span))
    stats += Assign(Ident(batchSym)(word.span), startBatch)

    for (arg, argValueSym) <- args.zip(argValueSyms) do
      val key = makeParamKey(arg.symbol, arg.ident.span)
      val value = Ident(argValueSym)(arg.rhs.span)
      val tparam = TypeTree(arg.symbol.tpe)(arg.span)
      val addBindingFun = TypeApply(Ident(addBindingSym)(arg.span), tparam :: Nil)(arg.span)
      stats += addBindingFun.appliedTo(Ident(batchSym)(arg.span), key, value).dropValue

    val ctxSym = TermSymbol.create("__ctx", CtxType, Flags.Synthetic, Visibility.Default, owner, owner.sourcePos)
    val ctxExpr = Ident(finishBatchSym)(expr.span).appliedTo(Ident(batchSym)(expr.span))
    stats += Assign(Ident(ctxSym)(expr.span), ctxExpr)

    // 3. evaluate expr under __ctxN
    val expr2 = withCtx(Some(ctxSym)):
      this(expr)

    stats += expr2

    Block(stats.toList)(word.span)

object LowerContextParams:

  /** Materialize context parameters as additional parameters */
  class ContextTypeMap(CtxType: Type)(using defn: Definitions) extends TypeMap:
    type Context = Unit

    def apply(tp: Type)(using ctx: Context): Type =
      tp match
        case _: RefType => tp

        case lambdaType: LambdaType =>
          val params2 =
            val paramsTransformed = lambdaType.params.map(param => this(param))
            if lambdaType.receives.nonEmpty then
              paramsTransformed :+ CtxType

            else
              paramsTransformed

          val resType2 = this(lambdaType.resultType)
          LambdaType(params2, resType2, receives = lambdaType.receives)

        case procType: ProcType =>
          throw new Exception("Unexpected proc type: " + procType)

        case _ =>
          recur(tp)

      end match
    end apply
  end ContextTypeMap
