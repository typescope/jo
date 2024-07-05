import Sast.*
import Symbols.*
import Types.*
import Reporter.Span

import scala.collection.mutable
import scala.annotation.constructorOnly

/**
  * Check stack safety
  *
  * - Fences can execute with empty value stack.
  * - Value and function definitions work with empty value stack and result in
  *   one value.
  * - The condition of an if-statement works as a fence and result in one value.
  * - Empty stack is never popped.
  */
class Checker(@constructorOnly reporter: Reporter):
  import Checker.ValueStack

  private val delayedChecks = new mutable.ArrayBuffer[() => Unit]

  def delayedCheck(check: => Unit): Unit =
    delayedChecks.addOne(() => check)

  def performDelayedChecks(): Unit =
    for check <- delayedChecks do check()
    delayedChecks.clear()

  /** Handles possible cycles in result type inference  */
  val symbolTypeProvider = new Checker.SymbolTypeProvider(using reporter)

  def check(word: Word)(using vs: ValueStack, rp: Reporter): Unit =
    word match
      case _: IntLit | _: BoolLit | _: RecordLit | _: Select | _: Encoded =>
        vs.push(word.tpe)

      case _: Assign =>
        vs.expectEmpty("No result expected before assignment", word.span)

      case _: ValDef =>
        vs.expectEmpty("No result expected before definition", word.span)

      case _: FunDef =>

      case If(cond, thenp, elsep) =>
        if word.tpe.isValueType then vs.push(word.tpe)

      case While(cond, body) =>
        vs.expectEmpty("No result expected before while loop", word.span)

      case Ident(sym) =>
        // The type of the symbol can be different after type erasure
        val info = word.tpe

        if info.isPolyType then
          Reporter.error(s"Function $sym expects type arguments", word.pos)

        if info.isProcType then
          vs.call(sym, info.asProcType, word.span)

        else if info.isValueType then
          vs.push(info)

      case FunRef(sym) =>
        vs.push(word.tpe)

      case Phrase(words) =>
        check(words)

  def check(words: List[Word])(using ValueStack, Reporter): Unit =
    for word <- words do check(word)

  def checkBounds(tctor: TypeTree, targs: List[TypeTree])(using Reporter): Unit =
    if !tctor.tpe.isTypeLambda then
      Reporter.error(s"Expect type lambda, found = ${tctor.tpe.show}", tctor.pos)
    else
      val tl = tctor.tpe.asTypeLambda
      checkBounds(tl.bounds, targs)

  def checkBounds(bounds: List[Type], targs: List[TypeTree])(using Reporter): Unit =
    if bounds.size != targs.size then
      Reporter.error(s"Expect ${bounds.size} args, found = ${targs.size}", (targs.head.span | targs.last.span).toPos)
    else
      for (targ, bound) <- targs.zip(bounds) do
        val argType = targ.tpe
        val TypeBound(lo, hi) = bound.as[TypeBound]
        val loActual = TypeOps.substTypeParams(lo, targs.map(_.tpe))
        val hiActual = TypeOps.substTypeParams(hi, targs.map(_.tpe))
        if !Subtyping.conforms(argType, hiActual) then
          Reporter.error(s"Arg type ${argType.show} does not conform to bound = ${hi.show}, which expands to ${hiActual.show}", targ.pos)
        if !Subtyping.conforms(loActual, argType) then
          Reporter.error(s"Arg type ${argType.show} does not conform to bound = ${hi.show}, which expands to ${hiActual.show}", targ.pos)

  def checkTypeApply(fun: Word, targs: List[TypeTree])(using Reporter): Word =
    if !fun.tpe.isPolyType then
      Reporter.error(s"Expect a poly function type, found = ${fun.tpe.show}", fun.pos)
      Phrase(words = Nil)(ErrorType, fun.span | targs.last.span)
    else
      val polyType = fun.tpe.asPolyType
      if polyType.paramCount != targs.size then
        Reporter.error(s"Expect ${polyType.paramCount} args, found = ${targs.size}", (targs.head.span | targs.last.span).toPos)
        Phrase(words = Nil)(ErrorType, fun.span | targs.last.span)
      else
        checkBounds(polyType.bounds, targs)
        val tpe = TypeOps.substTypeParams(polyType.resultType, targs.map(_.tpe))
        // TODO: generalize
        val funSym = fun.asInstanceOf[Ident].symbol
        // perform type erasure
        Ident(funSym)(fun.span, tpe)

  def checkType(tree: Tree, tp: Type)(using Reporter): Unit =
    if !Subtyping.conforms(tree.tpe, tp) then
      Reporter.error(s"Expect type ${tp.show}, found = ${tree.tpe.show}", tree.pos)

  def checkValueType(tree: Tree)(using Reporter): Unit =
    checkValueType(tree.tpe, tree.span)

  def checkValueType(tp: Type, span: Span)(using Reporter): Type =
    if !tp.isValueType then
      Reporter.error(s"Expect value type, found = ${tp.show}", span.toPos)
      ErrorType
    else
      tp

  def checkVoidOrValueType(tree: Tree)(using Reporter): Unit =
    if !tree.tpe.isVoid then checkValueType(tree)

  def fieldType(qualType: Type, field: String, span: Span)(using Reporter): Type =
    if !qualType.isRecordType then
      Reporter.error(s"Expect record type, found = ${qualType.show}", span.toPos)
      ErrorType
    else
      val recordType = qualType.asRecordType
      if !recordType.hasField(field) then
        Reporter.error(s"Expect field $field in record type ${recordType.show}, found none", span.toPos)
        ErrorType
      else
        recordType.fieldType(field)

  def commonResultType(tp1: Type, tp2: Type, span: Span)(using Reporter): Type =
    val commonTypeOpt = TypeOps.commonResultType(tp1, tp2)
    commonTypeOpt match
      case Some(tp) => tp
      case None =>
        Reporter.error(s"Cannot find common result type, tp1 = ${tp1.show}, tp2 = ${tp2.show}", span.toPos)
        ErrorType

  def checkTagValues(values: List[Word], tagTypes: List[Type], tagSpan: Span)(using Reporter): Unit =
    if tagTypes.size != values.size then
      Reporter.error(s"Expect ${tagTypes.size} args, found = ${values.size}", tagSpan.toPos)
    else
      for (value, tagType) <- values.zip(tagTypes) do
        checkType(value, tagType)

  def tagTypes(tag: Ast.Ident, unionType: Type, typeSpan: Span)(using Reporter): Option[List[Type]] =
    if !unionType.isUnionType then
      Reporter.error(s"Expect union type, found = ${unionType.show}", typeSpan.toPos)
      None
    else
      val unionType2 = unionType.asUnionType
      if !unionType2.hasTag(tag.name) then
        Reporter.error(s"The tag ${tag.name} does not exist in union type $unionType2", tag.pos)
        None
      else
        Some(unionType2.tagType(tag.name))

  /** Explicit drop of values in if/match expressions */
  def adapt(word: Word, targetType: Type): Word =
    val curType = word.tpe
    if targetType.isVoid && curType.isValueType then
      Encoded(word)(VoidType)
    else
      word

