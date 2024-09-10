import scala.collection.mutable

import Sast.*
import Types.*
import Symbols.*

import Positions.Span
import Namer.Scope
import Inference.*

object ExprTyper:
  /** The precedence of a function word
    *
    * We fix the precedence of names in the compiler according to established
    * convention in mathematics and programming languages.
    *
    * We make `and/or/not` the same precedence to make it simpler for
    * programmers.
    *
    * We disallow setting precedence of other function names (no matter it is
    * user-defined or primitive) for usability: programmers should not learn and
    * remember precedence beyond the basic ones. When in doubt, parentheses can
    * be used.
    */
  def precedence(word: Word): Int =
    assert(word.tpe.isProcType || word.tpe.isFunctionType || word.tpe.isPolyType, word)
    word match
      case Ident(sym)        => precedence(sym.name)
      case TypeApply(fun, _) => precedence(fun)
      case _                 => 100

  // TODO: ~  ->
  def precedence(fun: String): Int =
    fun match
      case "and" | "or" | "not"                     => 10
      case ">"   | "<"  | ">=" | "<=" | "==" | "!=" => 20
      case "+"   | "-"                              => 30
      case "<<"  | ">>" | "|"  | "&"  | "^"         => 40
      case "*"   | "/"  | "%"                       => 50
      case _                                        => 100

/**
  * Perform type checking for an expression.
  *
  * Used to check stack safety and construct function call nodes.
  *
  * Instance of the class should be able to be reused to type check different
  * expression. Therefore, it should not contain any expression-specific state.
  */
class ExprTyper(namer: Namer, checker: Checker):

  /** Parse a word from the words with the limit precedence */
  private def parse(words: mutable.ListBuffer[Word], precLimit: Int)(using rp: Reporter): Word =
    assert(words.nonEmpty, "input words are empty")

    // println("Parsing " + words + ", precedence = " + precedence)

    val values = mutable.ArrayBuffer.empty[Word]
    var continue = true

    def handleCall(callTree: Word): Unit =
      if callTree.tpe.isError then
        words.clear
        values.clear
        values += callTree
      else
        if !callTree.tpe.isValueType && words.nonEmpty then
          // Mixing non-value words in value context is an error
          Reporter.error("The call does not return a value", callTree.pos)
        else
          // put back to allow chaining of function calls
          words.insert(0, callTree)

    //     3   2    1
    // x c * a + b max

    while continue && words.nonEmpty do
      val word = words.remove(0)
      val tp = word.tpe

      // TODO: type inference
      if tp.isPolyType then
        Reporter.error(s"Function ${Printing.show(word)} expects type arguments", word.pos)
        values += errorTree(word.span)

      else if tp.isProcType then
        // infix, postfix, prefix
        val procPrec = ExprTyper.precedence(word)

        if procPrec > precLimit then
          // continue if current function has higher binding power
          val callTree = call(word, tp.asProcType, words, values, procPrec)
          handleCall(callTree)
        else
          // put back word
          words.insert(0, word)
          continue = false

      else if tp.isFunctionType then
        // prefix
        if values.isEmpty && words.nonEmpty then
          val funPrec = ExprTyper.precedence(word)
          // println("funPrec = " + funPrec + ", precLimit = " + precLimit)
          if funPrec > precLimit then
            val callTree = call(word, tp.asFunctionType, words, values, funPrec)
            handleCall(callTree)
          else
            values += word

        else
          values += word

      else
        if !tp.isValueType && words.nonEmpty then
          // Mixing non-value words in value context is an error
          Reporter.error("The code does not return a value", word.pos)
        else
          values += word
    end while

    if values.isEmpty then
      assert(words.nonEmpty, "words expected")
      values += words.remove(0)
    else if values.size > 1 then
      // Given the expression `add 4 5`, in parsing the arguments for `add`,
      // we have both `4` and `5` in values. We need to put back `5`.
      words.prependAll(values.tail)
    values.head
  end parse


  def transform(expr: Ast.Expr)(using  sc: Scope, rp: Reporter, tt: TargetType): Word =
    assert(expr.words.nonEmpty)

    val sc2 = sc.fresh()

    val words  = mutable.ListBuffer.empty[Word]
    for word <- expr.words do
      given TargetType = TargetType.Unknown
      words += namer.transform(word)(using sc2)

    val word = parse(words, -1)
    if words.nonEmpty then
      val span = words.head.span | words.last.span
      Reporter.error("Found unbound part, an expression should compose to a single function call", span.toPos)

    checker.adapt(word, tt)
  end transform

  def call(
      fun: Word, procType: ProcType,
      words: mutable.ListBuffer[Word],
      values: mutable.ArrayBuffer[Word],
      precedence: Int)(
      using Reporter): Word
  =
    call(fun, procType.preParamTypes, procType.postParamTypes, procType.resultType, words, values, precedence)

  def call(
      fun: Word, funType: FunctionType,
      words: mutable.ListBuffer[Word],
      values: mutable.ArrayBuffer[Word],
      precedence: Int)(
      using Reporter): Word
  =
    call(fun, Nil, funType.paramTypes, funType.resultType, words, values, precedence)

  def call(
      fun: Word, preTypes: List[Type], postTypes: List[Type], resType: Type,
      words: mutable.ListBuffer[Word],
      values: mutable.ArrayBuffer[Word],
      precedence: Int)(
      using Reporter): Word
  =

    if values.size < preTypes.size then
      Reporter.error(
        s"Function ${Printing.show(fun)} expects ${preTypes.size} pre arguments, found = ${values.size}",
        fun.pos)
      values.clear
      errorTree(fun.span)

    else if words.size < postTypes.size then
      Reporter.error(
        s"Function ${Printing.show(fun)} expects ${postTypes.size} post arguments, found = ${words.size}",
        fun.pos)
      words.clear
      errorTree(fun.span)

    else
      val preArgs = values.takeRight(preTypes.size)
      for (arg, paramType) <- preArgs.zip(preTypes) do
        checker.checkType(arg, paramType)

      values.dropRightInPlace(preTypes.size)

      val postArgs = mutable.ArrayBuffer.empty[Word]
      for paramType <- postTypes do
        if words.nonEmpty then
          val arg = parse(words, precedence)
          checker.checkType(arg, paramType)
          postArgs += arg
        else
          Reporter.error(
            s"Missing post argument for function ${Printing.show(fun)} , type = ${paramType.show}",
            fun.pos)

      val items = (preArgs :+ fun) ++ postArgs
      val span = items.head.span | items.last.span
      Apply(fun, (preArgs ++ postArgs).toList)(resType, span)

  def errorTree(span: Span): Word = Phrase(Nil)(ErrorType, span)
end ExprTyper
