package typing

import scala.collection.mutable

import ast.{ Trees => Ast }
import ast.Positions.*
import ast.Naming

import sast.*
import sast.Trees.*

import Inference.TargetType

import reporting.Reporter

import ExprTyper.{ Shape, ShapeHandler, OperatorHandler }

object ExprTyper:
  /** The shape of a function or type constructor */
  case class Shape[B](binder: B, preParams: Int, postParams: Int)

  trait ShapeHandler[T, B]:
    def resolveShape(item: T): Option[Shape[B]]
    def bundle(preItems: List[T], binder: B, postItems: List[T]): T

  trait OperatorHandler[T]:
    def prefix(binder: Ast.Ident, rhs: T): T
    def infix(lhs: T, binder: Ast.Ident, rhs: T): T
    def error(span: Span): T

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
      case "!"   =>  15

      case ">"   | "<"  | ">=" | "<=" | "==" | "!=" => 20
      case "+"   | "-"                              => 30
      case "<<"  | ">>" | "|"  | "&"  | "^"         => 40
      case "*"   | "/"  | "%"                       => 50
      case _                                        => 100

  /** Whether the operator is a precedence operator
    *
    * It is a mistake to mix precedence operator with non-precedence operator
    * in an expression.
    */
  def isPrecedenceOperator(op: String): Boolean = precedence(op) < 100

/**
  * Perform type checking for an expression.
  *
  * Used to construct function call nodes.
  *
  * Instance of the class should be able to be reused to type check different
  * expression. Therefore, it should not contain any expression-specific state.
  */
class ExprTyper(namer: Namer):
  /** Type a shape expression without operators */
  def transformShapeExpr(expr: Ast.Expr)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars, tt: TargetType)
  : Word =

    val procTypeHandler = new ShapeHandler[Ast.Word, Ast.Word]:
      def bundle(preArgs: List[Ast.Word], binder: Ast.Word, postArgs: List[Ast.Word]): Ast.Word =
        val startSpan = if preArgs.isEmpty then binder.span else preArgs.head.span
        val endSpan = if postArgs.isEmpty then binder.span else postArgs.last.span
        Ast.InfixCall(preArgs, binder, postArgs)(startSpan | endSpan)

      def resolveShape(word: Ast.Word): Option[Shape[Ast.Word]] =
        word match
          case _: Ast.RefTree | _: Ast.TypeApply =>
            val typed =
              word.getKeyOrUpdate(Namer.TypedWord):
                given TargetType = TargetType.ExprItem
                namer.transform(word)

            if typed.tpe.isProcType then
              val procType = typed.tpe.asProcType
              if procType.hasVararg then
                Reporter.error("Vararg functions not allowed in expression except being the first item.", word.pos)
                None

              else
                val shape = Shape(word, procType.preParamCount, procType.postParamCount)
                Some(shape)

            else
              None

          case _ =>
            None

    val words: mutable.ListBuffer[Ast.Word] = mutable.ListBuffer.from(expr.words)
    val values = parseShapeExpr(words, procTypeHandler)

    assert(words.isEmpty, words)
    if values.size > 1 then
      val rest = values.init
      val span = rest.head.span | rest.last.span
      Reporter.error("Found extra value, an expression should produce a single value", span.toPos)

    namer.transform(values.last)

  def transformType(tpt: Ast.ExprType, allowPackType: Boolean)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, checks: Checks)
  : TypeTree =

    val isOperatorExpr = tpt.types.exists:
      case Ast.Ident(name) if Naming.isOperator(name) => true
      case _ => false

    if isOperatorExpr then
      val handler = new ExprTyper.OperatorHandler[Ast.TypeTree]:
        def prefix(binder: Ast.Ident, rhs: Ast.TypeTree): Ast.TypeTree =
          Ast.AppliedType(binder, rhs :: Nil)(binder.span | rhs.span)

        def infix(lhs: Ast.TypeTree, binder: Ast.Ident, rhs: Ast.TypeTree): Ast.TypeTree =
          Ast.AppliedType(binder, lhs :: rhs :: Nil)(lhs.span | rhs.span)

        def error(span: Span): Ast.TypeTree = Ast.Ident("Bottom")(span)

      val words = mutable.ListBuffer.from(tpt.types)
      val word = parseOperatorExpr(words, handler)
      namer.transformType(word, allowPackType)

    else
      transformShapeExprType(tpt, allowPackType)

  def transformShapeExprType(tpt: Ast.ExprType, allowPackType: Boolean)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, checks: Checks)
  : TypeTree =

    val lambdaTypeHandler = new ShapeHandler[Ast.TypeTree, Ast.TypeTree]:
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
              val shape = Shape[Ast.TypeTree](tpt, lambdaType.preParamCount, lambdaType.postParamCount)
              Some(shape)

            else
              None

          case _ =>
            None

    val types: mutable.ListBuffer[Ast.TypeTree] = mutable.ListBuffer.from(tpt.types)
    val typeTrees = parseShapeExpr(types, lambdaTypeHandler)

    assert(types.isEmpty, types)
    if typeTrees.size > 1 then
      val rest = typeTrees.init
      val span = rest.head.span | rest.last.span
      Reporter.error("Found extra type, a type expression should produce a single type", span.toPos)

    namer.transformType(typeTrees.last, allowPackType)
  end transformShapeExprType


  /** Form AST from the words based on shape but not on precedence */
  def parseShapeExpr[T, B](words: mutable.ListBuffer[T], handler: ShapeHandler[T, B])(using Source, Reporter): List[T] =
    val stack = new mutable.ArrayBuffer[T]

    while words.nonEmpty do
      val word = words.remove(0)
      handler.resolveShape(word) match
        case Some(Shape(binder, preParamCount, postParamCount)) =>
          val preArgs = stack.takeRight(preParamCount).toList
          stack.dropRightInPlace(preParamCount)

          // Error will be reported during typing
          val postArgs = words.take(postParamCount).toList
          words.dropInPlace(postParamCount)

          stack += handler.bundle(preArgs, binder, postArgs)

        case None =>
          stack += word
    end while

    stack.toList
  end parseShapeExpr

  /** A flat operator expression -- no precedence, no shape
    *
    * The operators must be infix or prefix operators that take exactly one post argument.
    */
  def parseOperatorExpr[T <: Ast.Tree](words: mutable.ListBuffer[T], handler: OperatorHandler[T])(using rp: Reporter, so: Source): T =
    def parsePrefix(): T =
      val head = words.remove(0)
      head match
        case op @ Ast.Ident(name) if Naming.isOperator(name) =>
          // unary operator must be followed a non-operator word
          if words.isEmpty then
            Reporter.error(s"Argument expected for the unary operator $name, found none", head.pos)
            handler.error(head.span)

          else
            val arg = words.remove(0)
            arg match
              case Ast.Ident(name2) if Naming.isOperator(name2) =>
                Reporter.error(s"Unary operator $name should be followed by an argument, found another operator $name2", arg.pos)
                handler.error(arg.span)

              case _ =>
                handler.prefix(op, arg)

            end match
          end if

        case _ =>
          // no unary operator
          head

    var res = parsePrefix()

    while words.nonEmpty do
      val word = words.remove(0)
      word match
        case op @ Ast.Ident(name) if Naming.isOperator(name) =>
          if words.isEmpty then
            Reporter.error(s"Rhs expected for the operator $name, found none", word.pos)

          else
            val rhs = parsePrefix()
            res = handler.infix(res, op, rhs)

        case _ =>
          Reporter.error("An infix operator expected here for a flat operator expression", word.pos)
    end while

    res
end ExprTyper
