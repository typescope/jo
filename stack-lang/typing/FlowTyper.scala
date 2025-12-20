package typing

import ast.{ Trees => Ast }
import sast.*
import sast.Trees.*

import Inference.TargetType

import ast.Positions.*
import reporting.Reporter

/** Flow typer for condition expressions
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
  : Word =

    word match
      case isExpr: Ast.IsExpr =>
        namer.transformIsExpr(isExpr)

      case expr: Ast.Expr =>
        namer.exprTyper.transformExpr(expr)

      case infixCall: Ast.InfixCall =>
        infixCall.fun.getKey(Namer.TypedWord) match
          case fun @ Ident(sym) if sym == defn.Bool_and =>
            // Bound variables accumulate for `&&`

            val targetTypeBool = TargetType.Known(defn.BoolType)

            val lhs =
              given TargetType = targetTypeBool
              transformFlow(infixCall.preArgs.head, namer)

            val rhs =
              given TargetType = targetTypeBool
              transformFlow(infixCall.postArgs.head, namer)

            Apply(fun, lhs :: rhs :: Nil, autos = Nil)(infixCall.span).adapt

          case fun @ Ident(sym) if sym == defn.Bool_or =>
            // `||` must bind the same set of variables for both branches
            val snapShot = sc.promotedSet()

            val targetTypeBool = TargetType.Known(defn.BoolType)

            val lhs =
              given TargetType = targetTypeBool
              transformFlow(infixCall.preArgs.head, namer)

            val setLHS = sc.resetPromotedSet(snapShot) -- snapShot

            val rhs =
              given TargetType = targetTypeBool
              transformFlow(infixCall.postArgs.head, namer)

            val setRHS = sc.promotedSet() -- snapShot

            if setLHS != setRHS then
              Reporter.error(
                s"The lhs and rhs bind should bind same set of symbols, found lhs = " + setLHS + ", rhs = " + setRHS,
                infixCall.pos
              )

            Apply(fun, lhs :: rhs :: Nil, autos = Nil)(infixCall.span).adapt

          case fun @ Ident(sym) if sym == defn.Bool_not =>
            // `!` does not change bound variables
            val snapShot = sc.promotedSet()

            val arg =
              given TargetType = TargetType.Known(defn.BoolType)
              transformFlow(infixCall.postArgs.head, namer)

            sc.resetPromotedSet(snapShot)

            Apply(fun, arg :: Nil, autos = Nil)(infixCall.span).adapt

          case _ =>
            given Scope = sc.fresh()
            namer.transformInfixCall(infixCall).adapt

      case Ast.Fence(phrase) =>
        transformFlow(phrase, namer)

      case _ =>
        given Scope = sc.fresh()
        namer.transform(word)
