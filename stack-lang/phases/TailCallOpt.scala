package phases

import ast.Positions.*

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import scala.collection.mutable

/** Tail Call Optimization phase
  *
  * Recognizes tail-recursive calls and rewrites them to while loops.
  */
class TailCallOpt(using defn: Definitions) extends Phase:

  override def transformFunDef(fdef: FunDef)(using Context): FunDef =
    val sym = fdef.symbol

    // Skip abstract/deferred functions
    if sym.is(Flags.Defer) then return fdef

    Phase.owner.set(sym)

    // First recurse into the body so nested local functions are optimized too.
    val fdef1 = super.transformFunDef(fdef)

    optimizeTailrec(fdef1)

  // -------------------------------------------------------------------------
  // Transformation
  // -------------------------------------------------------------------------

  /** Optimize self tail calls if they are compatible with loop-slot rewriting.
    * Returns the original function unchanged when no compatible tail calls exist.
    */
  private def optimizeTailrec(fdef: FunDef)(using ctx: Context): FunDef =
    val funSym = fdef.symbol
    val span = fdef.span
    val owner = funSym
    val resultType = funSym.info.asProcType.resultType

    given source: Source = Phase.source.value

    val selfSymOpt =
      if funSym.is(Flags.Method) then
        Some(funSym.owner.classInfo.self)
      else
        None

    // Create mutable copies of all parameters (this + regular + auto)
    val allParams = selfSymOpt.toList ++ fdef.allParams
    val loopArgTypes = allParams.map(_.info)

    // Only optimize when there is at least one compatible self tail call in tail
    // position. Incompatible tail calls are left as regular calls.
    if !TailCallOpt.hasCompatibleTailCallsInTailPos(fdef.body, funSym, loopArgTypes) then
      return fdef

    val paramCopyMap: Map[Symbol, Symbol] = allParams.map: param =>
      val copy = TermSymbol.create(
        "_" + param.name,
        param.info,
        Flags.Mutable | Flags.Synthetic,
        Visibility.Default,
        owner,
        param.sourcePos
      )
      param -> copy
    .toMap

    // Create mutable copies of context (receive) parameters
    val receives: List[Symbol] = funSym.info.asProcType.receives
    val receiveCopyMap: Map[Symbol, Symbol] = receives.map: recv =>
      val copy = TermSymbol.create(
        "_" + recv.name,
        recv.info,
        Flags.Mutable | Flags.Synthetic,
        Visibility.Default,
        owner,
        span.toPos
      )
      recv -> copy
    .toMap
    // Create _tco_continue : Bool (mutable loop flag)
    val continueSym = TermSymbol.create(
      "_tco_continue",
      defn.BoolType,
      Flags.Mutable | Flags.Synthetic,
      Visibility.Default,
      owner,
      span.toPos
    )
    val continueIdent = Ident(continueSym)(span)

    // Create _tco_result : T (mutable result accumulator)
    val resultSym = TermSymbol.create(
      "_tco_result",
      resultType,
      Flags.Mutable | Flags.Synthetic,
      Visibility.Default,
      owner,
      span.toPos
    )
    val resultIdent = Ident(resultSym)(span)

    val tcoLabel = TermSymbol.create(
      "_tco_loop",
      VoidType,
      Flags.Label | Flags.Synthetic,
      Visibility.Default,
      owner,
      span.toPos
    )

    // paramCopiesInOrder mirrors extractAllArgs: self copy first (if method), then param copies.
    val paramCopiesInOrder: List[Symbol] = allParams.map(paramCopyMap)
    val paramSnapshotMap: Map[Symbol, Symbol] = allParams.map: param =>
      val snapshot = TermSymbol.create(
        "_tco_cap_" + param.name,
        param.info,
        Flags.Synthetic,
        Visibility.Default,
        owner,
        param.sourcePos
      )
      param -> snapshot
    .toMap
    var needsResultAccumulator = false
    val transformedBody = TailCallOpt.replaceTailCalls(
      fdef.body, funSym, loopArgTypes, paramCopiesInOrder, receiveCopyMap, continueSym, resultSym, tcoLabel,
      TailCallOpt.SubstTransform(paramSnapshotMap),
      () => needsResultAccumulator = true
    )

    // Build the initialization block before the while loop
    val initStmts = mutable.ArrayBuffer[Word]()

    // var _<recv> = <recv> for each context parameter
    for recv <- receives do
      val copy = receiveCopyMap(recv)
      initStmts += Assign(Ident(copy)(span), Ident(recv)(span))

    // var _<param> = <param> for each parameter and `this`
    for param <- allParams do
      val copy = paramCopyMap(param)
      initStmts += Assign(Ident(copy)(param.span), Ident(param)(param.span))

    // var _tco_continue = true
    initStmts += Assign(continueIdent, BoolLit(true)(span))

    // Build: while _tco_continue do
    //          _tco_continue = false
    //          body with p1 = _p1, p2 = _p2   (rebind context params each iteration)
    val continueReset = Assign(continueIdent, BoolLit(false)(span))
    val snapshotInits =
      allParams.map: param =>
        val snap = paramSnapshotMap(param)
        val copy = paramCopyMap(param)
        Assign(Ident(snap)(span), Ident(copy)(span), isDefine = true)
    val bodyInLoop =
      if receiveCopyMap.isEmpty then transformedBody
      else
        val withArgs = receives.map: recv =>
          val copy = receiveCopyMap(recv)
          Assign(Ident(recv)(span), Ident(copy)(span))
        With(transformedBody, withArgs)
    val whileBodyInner = Block((continueReset :: snapshotInits) :+ bodyInLoop)(span)
    val whileBody = Labeled(tcoLabel, VoidType, whileBodyInner)(span)
    val whileLoop = While(continueIdent, whileBody)(span)

    // Final body: only append result accumulator when some tail path falls through
    // with a value (instead of directly returning from the function).
    val finalWords =
      if needsResultAccumulator then
        initStmts.toList :+ whileLoop :+ resultIdent
      else
        val abortFun = Ident(defn.abort)(span)
        val abortArg = StringLit("Unreachable path in tailrec optimization")(span)
        val unreachable = abortFun.appliedTo(abortArg).dropIfVoid(resultType)
        initStmts.toList :+ whileLoop :+ unreachable
    val newBody = Block(finalWords)(span)

    fdef.copy(body = newBody)(span)