object Checker:
  /**
    * Represent the types of values on the value stack.
    *
    * Used to check stack safety.
    */
  class ValueStack:
    /** Don't expose size in order to handle errors */
    private val valueTypes = mutable.ArrayBuffer.empty[Type]
    private var hasError = false

    private def setError() =
      hasError = true

    def isError = hasError

    def size: Int = valueTypes.size

    def expectEmpty(msg: String, span: Span)(using Reporter): Unit =
      if !isError && this.size != 0 then
        Reporter.error(s"$msg, found = $size", span.toPos)
        setError()

    def call(fun: Symbol, tp: ProcType, span: Span)(using Reporter): Unit =
      if isError then return

      val ProcType(names, paramTypes, resType) = tp

      if this.size < paramTypes.size then
        Reporter.error(
          s"Function $fun expects ${paramTypes.size} arguments, found = $size",
          span.toPos)
        setError()
      else
        val argTypes = valueTypes.takeRight(paramTypes.size)
        val agree =
          argTypes.zip(paramTypes).forall: (tp1, tp2) =>
            Subtyping.conforms(tp1, tp2)

        if !agree then
          val expect = paramTypes.map(_.show).mkString("(", ", ", ")")
          val actual = argTypes.map(_.show).mkString("(", ", ", ")")
          Reporter.error(
            s"Function $fun expects arguments $expect, found = $actual",
            span.toPos)
          setError()
        end if

        valueTypes.dropRightInPlace(paramTypes.size)

        if resType.isValueType then
          push(resType)

    def push(tp: Type): Unit =
      assert(tp.isValueType, tp)
      valueTypes += tp
      if tp.isError then setError()

    def push(tps: List[Type]): Unit = for tp <- tps do push(tp)

    def pop(): Option[Type] =
      if valueTypes.isEmpty then
        None
      else if isError then
        Some(ErrorType)
      else
        val tp = valueTypes.remove(this.size - 1)
        Some(tp)
    end pop
  end ValueStack


  final class SymbolTypeProvider(using Reporter) extends InfoProvider:
    /** All pending completers --- removed after completion */
    private val completers = mutable.Map.empty[Symbol, InfoCompleter]

    /** The symbols currently in progress of being completed */
    private val completing = new mutable.ArrayBuffer[Symbol]

    /**
      * Add an info provider for the symbol
      *
      * @param initial the initial approximation type for the symbol without computation
      * @param compute compute the type for the symbol
      */
    def addProvider(sym: Symbol, initial: () => Type, compute: () => Type) =
      assert(!completers.contains(sym), "Duplicate provider " + sym)
      completers(sym) = InfoCompleter(initial, compute)

    /**
      * We only allow self cycles, so it suffices to compute fixed point for the
      * current info completer.
      */
    def apply(sym: Symbol): Type = Debug.trace(s"Retriving $sym", (_: Type).show, enable = false):
      if !completers.contains(sym) then
        Reporter.abort("No completer for " + sym, sym.sourcePos)

      val completer = completers(sym)

      def iterate(current: Type): Type = Debug.trace(s"Compute type for $sym", (_: Type).show, enable = false):
        if Subtyping.conforms(current, completer.currentType) then
          // Due to monotonicity, prev <: current, now current <: prev,
          // thus fix-point has reached
          current
        else
          // update cache, run another iteration
          completer.completing(current)
          iterate(completer.compute())
      end iterate

      if completing.contains(sym) && completing.last != sym then
        val cycle = completing.dropWhile(_ != sym).map(_.name).mkString(", ")
        Reporter.error("Mutual recursion needs explicit return type: " + cycle, sym.sourcePos)
        ErrorType
      else if completing.contains(sym) then
        completer.currentType
      else if completer.isComplete then
        completer.currentType
      else
        completing += sym

        val tp0 = completer.initial()
        completer.completing(tp0)
        val tp = iterate(completer.compute()) // trigger at list one computation
        completer.complete(tp)

        completing -= sym
        tp

  private enum InfoState:
    case Incomplete
    case Completing(current: Type)
    case Completed(cache: Type)

  private class InfoCompleter(val initial: () => Type, val compute: () => Type):
    private var state = InfoState.Incomplete

    def complete(tp: Type): Unit =
      assert(state != InfoState.Completed, "Double completion")
      state = InfoState.Completed(tp)

    def completing(tp: Type): Unit =
      assert(state != InfoState.Completed, "monotonicity violated")
      state = InfoState.Completing(tp)

    def isComplete: Boolean = state.isInstanceOf[InfoState.Completed]

    def currentState: InfoState = state

    def currentType: Type =
      currentState match
        case InfoState.Completing(tp) => tp

        case InfoState.Completed(tp) => tp

        case InfoState.Incomplete =>
           throw new Exception("Unexpected condition")
