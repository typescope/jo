import scala.collection.mutable

import Sast.*
import Types.*
import Symbols.*

import Positions.Span
import Namer.Scope

object NamerUtils:
  /**
    * Perform type checking for an expression.
    *
    * Used to check stack safety and construct function call nodes.
    *
    * Instance of the class should be able to be reused to type check different
    * expression. Therefore, it should not contain any expression-specific state.
    */
  class ExprTyper(namer: Namer, checker: Checker):
    def transform(expr: Ast.Expr)(using  sc: Scope, rp: Reporter): Word =
      val sc2 = sc.fresh()

      val words  = mutable.ListBuffer.empty[Word]
      for word <- expr.words do
        words += namer.transform(word)(using sc2)

      val values = mutable.ArrayBuffer.empty[Word]

      /** A function is automatically called if it's the first element in an
        * expression unless no arguments is available
        */
      def isFunctionCall(tp: FunctionType): Boolean =
        values.isEmpty && words.nonEmpty

      def handleCall(callTree: Word): Unit =
        if callTree.tpe.isError then
          words.clear
          values.clear
          values += callTree
        else if callTree.tpe.isValueType then
          // allow chaining of calls
          words.insert(0, callTree)
        else
          if words.nonEmpty then
            // Mixing non-value words in value context is an error
            Reporter.error("The call does not return a value", callTree.pos)
          else
            values += callTree

      while words.nonEmpty do
        val word = words.remove(0)
        val tp = word.tpe

        // TODO: type inference
        if tp.isPolyType then
          Reporter.error(s"Function ${Printing.show(word)} expects type arguments", word.pos)
          errorTree(word.span)

        else if tp.isProcType then
          val callTree = call(word, tp.asProcType, words, values)
          handleCall(callTree)

        else if word.tpe.isValueType then
          values += word

        else
          if words.nonEmpty then
            // Mixing non-value words in value context is an error
            Reporter.error("The code does not return a value", word.pos)
          else
            values += word
      end while

      // handle function calls, which have lower precedence than procedure calls
      words ++= values
      values.clear()

      while words.nonEmpty do
        val word = words.remove(0)
        val tp = word.tpe

        if tp.isFunctionType && isFunctionCall(tp.asFunctionType) then
          val callTree = call(word, tp.asFunctionType, words, values)
          handleCall(callTree)
        else
          values += word
      end while

      if values.size > 1 then
        Reporter.error("At most one value expected, found = " + values.size, expr.pos)

      val tp = if values.isEmpty then VoidType else values.last.tpe
      Phrase(values.toList)(tp, expr.span)
    end transform

    def call(
      fun: Word, procType: ProcType,
      words: mutable.ListBuffer[Word],
      values: mutable.ArrayBuffer[Word])(
      using Reporter): Word =

      call(fun, procType.preParamTypes, procType.postParamTypes, procType.resultType, words, values)

    def call(
      fun: Word, funType: FunctionType,
      words: mutable.ListBuffer[Word],
      values: mutable.ArrayBuffer[Word])(
      using Reporter): Word =

      call(fun, Nil, funType.paramTypes, funType.resultType, words, values)

    def call(
      fun: Word, preTypes: List[Type], postTypes: List[Type], resType: Type,
      words: mutable.ListBuffer[Word],
      values: mutable.ArrayBuffer[Word])(
      using Reporter): Word =

      if values.size < preTypes.size then
        Reporter.error(
          s"Function ${Printing.show(fun)} expects ${preTypes.size} pre arguments, found = ${values.size}",
          fun.pos)
        errorTree(fun.span)

      else if words.size < postTypes.size then
        Reporter.error(
          s"Function ${Printing.show(fun)} expects ${postTypes.size} post arguments, found = ${words.size}",
          fun.pos)
        errorTree(fun.span)

      else
        val preArgs = values.takeRight(preTypes.size)
        for (arg, paramType) <- preArgs.zip(preTypes) do
          checker.checkType(arg, paramType)

        values.dropRightInPlace(preTypes.size)

        val postArgs = words.take(postTypes.size)
        for (arg, paramType) <- postArgs.zip(postTypes) do
          checker.checkType(arg, paramType)

        words.dropInPlace(postTypes.size)

        var span = preArgs.foldLeft(fun.span)(_ | _.span)
        span = postArgs.foldLeft(span)(_ | _.span)
        Apply(fun, (preArgs ++ postArgs).toList)(resType, span)

    def errorTree(span: Span): Word = Phrase(Nil)(ErrorType, span)
  end ExprTyper

  /** Type provider for fun definitions that might involve cycles
    *
    * Fixed-point computation is performed for soundness.
    *
    * Only self-cycles are allowed.
    */
  final class CyclicTypeProvider(using rp: Reporter) extends InfoProvider:
    /** All pending completers --- never removed */
    private val completers = mutable.Map.empty[Symbol, InfoCompleter.FixedPoint]

    /** The symbols currently in progress of being completed */
    private val completing = new mutable.ArrayBuffer[Symbol]

    /**
      * Add an info provider for the symbol
      *
      * @param initial the initial approximation type for the symbol without computation
      * @param compute compute the type for the symbol
      */
    def addProvider(sym: Symbol, initial: Reporter => Type, compute: Reporter => Type) =
      assert(!completers.contains(sym), "Duplicate provider " + sym)
      completers(sym) = new InfoCompleter.FixedPoint(initial, compute)

    /**
      * We only allow self cycles, so it suffices to compute fixed point for the
      * current info completer.
      */
    def apply(sym: Symbol): Type = Debug.trace(s"Retriving $sym", (_: Type).show, enable = false):
      if !completers.contains(sym) then
        Reporter.abort("No completer for " + sym, sym.sourcePos)

      val completer = completers(sym)

      def iterate(current: Type)(using rp: Reporter): Type = Debug.trace(s"Compute type for $sym", (_: Type).show, enable = false):
        if Subtyping.conforms(current, completer.currentType) then
          // Due to monotonicity, prev <: current, now current <: prev,
          // thus fix-point has reached
          for item <- rp.reports do this.rp.report(item)
          current
        else
          // update cache, run another iteration
          completer.completing(current)
          // throw the old reporter away without reporting any errors
          val reporter = rp.withSource(sym.sourcePos.source).fresh()
          iterate(completer.compute(reporter))(using reporter)
      end iterate

      if completing.contains(sym) && completing.last != sym then
        val cycle = completing.dropWhile(_ != sym).map(_.name).mkString(", ")
        Reporter.error("Mutual recursion needs explicit return type: " + cycle, sym.sourcePos)
        completing.dropRightInPlace(cycle.size)
        val tp = completer.currentType
        completer.complete(tp)
        tp
      else if completing.contains(sym) then
        completer.currentType
      else if completer.isComplete then
        completer.currentType
      else
        completing += sym

        val tp0 = completer.initial(rp)
        completer.completing(tp0)

        // trigger at list one computation
        val reporter = rp.withSource(sym.sourcePos.source).fresh()
        val tp = iterate(completer.compute(reporter))(using reporter)
        completer.complete(tp)

        completing -= sym
        tp

  /** Type provider for value definitions
    *
    * No worries about cycles and no fixed-point computation is performed.
    */
  final class ValueTypeProvider(using rp: Reporter) extends InfoProvider:
    /** All completers --- never removed  */
    private val completers = mutable.Map.empty[Symbol, InfoCompleter.Simple]

    /** The symbols currently in progress of being completed */
    private val completing = new mutable.ArrayBuffer[Symbol]

    /**
      * Add an info provider for the symbol
      *
      * @param initial the initial approximation type for the symbol without computation
      * @param compute compute the type for the symbol
      */
    def addProvider(sym: Symbol, provider: Symbol => Type) =
      assert(!completers.contains(sym), "Duplicate provider " + sym)
      completers(sym) = new InfoCompleter.Simple(provider)

    /**
      * We only allow self cycles, so it suffices to compute fixed point for the
      * current info completer.
      */
    def apply(sym: Symbol): Type = Debug.trace(s"Retriving $sym", (_: Type).show, enable = false):
      if !completers.contains(sym) then
        Reporter.abort("No completer for " + sym, sym.sourcePos)

      val completer = completers(sym)

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

        val tp = completer.compute(sym)
        completer.complete(tp)

        completing -= sym
        tp

  private enum InfoState:
    case Incomplete
    case Completing(current: Type)
    case Completed(cache: Type)

  private enum InfoCompleter:
    case FixedPoint(initial: Reporter => Type, compute: Reporter => Type)
    case Simple(compute: Symbol => Type)

    private var state = InfoState.Incomplete

    def complete(tp: Type): Unit =
      assert(state != InfoState.Completed, "Double completion")
      state = InfoState.Completed(tp)

    def completing(tp: Type): Unit =
      assert(state != InfoState.Completed, "monotonicity violated")
      state = InfoState.Completing(tp)

    def isComplete: Boolean = state.isInstanceOf[InfoState.Completed]

    def currentType: Type =
      state match
        case InfoState.Completing(tp) => tp

        case InfoState.Completed(tp) => tp

        case InfoState.Incomplete =>
           throw new Exception("Unexpected condition")
