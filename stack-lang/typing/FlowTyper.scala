package typing

import ast.{ Trees => Ast }
import ast.Positions.*
import ast.Naming

import common.Debug
import common.OutOfBand

import sast.*
import sast.Trees.*
import sast.Types.Type
import reporting.Reporter

import Inference.TargetType

import scala.collection.mutable

/** Flow typer for conditional operator expressions
  *
  * The flow typing for patterns is implemented in PatternTyper.
  */
object FlowTyper:
  extension (word: Word)
    def adapt(using tt: TargetType, defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tvars: TypeVars): Word =
      // adaptation should not need flow scope
      given Scope = sc.outer
      Checker.adapt(word, tt)

  def transformFlow(word: Ast.Word, namer: Namer)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word = Debug.trace(s"Flow typing ${word.show}, owner = ${sc.owner}, scope = ${sc.show}", (_: Word).show, enable = false):
    word match
      case isExpr: Ast.IsExpr =>
        namer.transformIsExpr(isExpr)

      case expr: Ast.Expr =>
        transformExpr(expr, namer)

      case infixCall: Ast.InfixOperatorCall =>
        transformInfixOperatorCall(infixCall, namer)

      case prefixCall: Ast.PrefixOperatorCall =>
        transformPrefixOperatorCall(prefixCall, namer)

      case Ast.Fence(phrase) =>
        transformFlow(phrase, namer)

      case _ =>
        given Scope = sc.fresh()
        namer.transform(word)

  def transformExpr(expr: Ast.Expr, namer: Namer)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word =

    expr.words match
       case word :: Nil =>
         return transformFlow(word, namer)

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
                // If the first word is a container name followed by >, inject the
                // names of the container in typing the expression
                val injected = sc.fresh().freshImportedScope(sc.owner, sym.nameTable)

                // No out flow propagation from ">"
                given FlowScope = new FlowScope(injected.fresh())
                return transformExpr(Ast.Expr(rest.tail)(expr.span), namer)

              case _ =>

          case _ =>

      case _ =>

    val isOperatorExpr = expr.words.exists:
      case Ast.Ident(name) if Naming.isOperator(name) => true
      case _ => false

    val isPrecedenceExpr = isOperatorExpr && expr.words.exists:
      case Ast.Ident(name) if Naming.isOperator(name) =>
        ExprTyper.isPrecedenceOperator(name)

      case _ => false

    if isPrecedenceExpr then
      // precedence expression, where only infix & prefix operators are supported
      val words = mutable.ListBuffer.from(expr.words)
      val word = parsePrecedenceExpr(words, -1)

      transformFlow(word, namer)

    else if isOperatorExpr then
      // No out flow propagation from current expression
      given Scope = sc.fresh()
      val handler = new ExprTyper.OperatorHandler[Ast.Word]:
        def prefix(binder: Ast.Ident, rhs: Ast.Word): Ast.Word =
          Ast.PrefixOperatorCall(binder, rhs)(binder.span | rhs.span)

        def infix(lhs: Ast.Word, binder: Ast.Ident, rhs: Ast.Word): Ast.Word =
          Ast.InfixOperatorCall(lhs, binder, rhs)(lhs.span | rhs.span)

        def error(span: Span): Ast.Word = Ast.Ident("...")(span)


      val words = mutable.ListBuffer.from(expr.words)
      val word = namer.exprTyper.parseOperatorExpr(words, handler)

      namer.transform(word)

    else
      // Completely disallow operators in shape expressions.
      //
      // The usage of precedence operators in operator expressions is forbidden
      // for the sake of readability.
      //
      // As a result, we force programmers to define custom operators:
      //
      // (1) Either they are precedence operators with the same precedence, or
      // (2) They cannot have precedence and may only used in operator expressions


      // No out flow propagation from current expression
      given Scope = sc.fresh()

      val wordTyped =
        head.getKeyOrUpdate(Namer.TypedWord):
          given TargetType = TargetType.ExprItem
          namer.transform(head)

      val tp = wordTyped.tpe
      val isVarargApply = tp.isProcType && tp.asProcType.hasVararg

      if tp.isLambdaType || isVarargApply then
        val app = Ast.Apply(head, rest, havingBindings = Nil)(head.span | rest.last.span)
        namer.transform(app)

      else
        namer.exprTyper.transformShapeExpr(expr)

  end transformExpr

  def transformPrefixOperatorCall(call: Ast.PrefixOperatorCall, namer: Namer)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word = Debug.trace(s"Flow typing ${call.show}, owner = ${sc.owner}, scope = ${sc.show}", (_: Word).show, enable = false):
    val Ast.PrefixOperatorCall(op, rhs) = call

    // Typing operator using outer scope
    //
    // Flow typing should not change operator semantics
    val opSym =
      given oob: OutOfBand = new OutOfBand
      val sym = sc.outer.resolveTerm(op.name, op.pos)
      if oob.hasKey(Scope.PrefixKey) then
        val message = "Unexpected prefix in typing operator " + op
        Reporter.error(message, op.pos)
        Reporter.abortInternal(message)

      sym

    val fun = Ident(opSym)(op.span)
    op.addKey(Namer.TypedWord, fun)

    if opSym == defn.Bool_not then
      // `!` does not change bound variables
      val snapShot = sc.promotedSet()

      val arg =
        given TargetType = TargetType.Known(defn.BoolType)
        transformFlow(rhs, namer)

      sc.resetPromotedSet(snapShot)

      Apply(fun, arg :: Nil, autos = Nil)(call.span).adapt

    else
      given Scope = sc.fresh()
      val infixCall = Ast.InfixCall(Nil, op, rhs :: Nil)(call.span)
      namer.transformInfixCall(infixCall).adapt

  def transformInfixOperatorCall(call: Ast.InfixOperatorCall, namer: Namer)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word = Debug.trace(s"Flow typing ${call.show}, owner = ${sc.owner}, scope = ${sc.show}", (_: Word).show, enable = false):
    val Ast.InfixOperatorCall(lhs, op, rhs) = call

    // Snapshot for `&&`
    val snapShot = sc.promotedSet()

    val lhsTyped =
      lhs.getKeyOrUpdate(Namer.TypedWord):
        given TargetType = TargetType.ValueType
        transformFlow(lhs, namer)

    val tp = lhsTyped.tpe

    val isDotlessMethodCall = tp.isClassInfoType && {
      tp.getTermMember(op.name) match
        case Some(memType) => memType.isProcType
        case None => false
    }

    if isDotlessMethodCall then
      // No out flow from dotless call
      given Scope = sc.fresh()
      return namer.transformDotlessCall(call)

    // Typing operator using outer scope
    //
    // Flow typing should not change operator semantics
    val opSym =
      given oob: OutOfBand = new OutOfBand
      val sym = sc.outer.resolveTerm(op.name, op.pos)
      if oob.hasKey(Scope.PrefixKey) then
        val message = "Unexpected prefix in typing operator " + op
        Reporter.error(message, op.pos)
        Reporter.abortInternal(message)

      sym

    if opSym == defn.Bool_and then
      val targetTypeBool = TargetType.Known(defn.BoolType)

      val lhsTypedAdapted =
        given TargetType = targetTypeBool
        lhsTyped.adapt

      // Bound variables accumulate for `&&`
      val rhsTyped =
        given TargetType = targetTypeBool
        transformFlow(rhs, namer)

      val falseLit = BoolLit(false)(rhs.span.endPoint)
      If(lhsTypedAdapted, rhsTyped, falseLit)(defn.BoolType, call.span).adapt

    else if opSym == defn.Bool_or then
      // `||` must bind the same set of variables for both branches

      val targetTypeBool = TargetType.Known(defn.BoolType)

      val lhsTypedAdapted =
        given TargetType = targetTypeBool
        lhsTyped.adapt

      val setLHS = sc.resetPromotedSet(snapShot) -- snapShot

      val rhsTyped =
        given TargetType = targetTypeBool
        transformFlow(rhs, namer)

      val setRHS = sc.promotedSet() -- snapShot

      if setLHS != setRHS then
        Reporter.error(
          s"The lhs and rhs bind should bind same set of variables, found lhs = " + setLHS + ", rhs = " + setRHS,
          call.pos
        )

      val trueLit = BoolLit(true)(lhs.span.endPoint)
      If(lhsTypedAdapted, trueLit, rhsTyped)(defn.BoolType, call.span).adapt

    else
      val fun = Ident(opSym)(op.span)
      op.addKey(Namer.TypedWord, fun)

      given Scope = sc.fresh()
      val infixCall = Ast.InfixCall(lhs :: Nil, op, rhs :: Nil)(call.span)
      namer.transformInfixCall(infixCall).adapt

  /** Form AST from the words with the limit precedence for precedence expression
    *
    * A precedence expression must only contain precedence operators. It is an
    * error to mix precedence operators and non-precedence operators.
    */
  private def parsePrecedenceExpr(words: mutable.ListBuffer[Ast.Word], precLimit: Int)(using rp: Reporter, so: Source): Ast.Word =
    // println("Parsing " + words + ", precedence = " + precLimit)
    val head = words.remove(0)

    def errorWord(span: Span): Ast.Word = Ast.Ident("...")(span)

    var res =
      head match
        case op @ Ast.Ident(name) if Naming.isOperator(name) =>
          // unary operator must be followed a non-operator word
          if words.isEmpty then
            Reporter.error(s"Argument expected for the unary operator $name, found none", head.pos)
            errorWord(head.span)

          else
            val arg = words.remove(0)
            arg match
              case Ast.Ident(name2) if Naming.isOperator(name2) =>
                Reporter.error(s"Unary operator $name should be followed by an argument, found another operator $name2", arg.pos)
                errorWord(arg.span)

              case _ =>
                Ast.PrefixOperatorCall(op, arg)(head.span | arg.span)

            end match
          end if

        case _ =>
          // no unary operator
          head


    var continue = true

    // a + b - c * 2

    while continue && words.nonEmpty do
      val word = words.remove(0)
      word match
        case op @ Ast.Ident(name) if Naming.isOperator(name) =>
          if !ExprTyper.isPrecedenceOperator(name) then
            Reporter.error(s"Mixing non-precedence infix operator in precedence expression is disallowed, operator = $name", op.pos)

          if words.isEmpty then
            continue = false
            Reporter.error(s"Rhs expected for the operator $name, found none", word.pos)

          else
            val precedence = ExprTyper.precedence(name)
            // infix
            if precedence > precLimit then
              val rhs = parsePrecedenceExpr(words, precedence)
              res = Ast.InfixOperatorCall(res, op, rhs)(res.span | rhs.span)

            else
              words.insert(0, word)
              continue = false

        case word =>
          continue = false
          words.clear
          Reporter.error("An infix operator expected here for precedence expression", word.pos)
      end match
    end while

    res
  end parsePrecedenceExpr
