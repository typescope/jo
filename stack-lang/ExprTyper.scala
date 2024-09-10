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

  /** The structure formed by precedence parsing and partial type checkig  */
  enum Item:
    /** An untyped word */
    case Raw(word: Ast.Word)

    /** An typed word */
    case Typed(word: Word)

    /** A call tree where the function is a typed ident */
    case Call(fun: Word, preArgs: List[Item], postArgs: List[Item])
             (val preTypes: List[Type], val postTypes: List[Type], val resultType: Type)

    def span: Span =
      this match
        case Raw(word) => word.span
        case Typed(word) => word.span
        case Call(fun, pre, post) =>
          (pre ++ post).foldRight(fun.span)(_.span | _)

/**
  * Perform type checking for an expression.
  *
  * Used to check stack safety and construct function call nodes.
  *
  * Instance of the class should be able to be reused to type check different
  * expression. Therefore, it should not contain any expression-specific state.
  */
class ExprTyper(namer: Namer, checker: Checker):
  import ExprTyper.Item

  def transform(expr: Ast.Expr)(using  sc: Scope, rp: Reporter, tt: TargetType): Word =
    assert(expr.words.nonEmpty)

    val words = mutable.ListBuffer.from(expr.words)
    val values = parse(words, -1)

    if values.size > 1 then
      val span = values(1).span | values.last.span
      Reporter.error("Found unbound part, an expression should compose to a single function call", span.toPos)

    typeItem(values.head)
  end transform

  private def typeItem(item: Item)(using sc: Scope, rp: Reporter, tt: TargetType): Word =
    item match
      case Item.Typed(word) => checker.adapt(word, tt)

      case Item.Raw(word) =>
        namer.transform(word)

      case call @ Item.Call(fun, preArgs, postArgs) =>
        val span = call.span
        if preArgs.size < call.preTypes.size then
          Reporter.error(
            s"Function ${Printing.show(fun)} expects ${call.preTypes.size} pre arguments, found = ${preArgs.size}",
            span.toPos)
          errorTree(span)

        else if postArgs.size < call.postTypes.size then
          Reporter.error(
            s"Function ${Printing.show(fun)} expects ${call.postTypes.size} post arguments, found = ${postArgs.size}",
            span.toPos)
          errorTree(span)

        else
          val preArgs2 =
            for (arg, paramType) <- preArgs.zip(call.preTypes) yield
              given TargetType = TargetType.Known(paramType)
              typeItem(arg)

          val postArgs2 =
            for (arg, paramType) <- postArgs.zip(call.postTypes) yield
              given TargetType = TargetType.Known(paramType)
              typeItem(arg)

          val word = Apply(fun, preArgs2 ++ postArgs2)(call.resultType, span)
          checker.adapt(word, tt)

  /** Parse items from the words with the limit precedence */
  private def parse(words: mutable.ListBuffer[Ast.Word], precLimit: Int)(using rp: Reporter, sc: Scope): List[Item] =
    // println("Parsing " + words + ", precedence = " + precedence)

    val values = mutable.ArrayBuffer.empty[Item]
    var continue = true

    //     3   2    1
    // x c * a + b max

    while continue && words.nonEmpty do
      val word = words.remove(0)
      word match
        case _: Ast.Ident | _: Ast.TypeApply =>
          given TargetType = TargetType.Unknown
          val word2 = namer.transform(word)
          val tp = word2.tpe

          if tp.isPolyType then
            // TODO: type inference
            Reporter.error(s"Function ${Printing.show(word2)} expects type arguments", word.pos)
            values += Item.Typed(errorTree(word.span))

          else if tp.isProcType then
            val procType = tp.asProcType
            // infix, postfix, prefix
            val procPrec = ExprTyper.precedence(word2)

            if procPrec > precLimit then
              // continue if current function has higher binding power
              values ++= call(word2, procType.preParamTypes, procType.postParamTypes, procType.resultType, words, values, procPrec)
            else
              // put back word
              words.insert(0, word)
              continue = false

          else if tp.isFunctionType && word2.isInstanceOf[Ident] then
            val funType = tp.asFunctionType
            // prefix
            if values.isEmpty && words.nonEmpty then
              val funPrec = ExprTyper.precedence(word2)
              // println("funPrec = " + funPrec + ", precLimit = " + precLimit)
              if funPrec > precLimit then
                val preTypes = Nil
                values ++= call(word2, preTypes, funType.paramTypes, funType.resultType, words, values, funPrec)
              else
                values += Item.Typed(word2)

            else
              values += Item.Typed(word2)

          else
            values += Item.Typed(word2)
        case _ =>
          values += Item.Raw(word)
    end while

    values.toList
  end parse

  def call(
      fun: Word, preTypes: List[Type], postTypes: List[Type], resultType: Type,
      words: mutable.ListBuffer[Ast.Word],
      values: mutable.ArrayBuffer[Item],
      precedence: Int)(
      using Reporter, Scope): List[Item]
  =
    val preArgs = values.takeRight(preTypes.size).toList
    values.dropRightInPlace(preTypes.size)

    val (postArgs, rest) = parse(words, precedence).splitAt(postTypes.size)
    Item.Call(fun, preArgs, postArgs)(preTypes, postTypes, resultType) :: rest

  def errorTree(span: Span): Word = Phrase(Nil)(ErrorType, span)
end ExprTyper
