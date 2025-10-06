package typing

import scala.collection.mutable

import ast.{ Trees => Ast }
import sast.*
import sast.Trees.*
import sast.Types.*

import ast.Positions.*
import reporting.Reporter

import Inference.TargetType

import ExprTyper.{ Shape, Handler }

object ExprTyper:
  /** The shape of a function or type constructor */
  case class Shape(preParams: Int, postParams: Int, precedence: Int)

  trait Handler[T]:
    def resolveShape(item: T): Option[Shape]
    def bundle(preItems: List[T], binder: T, postItems: List[T]): T

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
      case "&&"  =>  10
      case "!"   =>  150

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
    expr.words match
       case word :: Nil =>
         return namer.transform(word)

       case (tag: Ast.Tag) :: args =>
         return namer.transformTagged(tag, args)

       case _ =>

    val head :: rest = expr.words: @unchecked

    val wordTyped =
      head.getKeyOrUpdate(Namer.TypedWord):
        given TargetType = TargetType.Unknown
        namer.transform(head)

    val tp = wordTyped.tpe

    val isDotlessMethodCallPattern = (tp.isObjectType || tp.isClassType) && rest.head.match
      case Ast.Ident(name) =>
        tp.getTermMember(name) match
          case Some(memType) => memType.isProcType
          case None => false

      case _ => false


    val isVarargApply = tp.isProcType && tp.asProcType.hasVararg

    val containerSymbolOpt =
      rest match
        case Ast.Ident(">") :: _ =>
          head match
            case ref: Ast.RefTree =>
              // typed without adaptation and ignore errors
              given Reporter = rp.fresh(buffer = true)
              val wordTyped = namer.transformRefTree(ref)
              wordTyped.tpe match
                case StaticRef(sym) if sym.isContainer => Some(sym)
                case _ => None

            case _ => None

        case _ => None


    if containerSymbolOpt.nonEmpty then
      // If the first word is a section or namespace reference followed by >, inject the
      // names of the container in typing the expression
      val sym = containerSymbolOpt.get
      val injected = sc.fresh(sc.owner, sym.info.as[ContainerInfo].nameTable)
      given Scope = injected.fresh()
      transform(Ast.Expr(rest.tail)(expr.span))

    else if isDotlessMethodCallPattern then
      // Dotless method call pattern, where the infix operator takes exactly one parameter
      val words = mutable.ListBuffer.from(expr.words)
      val word = parseDotless(words, -1)

      assert(words.isEmpty, words)
      namer.transform(word)

    else if tp.isSingleMethodObjectType || isVarargApply then
      val app = Ast.Apply(head, rest)(head.span | rest.last.span)
      namer.transform(app)

    else
      // mixed prefix/infix/postfix pattern, arity depends on type of the function

      val procTypeHandler = new Handler[Ast.Word]:
        def bundle(preArgs: List[Ast.Word], binder: Ast.Word, postArgs: List[Ast.Word]): Ast.Word =
          val startSpan = if preArgs.isEmpty then binder.span else preArgs.head.span
          val endSpan = if postArgs.isEmpty then binder.span else postArgs.last.span
          Ast.InfixCall(preArgs, binder, postArgs)(startSpan | endSpan)

        def resolveShape(word: Ast.Word): Option[Shape] =
          word match
            case _: Ast.RefTree | _: Ast.TypeApply =>
              val typed =
                word.getKeyOrUpdate(Namer.TypedWord):
                  given TargetType = TargetType.Unknown
                  namer.transform(word)

              if typed.tpe.isProcType then
                val procType = typed.tpe.asProcType
                if procType.hasVararg then
                  Reporter.error("Vararg functions not allowed in expression syntax except being the first item.", word.pos)
                  None
                else
                  val prec = ExprTyper.precedence(word)
                  val shape = Shape(procType.preParamCount, procType.postParamCount, prec)
                  Some(shape)
              else
                None

            case _ =>
              None

      val words: mutable.ListBuffer[Ast.Word] = mutable.ListBuffer.from(expr.words)
      val values = parseMixed(words, -1, procTypeHandler)

      assert(words.isEmpty, words)
      if values.size > 1 then
        val rest = values.init
        val span = rest.head.span | rest.last.span
        Reporter.error("Found extra value, an expression should produce a single value", span.toPos)

      namer.transform(values.last)
    end if
  end transform

  def transformType(tpt: Ast.ExprType, allowPackType: Boolean)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): TypeTree =
    val lambdaTypeHandler = new Handler[Ast.TypeTree]:
      var count = 0
      def bundle(preArgs: List[Ast.TypeTree], binder: Ast.TypeTree, postArgs: List[Ast.TypeTree]): Ast.TypeTree =
        val startSpan = if preArgs.isEmpty then binder.span else preArgs.head.span
        val endSpan = if postArgs.isEmpty then binder.span else postArgs.last.span
        Ast.AppliedType(binder, preArgs ++ postArgs)(startSpan | endSpan)

      def resolveShape(tpt: Ast.TypeTree): Option[Shape] =
        count += 1
        tpt match
          case ref: Ast.RefTree =>
            val typed =
              tpt.getKeyOrUpdate(Namer.TypedTypeTree):
                namer.transformType(tpt, allowPackType && count <= 1)

            if typed.tpe.isTypeLambda then
              val lambdaType = typed.tpe.asTypeLambda
              val prec = ExprTyper.precedence(ref)
              val shape = Shape(lambdaType.preParamCount, lambdaType.postParamCount, prec)
              Some(shape)
            else
              None

          case _ =>
            None

    val types: mutable.ListBuffer[Ast.TypeTree] = mutable.ListBuffer.from(tpt.types)
    val typeTrees = parseMixed(types, -1, lambdaTypeHandler)

    assert(types.isEmpty, types)
    if typeTrees.size > 1 then
      val rest = typeTrees.init
      val span = rest.head.span | rest.last.span
      Reporter.error("Found extra type, a type expression should produce a single type", span.toPos)

    namer.transformType(typeTrees.last, allowPackType)
  end transformType


  /** Form AST from the words with the limit precedence for mixed prefix/infix/postfix pattern */
  def parseMixed[T]
      (words: mutable.ListBuffer[T], precLimit: Int, handler: Handler[T])
  : List[T] =

    // println("Parsing " + words + ", precedence = " + precedence)

    val values = mutable.ArrayBuffer.empty[T]

    def step(binder: T, shape: Shape): Unit =
      val preParamCount = shape.preParams
      val postParamCount = shape.postParams

      val preArgs = values.takeRight(preParamCount).toList
      values.dropRightInPlace(preParamCount)

      val (postArgs, rest) = parseMixed(words, shape.precedence, handler).splitAt(postParamCount)

      val binding = handler.bundle(preArgs, binder, postArgs)

      // continue if current binder has higher binding power
      values += binding

      // It is important that the rest is added after the inserting `call`
      values ++= rest

    var continue = true
    while continue && words.nonEmpty do
      val word = words.remove(0)
      handler.resolveShape(word) match
        case Some(shape) =>
          // infix, postfix, prefix
          if shape.precedence > precLimit then
            step(word, shape)

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

end ExprTyper
