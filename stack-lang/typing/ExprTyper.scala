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
  case class Shape[B](binder: B, preParams: Int, postParams: Int, precedence: Int)

  trait Handler[T, B]:
    def resolveShape(item: T): Option[Shape[B]]
    def bundle(preItems: List[T], binder: B, postItems: List[T]): T

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

  def transformExpr(expr: Ast.Expr)(using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars): Word =
    expr.words match
       case word :: Nil =>
         return FlowTyper.transformFlow(word, namer)

       case _ =>

    val head :: rest = expr.words: @unchecked

    rest match
      case Ast.Ident(">") :: _ =>
        head match
          case ref: Ast.RefTree if Ast.isQualid(ref) =>
            val containerOpt =
              // typed without adaptation and ignore errors
              given Reporter = rp.fresh(buffer = true)
              given Scope = sc.outer
              namer.resolveContainer(ref)

            containerOpt match
              case Some(sym) =>
                // If the first word is a section or namespace reference followed by >, inject the
                // names of the container in typing the expression
                val injected = sc.fresh().freshImportedScope(sc.owner, sym.nameTable)
                given Scope = injected.fresh()
                return namer.transform(Ast.Expr(rest.tail)(expr.span))

              case _ =>

          case _ =>

      case _ =>

    val headTrialReporter = rp.fresh(buffer = true)
    val wordTyped =
      // Due to flow typing, need to re-do typing for || to check equal binding in both branches
      given Reporter = headTrialReporter
      given TargetType = TargetType.ExprItem
      given Scope = sc.fresh()
      namer.transform(head)

    val tp = wordTyped.tpe

    val isDotlessMethodCallPattern = (tp.isObjectType || tp.isClassInfoType) && rest.head.match
      case Ast.Ident(name) =>
        tp.getTermMember(name) match
          case Some(memType) => memType.isProcType
          case None => false

      case _ => false


    val isVarargApply = tp.isProcType && tp.asProcType.hasVararg

    if isDotlessMethodCallPattern then
      headTrialReporter.commit(rp)

      // Dotless method call pattern, where the infix operator takes exactly one parameter
      val words = mutable.ListBuffer.from(expr.words)
      val word = parseDotless(words, -1)

      assert(words.isEmpty, words)

      given Scope = sc.fresh()
      namer.transform(word)

    else if tp.isSingleMethodObjectType || isVarargApply then
      headTrialReporter.commit(rp)

      val app = Ast.Apply(head, rest, Nil)(head.span | rest.last.span)

      given Scope = sc.fresh()
      namer.transform(app)

    else
      // mixed prefix/infix/postfix pattern, arity depends on type of the function

      val procTypeHandler = new Handler[Ast.Word, Ast.Word]:
        def bundle(preArgs: List[Ast.Word], binder: Ast.Word, postArgs: List[Ast.Word]): Ast.Word =
          val startSpan = if preArgs.isEmpty then binder.span else preArgs.head.span
          val endSpan = if postArgs.isEmpty then binder.span else postArgs.last.span
          Ast.InfixCall(preArgs, binder, postArgs)(startSpan | endSpan)

        def resolveShape(word: Ast.Word): Option[Shape[Ast.Word]] =
          word match
            case _: Ast.RefTree | _: Ast.TypeApply =>

              // Test for shape should only use outer scope, not flow bound variables
              //
              // (1) Some code that depends on bound variables e.g. `x.f 4` would not type
              // check. That is a compromise. Users can use `(x.f 4)` to make it work.
              //
              // (2) A name might resolve to the wrong variable if flow scope is incomplete.
              // This will impact expression typing but not semantics due to retyping.
              //
              // Therefore, we should never cache the result of shape test unless
              // it's certain that the typing can't be changed by flow scope.
              given Scope = sc.outer

              // Don't call getKeyOrUpdate, see comment above
              val typed = word.getKeyOrElse(Namer.TypedWord):
                // Due to flow typing, the names might not be visible in shape test
                given tempReporter: Reporter = rp.fresh(buffer = true)

                given TargetType = TargetType.ExprItem
                namer.transform(word)

              // Cache function references, which cannot be changed by flow typing
              typed match
                case Ident(sym) if sym.is(Flags.Fun) =>
                  word.getKeyOrUpdate(Namer.TypedWord)(typed)

                case _ =>

              if typed.tpe.isProcType then
                val procType = typed.tpe.asProcType
                if procType.hasVararg then
                  Reporter.error("Vararg functions not allowed in expression syntax except being the first item.", word.pos)
                  None
                else
                  val prec = ExprTyper.precedence(word)
                  val shape = Shape(word, procType.preParamCount, procType.postParamCount, prec)
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

      FlowTyper.transformFlow(values.last, namer)
    end if
  end transformExpr

  def transformType(tpt: Ast.ExprType, allowPackType: Boolean)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, checks: Checks): TypeTree =
    val lambdaTypeHandler = new Handler[Ast.TypeTree, Ast.TypeTree]:
      var count = 0
      def bundle(preArgs: List[Ast.TypeTree], binder: Ast.TypeTree, postArgs: List[Ast.TypeTree]): Ast.TypeTree =
        val startSpan = if preArgs.isEmpty then binder.span else preArgs.head.span
        val endSpan = if postArgs.isEmpty then binder.span else postArgs.last.span
        Ast.AppliedType(binder, preArgs ++ postArgs)(startSpan | endSpan)

      def resolveShape(tpt: Ast.TypeTree): Option[Shape[Ast.TypeTree]] =
        count += 1
        tpt match
          case ref: Ast.RefTree =>
            val typed =
              tpt.getKeyOrUpdate(Namer.TypedTypeTree):
                namer.transformType(tpt, allowPackType && count <= 1)

            if typed.tpe.isTypeLambda then
              val lambdaType = typed.tpe.asTypeLambda
              val prec = ExprTyper.precedence(ref)
              val shape = Shape[Ast.TypeTree](ref, lambdaType.preParamCount, lambdaType.postParamCount, prec)
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
  def parseMixed[T, B]
      (words: mutable.ListBuffer[T], precLimit: Int, handler: Handler[T, B])
  : List[T] =

    // println("Parsing " + words + ", precedence = " + precedence)

    val values = mutable.ArrayBuffer.empty[T]

    def step(shape: Shape[B]): Unit =
      val preParamCount = shape.preParams
      val postParamCount = shape.postParams

      val preArgs = values.takeRight(preParamCount).toList
      values.dropRightInPlace(preParamCount)

      val (postArgs, rest) = parseMixed(words, shape.precedence, handler).splitAt(postParamCount)

      val binding = handler.bundle(preArgs, shape.binder, postArgs)

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
            step(shape)

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
