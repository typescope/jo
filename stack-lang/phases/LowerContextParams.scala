package phases

import ast.Positions.{ Source, Span }
import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import scala.collection.mutable

/** This phase translate context parameters to runtime calls
  *
  * This phase is generic and can be used for all platforms, as long as the
  * following support functions are provided:
  *
  *     def paramKey[T](id: T): Key[T] = ...
  *     def emptyCtx(): Ctx = ...
  *     def getParam[T](ctx: Ctx, key: Key[T]): T = ...
  *     def bindParam[T](ctx: Ctx, key: Key[T], value: T): Ctx = ...
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
    defn.installTransform: (sym, tp) =>
      tp match
        case procType: ProcType if sym.isFunction && procType.receives.nonEmpty =>
          procType.append(NamedInfo("__ctx", CtxType) :: Nil)
        case _ =>
          tp

  private def withCurrentCtx[T](ctxSym: Symbol)(work: => T)(using ctx: Context): T =
    val previous = currentCtxSym.getOpt
    currentCtxSym.set(ctxSym)
    try work
    finally
      previous match
        case Some(sym) => currentCtxSym.set(sym)
        case None      => currentCtxSym.unset()

  private def currentCtx(using Context): Symbol =
    currentCtxSym.value

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

  /** Create a call to paramKey(paramIdent)
    * where paramIdent is an Ident referring to the context parameter symbol
    */
  private def makeParamSymbol(paramSym: Symbol, span: Span): Word =
    val paramIdent = Ident(paramSym)(span)
    val tparam = TypeTree(paramSym.info)(span)
    val funParamKey = TypeApply(Ident(paramKeySym)(span), tparam :: Nil)(span)
    funParamKey.appliedTo(paramIdent)

  override def transformIdent(word: Ident)(using Context): Word =
    word match
      case Ident(sym) if sym.isAllOf(Flags.Context) =>
        val ctx = Ident(currentCtx)(word.span)
        val key = makeParamSymbol(sym, word.span)
        val tparam = TypeTree(sym.info)(word.span)
        val getParamFun = TypeApply(Ident(getParamSym)(word.span), tparam :: Nil)(word.span)
        val getParamCall = Encoded(getParamFun.appliedTo(ctx, key))(word.tpe)
        getParamCall

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
      assert(currentCtxSym.getOpt.nonEmpty, "Missing current context for call requiring receives: " + apply.show)
      val ctxArg = Ident(currentCtx)(apply.span)
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

  override def transformFunDef(fdef: FunDef)(using Context): FunDef = try
    val sym = fdef.symbol
    val span = fdef.span
    val funOwner = sym

    val maybeCtxParam =
      if shouldAddCtxParam(sym) then
        Some(TermSymbol.create("__ctx", CtxType, Flags.Param | Flags.Synthetic, Visibility.Default, sym, sym.sourcePos))
      else
        None

    val params2 = fdef.params ++ maybeCtxParam.toList

    Phase.owner.set(funOwner)

    val body2 =
      maybeCtxParam match
        case Some(ctxParam) =>
          withCurrentCtx(ctxParam) {
            this(fdef.body)
          }

        case None =>
          this(fdef.body)

    fdef.copy(params = params2, body = body2)(span)
  catch case ex =>
    println(fdef.symbol.info.show)
    println(fdef.show)
    throw ex

  override def transformWith(word: With)(using Context): Word =
    val With(expr, args) = word
    given Source = Phase.source.value

    val stats = new mutable.ArrayBuffer[Word]
    val owner = Phase.owner.value
    val outerCtxRef =
      currentCtxSym.getOpt match
        case Some(sym) =>
          Ident(sym)(word.span)
        case None =>
          Ident(emptyCtxSym)(word.span).appliedTo()

    // 1. args are evaluated with the outer context
    val argValueSyms = args.map: arg =>
      val paramName = arg.ident.symbol.fullName
      val argValueSym = TermSymbol.create("arg_" + paramName, arg.rhs.tpe, Flags.Synthetic, Visibility.Default, owner, pos = arg.rhs.pos)
      stats += Assign(Ident(argValueSym)(arg.rhs.span), this(arg.rhs))
      argValueSym

    // 2. val __ctxN = bindParam(... bindParam(outerCtx, k1, v1) ..., kn, vn)
    val ctxSym = TermSymbol.create("__ctx", CtxType, Flags.Synthetic, Visibility.Default, owner, pos = expr.pos)
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
    val expr2 = withCurrentCtx(ctxSym) {
      this(expr)
    }
    stats += expr2

    Block(stats.toList)(word.span)
