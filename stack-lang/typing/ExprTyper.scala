package typing

import scala.collection.mutable

import ast.Ast
import sast.*
import sast.Sast.*
import sast.Types.*

import ast.Positions.*
import reporting.Reporter

import Inference.TargetType

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
  def precedence(word: Ast.Word): Int =
    word match
      case Ast.Ident(name)   => precedence(name)
      case _                 => 100


  /** The precedence of common operators
    *
    * While users may define other operators, it is on purpose to disallow
    * defining precedence for them --- no one can understand/remember them.
    *
    * Programs that do not depend on precedence rules are easier to understand.
    */
  def precedence(fun: String): Int =
    fun match
      case "||"  =>  5
      case "&&" =>  10
      case "!"  =>  15

      case ">"   | "<"  | ">=" | "<=" | "==" | "!=" => 20
      case "+"   | "-"                              => 30
      case "<<"  | ">>" | "|"  | "&"  | "^"         => 40
      case "*"   | "/"  | "%"                       => 50
      case _                                        => 100

/**
  * Perform type checking for an expression.
  *
  * Used to construct function call nodes.
  *
  * Instance of the class should be able to be reused to type check different
  * expression. Therefore, it should not contain any expression-specific state.
  */
class ExprTyper(namer: Namer):

  def transform(expr: Ast.Expr)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    expr.words: @unchecked match
    case head :: Nil =>
      namer.transform(head)

    case (tag: Ast.Tag) :: args =>
      namer.transformTagged(tag, args)

    case head :: rest =>
      val wordTyped =
        given TargetType = TargetType.Unknown
        namer.transform(head)

      head.addKey(Namer.TypedWord, wordTyped)

      val tp = wordTyped.tpe

      val isDotlessMethodCallPattern = tp.isObjectType && rest.head.match
        case Ast.Ident(name) =>
          tp.getTermMember(name) match
            case Some(memType) => memType.isProcType
            case None => false

        case _ => false

      if tp.is[TypeRef] && tp.as[TypeRef].symbol.isContainer then
        // if the first word is a section or namespace reference, inject the
        // names of the container in typing the expression
        val tref = tp.as[TypeRef]
        val injected = sc.fresh(tref.symbol, tref.symbol.info.as[NameTableInfo].nameTable)
        given Scope = injected.fresh()
        transform(Ast.Expr(rest)(expr.span))

      else if isDotlessMethodCallPattern then
        // Dotless method call pattern, where the infix operator takes exactly one parameter
        val words = mutable.ListBuffer.from(expr.words)
        val word = parseDotless(words, -1)

        assert(words.isEmpty, words)
        namer.transform(word)

      else if tp.hasOnlyApplyMethod then
        val app = Ast.Apply(head, rest)(head.span | rest.last.span)
        namer.transform(app)

      else
        // mixed prefix/infix/postfix pattern, arity depends on type of the function
        val words: mutable.ListBuffer[Ast.Word] = mutable.ListBuffer.from(expr.words)

        val resolveProc: Ast.Word => Option[ProcType] = (word: Ast.Word) => word match
          case _: Ast.RefTree | _: Ast.TypeApply =>
            val typed =
              word.getKeyOrUpdate(Namer.TypedWord):
                given TargetType = TargetType.Unknown
                namer.transform(word)

            if typed.tpe.isProcType then Some(typed.tpe.asProcType) else None

          case _ =>
            None

        val values = parseMixed(words, -1, resolveProc)

        assert(words.isEmpty, words)
        if values.size > 1 then
          val rest = values.init
          val span = rest.head.span | rest.last.span
          Reporter.error("Found extra value, an expression should produce at most one value", span.toPos)

        namer.transform(values.last)
  end transform

  /** Form AST from the words with the limit precedence for mixed prefix/infix/postfix pattern */
  def parseMixed(
    words: mutable.ListBuffer[Ast.Word], precLimit: Int, resolveProc: Ast.Word => Option[ProcType])
    (using rp: Reporter, sc: Scope, so: Source)
  : List[Ast.Word] =
    // println("Parsing " + words + ", precedence = " + precedence)

    val values = mutable.ArrayBuffer.empty[Ast.Word]

    def step(fun: Ast.Word, procType: ProcType, precedence: Int): Unit =
      val preParamCount = procType.preParamCount
      val postParamCount = procType.postParamCount

      val preArgs = values.takeRight(preParamCount).toList
      values.dropRightInPlace(preParamCount)

      val (postArgs, rest) = parseMixed(words, precedence, resolveProc).splitAt(postParamCount)

      val startSpan = if preArgs.isEmpty then fun.span else preArgs.head.span
      val endSpan = if postArgs.isEmpty then fun.span else postArgs.last.span
      val call = Ast.InfixCall(preArgs, fun, postArgs)(startSpan | endSpan)

      // continue if current function has higher binding power
      values += call

      // It is important that the rest is added after the inserting `call`
      values ++= rest


    var continue = true
    while continue && words.nonEmpty do
      val word = words.remove(0)
      resolveProc(word) match
        case Some(procType) =>
          val precedence = ExprTyper.precedence(word)
          // infix, postfix, prefix
          if precedence > precLimit then
            step(word, procType, precedence)

          else
            // put back word
            words.insert(0, word)
            continue = false

        case None =>
          values += word
    end while

    values.toList
  end parseMixed

  /** Form AST from the words with the limit precedence for dotless call syntax */
  private def parseDotless(words: mutable.ListBuffer[Ast.Word], precLimit: Int)(using rp: Reporter, so: Source): Ast.Word =
    // println("Parsing " + words + ", precedence = " + precLimit)

    var res = words.remove(0)
    var continue = true

    // a + b - c * 2

    while continue && words.nonEmpty do
      val word = words.remove(0)
      word match
        case ident: Ast.Ident =>
          if words.isEmpty then
            continue = false
            Reporter.error("Exact one argument expected in dotless call syntax, found none", word.pos)

          else
            // TODO: support prefix operators?
            val precedence = ExprTyper.precedence(ident.name)
            // infix
            if precedence > precLimit then
              val rhs = parseDotless(words, precedence)
              res = Ast.DotlessCall(res, ident, rhs)(res.span | rhs.span)
            else
              words.insert(0, word)
              continue = false

        case word =>
          continue = false
          words.clear
          Reporter.error("A method name expected here in dotless call syntax", word.pos)
      end match
    end while

    res
  end parseDotless

  def errorTree(span: Span): Word = Block(Nil)(ErrorType, span)
end ExprTyper
