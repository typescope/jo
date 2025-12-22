package typing

import scala.collection.mutable

import ast.{ Trees => Ast }
import ast.Positions.*
import ast.Naming

import sast.*
import sast.Trees.*

import reporting.Reporter

import ExprTyper.{ Shape, Handler }

object ExprTyper:
  /** The shape of a function or type constructor */
  case class Shape[B](binder: B, preParams: Int, postParams: Int, precedence: Int)

  trait Handler[T, B]:
    def resolveShape(item: T): Option[Shape[B]]
    def bundle(preItems: List[T], binder: B, postItems: List[T]): T

  /** The precedence of an operator
    *
    * We fix the precedence of names in the compiler according to established
    * convention in mathematics and programming languages.
    *
    * For simplicity, all operators are left-associative. The existence of list
    * literals and sequence patterns cover the use case for `::`.
    *
    * We disallow setting precedence of operator names (no matter it is
    * user-defined or primitive) for usability: programmers should not learn and
    * remember precedence beyond the basic ones. When in doubt, parentheses can
    * be used.
    *
    * While users may define custom operators, it is on purpose to disallow
    * defining precedence for them --- no one can understand/remember them.
    *
    * Programs that do not depend on precedence rules are easier to understand.
    */
  def precedence(op: String): Int =
    op match
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
          case Ast.Ident(name) if Naming.isOperator(name) =>
            val typed =
              tpt.getKeyOrUpdate(Namer.TypedTypeTree):
                namer.transformType(tpt, allowPackType && count <= 1)

            if typed.tpe.isTypeLambda then
              val lambdaType = typed.tpe.asTypeLambda
              val prec = ExprTyper.precedence(name)
              val shape = Shape[Ast.TypeTree](tpt, lambdaType.preParamCount, lambdaType.postParamCount, prec)
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
end ExprTyper
