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


  /** Create a lambda (function object with apply method) from a procedure type
    *
    * @param procType The procedure type for the lambda
    * @param owner The owner symbol
    * @param policy The effect policy
    * @param span The source span
    * @param body Function to generate the body, given parameter and auto idents
    * @return An object with an apply method
    */
  def createLambda
      (procType: ProcType, owner: Symbol, policy: Effects.Policy, span: Span)
      (body: (List[Ident], List[Ident]) => Word)
      (using defn: Definitions, source: Source)
  : Word =
    val pos = span.toPos

    // Create a "this" symbol for the object
    val thisSym = TermSymbol.create("this", Synthetic, Visibility.Default, owner, pos)

    // Create an "apply" method symbol
    val applySym = TermSymbol.create("apply", Fun | Method | Synthetic, Visibility.Default, thisSym, pos)

    // Create parameter symbols for the apply method
    val paramSyms =
      for param <- procType.params yield
        TermSymbol.create(param.name, param.info, Param, Visibility.Default, applySym, pos)

    // Create auto parameter symbols for the apply method
    val autoSyms =
      for auto <- procType.autos yield
        TermSymbol.create(auto.name, auto.info, Context, Visibility.Default, applySym, pos)

    val thisType = ObjectType(NamedInfo("apply", MemberRef(StaticRef(thisSym), applySym)) :: Nil, mutableFields = Nil)
    defn.add(thisSym, thisType)

    // No preParam for methods
    val applyProcType = procType.copy(preParamCount = 0)

    // Build the object type
    val objType = ObjectType(NamedInfo("apply", applyProcType) :: Nil, mutableFields = Nil)

    defn.add(applySym, applyProcType)

    // Generate parameter idents and call the body function
    val paramIdents = paramSyms.map(sym => Ident(sym)(span))
    val autoIdents = autoSyms.map(sym => Ident(sym)(span))
    val bodyWord = body(paramIdents, autoIdents)

    // Create the apply method definition
    val resultTypeTree = TypeTree(procType.resultType)(span.point)
    val candidatesConverted = procType.candidates.map(_.map {
      case sym: Symbol => AutoCandidate.Value(sym)(span)
      case MemberCandidate(tp, name) => AutoCandidate.Member(TypeTree(tp)(span.point), name)(span)
    })
    val funDef = FunDef(
      applySym,
      procType.tparams,
      paramSyms,
      autoSyms,
      candidatesConverted,
      resultTypeTree,
      policy,
      bodyWord
    )(span)

    // Create and return the object
    Object(thisSym, funDef :: Nil)(objType, span)

  /** Eta-expand a function to an object with an apply method
    *
    * Converts: f
    * To: { def apply[T1, ...](arg1: T1, ...): U = f(arg1, ...) }
    */
  def etaExpand(fun: Symbol, owner: Symbol, policy: Effects.Policy, span: Span)(using defn: Definitions, source: Source): Word =
    val procType = fun.info.asProcType

    createLambda(procType, owner, policy, span) { (paramIdents, autoIdents) =>
      // Build the body: call the original function with the parameters
      val funIdent = Ident(fun)(span)

      // Apply type arguments if polymorphic
      val funWithTargs =
        if procType.isPolyType then
          funIdent.appliedToTypes(procType.tparams.map(StaticRef.apply)*)
        else
          funIdent

      // Apply regular and auto arguments
      Apply(funWithTargs, paramIdents, autoIdents)(span)
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