object TailCallOpt:

  /** Check if `word` (in tail position) contains any tail call to `sym` that can be
    * rewritten with the current loop-state slot types.
    */
  private def hasCompatibleTailCallsInTailPos(word: Word, sym: Symbol, loopArgTypes: List[Type])(using Definitions): Boolean =
    word match
      case apply: Apply =>
        isCompatibleTailCall(apply, sym, loopArgTypes)

      case If(_, thenp, elsep) =>
        hasCompatibleTailCallsInTailPos(thenp, sym, loopArgTypes) || hasCompatibleTailCallsInTailPos(elsep, sym, loopArgTypes)

      case Block(words) => words.nonEmpty && hasCompatibleTailCallsInTailPos(words.last, sym, loopArgTypes)

      case With(expr, _) => hasCompatibleTailCallsInTailPos(expr, sym, loopArgTypes)

      case Allow(expr, _) => hasCompatibleTailCallsInTailPos(expr, sym, loopArgTypes)

      case Return(_, value) => hasCompatibleTailCallsInTailPos(value, sym, loopArgTypes)

      case _: Match => throw new Exception("Unexpect tree: " + word)

      case _ => false

  /** Check if `apply` is a tail call to `sym` */
  private def isTailCallTo(apply: Apply, sym: Symbol): Boolean =
    def checkFun(fun: Word): Boolean =
      fun match
        case _: Select =>
          fun.tpe match
            case MemberRef(_, calledSym) => calledSym == sym
            case _ => false
        case TypeApply(inner, _) => checkFun(inner)
        case Ident(calledSym) => calledSym == sym
        case _ => false

    checkFun(apply.fun)

  /** Extract the receiver expression from a method call's fun node (if any) */
  private def extractReceiver(fun: Word): Option[Word] =
    fun match
      case Select(recv, _) => Some(recv)
      case TypeApply(inner, _) => extractReceiver(inner)
      case _ => None

  /** Receiver (if any) followed by explicit args and auto args as a flat list */
  private def extractAllArgs(apply: Apply): List[Word] =
    extractReceiver(apply.fun).toList ++ apply.args ++ apply.autos

  /** A self-tail call is TCO-compatible iff:
   * 1) it targets the same function,
    * 2) each recursive argument type conforms to the corresponding loop slot type.
    */
  private def isCompatibleTailCall(apply: Apply, sym: Symbol, loopArgTypes: List[Type])(using Definitions): Boolean =
    if !isTailCallTo(apply, sym) then false
    else
      val args = extractAllArgs(apply)
      args.size == loopArgTypes.size &&
      args.zip(loopArgTypes).forall: (arg, targetType) =>
        Subtyping.conforms(arg.tpe, targetType)

  /** Rewrite param/self references to the per-iteration snapshot values. */
  private class SubstTransform(substMap: Map[Symbol, Symbol])(using Definitions) extends TreeMap:
    type Context = Definitions

    override def transformIdent(ident: Ident)(using Context): Word =
      substMap.get(ident.symbol).fold(ident)(copy => Ident(copy)(ident.span))

  /** Replace self-tail calls while applying `subst` throughout.
    *
    * Recurses on the structural spine (tail positions); applies `subst`
    * everywhere else (non-tail positions and the fallback case).
    *
    * Returns a word of Void type by assigning final value to result.
    */
  private def replaceTailCalls(
    word: Word,
    funSym: Symbol,
    loopArgTypes: List[Type],
    allCopiesInOrder: List[Symbol],
    receiveCopyMap: Map[Symbol, Symbol],
    continueSym: Symbol,
    resultSym: Symbol,
    tcoLabel: Symbol,
    subst: SubstTransform,
    markNeedsResultAccumulator: () => Unit
  )(using Definitions): Word =

    def go(word: Word): Word =
      replaceTailCalls(
        word, funSym, loopArgTypes, allCopiesInOrder, receiveCopyMap, continueSym, resultSym, tcoLabel, subst,
        markNeedsResultAccumulator
      )

    word match

      case apply: Apply if isCompatibleTailCall(apply, funSym, loopArgTypes) =>
        val span = apply.span
        val updates = mutable.ArrayBuffer[Word]()

        val newAllArgs = extractAllArgs(apply).map(subst(_))

        // Arguments are rewritten to per-iteration snapshots, so assigning loop
        // slots left-to-right cannot affect later argument computations.
        for (copy, newVal) <- allCopiesInOrder.zip(newAllArgs) do
          updates += Assign(Ident(copy)(span), newVal)

        for (recv, copy) <- receiveCopyMap do
          updates += Assign(Ident(copy)(span), Ident(recv)(span))

        updates += Assign(Ident(continueSym)(span), BoolLit(true)(span))
        updates += Return(tcoLabel, Block(Nil)(span))(span).dropValue
        Block(updates.toList)(span)

      case If(cond, thenp, elsep) =>
        If(subst(cond), go(thenp), go(elsep))(VoidType, word.span)

      case Block(words) =>
        assert(!words.isEmpty, "Unexpected empty block")
        Block(words.init.map(subst(_)) :+ go(words.last))(word.span)

      case With(expr, args) =>
        With(go(expr), args.map(arg => arg.copy(rhs = subst(arg.rhs))))

      case Allow(expr, _) =>
        go(expr)

      case Return(label, value) if hasCompatibleTailCallsInTailPos(value, funSym, loopArgTypes) =>
        go(value)

      case ret: Return =>
        Return(ret.label, subst(ret.value))(ret.span).dropValue

      case _: Match => throw new Exception("Unexpect tree: " + word)

      case _ =>
        markNeedsResultAccumulator()
        Assign(Ident(resultSym)(word.span), subst(word))
