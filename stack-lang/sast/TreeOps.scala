package sast

import Trees.*
import Symbols.*
import Flags.*
import Types.*

import ast.Positions.{Span, Source}

import scala.collection.mutable

object TreeOps:
  def instantiatePoly(polyType: ProcType, fun: Word)(using Definitions, TypeVars): Word =
    assert(polyType.tparams.nonEmpty, polyType.show)

    val span = fun.span.endPoint
    val tvars = for tparam <- polyType.tparams yield TypeVar(tparam.name, span)
    val targs = tvars.map(tvar => TypeTree(tvar)(span))
    val tpe = polyType.instantiate(tvars)

    TypeApply(fun, targs)(tpe, fun.span)


  /** Create a lambda from a lambda type
    *
    * @param lambdaType The lambda type for the lambda
    * @param owner The owner symbol
    * @param span The source span
    * @param body Function to generate the body, given parameter idents
    * @return A lambda
    */
  def createLambda
      (lambdaType: LambdaType, owner: Symbol, span: Span)
      (body: List[Ident] => Word)
      (using defn: Definitions, source: Source)
  : Word =
    val pos = span.toPos

    // Create a lambda symbol
    val lambdaSym = TermSymbol.create("lambda", Synthetic, Visibility.Default, owner, pos)

    // Create parameter symbols for the lambda (with synthetic names)
    val paramSyms =
      for (paramType, i) <- lambdaType.params.zipWithIndex yield
        TermSymbol.create("p" + i, paramType, Param, Visibility.Default, lambdaSym, pos)

    // Generate parameter idents and call the body function
    val paramIdents = paramSyms.map(sym => Ident(sym)(span))
    val bodyWord = body(paramIdents)

    defn.add(lambdaSym, lambdaType)

    // Create and return the lambda
    Lambda(lambdaSym, paramSyms, lambdaType.receives, bodyWord)(span)

  /** Eta-expand a function to a lambda
    *
    * Converts: f
    * To: (arg1: T1, ...) => f(arg1, ...)
    */
  def etaExpand(fun: Symbol, owner: Symbol, receives: List[Symbol], span: Span)(using defn: Definitions, source: Source): Word =
    val procType = fun.info.asProcType

    assert(procType.autos.isEmpty, "Autos not supported in etaExpand: " + fun)

    // Create lambda type from function's parameter and result types
    val lambdaType = LambdaType(procType.params.map(_.info), procType.resultType, receives)

    createLambda(lambdaType, owner, span) { paramIdents =>
      // Build the body: call the original function with the parameters
      val funIdent = Ident(fun)(span)

      // Apply type arguments if polymorphic
      val funWithTargs =
        if procType.isPolyType then
          funIdent.appliedToTypes(procType.tparams.map(StaticRef.apply)*)
        else
          funIdent

      // Apply regular arguments (auto arguments will be resolved at call site)
      Apply(funWithTargs, paramIdents, Nil)(span)
    }

  /** Returns (locals, free) */
  def variableCensus(fdef: FunDef)(using Definitions): (List[Symbol], List[Symbol]) =
    val census = new VariableCensus
    census(fdef.body)(using ())
    val locals = census.locals.distinct.toList
    val masked = fdef.allParams ++ locals
    val free = census.free.filter(sym => !masked.contains(sym)).distinct.toList
    (locals.filter(_.info.isValueType), free)

  /** Returns free  */
  def freeReferences(lam: Lambda)(using Definitions): List[Symbol] =
    val census = new VariableCensus
    census(lam.body)(using ())
    val locals = census.locals.distinct.toList
    val masked = lam.params ++ locals
    census.free.filter(sym => !masked.contains(sym)).distinct.toList

  /** The census should not depend on Symbol.owner as they are inaccurate after
    * closure conversion and class encoding.
    */
  class VariableCensus(using Definitions) extends TreeTraverser:
    val locals = new mutable.ArrayBuffer[Symbol]
    val free = new mutable.ArrayBuffer[Symbol]

    type Context = Unit

    override def apply(pat: Pattern)(using Context): Unit =
      pat match
        case BindPattern(id, nested) =>
          locals += id.symbol
          this(nested)

        case SeqPattern(pats) =>
          pats.foreach:
            case AtomPattern(pattern) => this(pattern)

            case SkipToPattern(pattern) => this(pattern)

            case RestPattern(pattern) => this(pattern)

            case star @ StarPattern(pattern) =>
              locals ++= star.bindings.map(_._1)
              this(pattern)

        case _ =>
          recur(pat)

    def apply(word: Word)(using Context): Unit =
      word match
        case Ident(sym) =>
          // can be a global name
          free += sym

        case ValDef(sym, rhs) =>
          if !sym.isField then locals += sym
          this(rhs)

        case Assign(Ident(sym), rhs) =>
          locals += sym
          this(rhs)

        case obj: Object =>
          locals += obj.self
          recur(obj)

        case Lambda(symbol, params, receives, body) =>
          locals ++= params
          this(body)

        case fdef: FunDef =>
          locals += fdef.symbol
          free ++= fdef.freeVariables

        case _ => recur(word)
