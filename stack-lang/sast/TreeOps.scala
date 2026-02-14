package sast

import Trees.*
import Symbols.*
import Types.*

import ast.Positions.{Span, Source}

import scala.collection.mutable

object TreeOps:
  def instantiatePoly(polyType: ProcType, fun: Word)(using Definitions, TypeVars): Word =
    assert(polyType.tparams.nonEmpty, polyType.show)

    val span = fun.span.endPoint
    val tvars = for tparam <- polyType.tparams yield TypeVar(tparam.name, span)
    val targs = tvars.map(tvar => TypeTree(tvar)(span))

    TypeApply(fun, targs)(fun.span)

  /** Create a partial Apply for an extension method call.
    *
    * Called after member resolution has already validated the member exists.
    */
  def createExtensionApply(sym: Symbol, qual: Word, span: Span)(using Definitions): Word =
    var fun: Word = Ident(sym)(span)
    val procType = sym.info.asProcType
    if procType.preTypeParamCount > 0 then
      val solver = new UnificationSolver
      val preTargs =
        given TypeVars = solver
        val tvars = procType.preTparams.map(tparam => TypeVar(tparam.name, span))
        val proc = procType.instantiatePreTypeParams(tvars)
        val preParamType = proc.preParamTypes.head
        Subtyping.conforms(qual.tpe.widen, preParamType)
        assert(
          tvars.forall(solver.isInstantiated),
          s"extension header type params not fully inferred for ${sym.name}: ${procType.preTparams.map(_.name)}"
        )
        tvars.map(tvar => TypeTree(tvar.instantiated)(span))
      fun = TypeApply(fun, preTargs)(span)

    Apply(fun, List(qual), Nil)(span)

  /** Smart member selection: handles extension methods by creating a partial Apply,
    * falls back to plain Select for regular members.
    */
  def smartSelect(word: Word, name: String, span: Span)(using Definitions): Word =
    assert(word.tpe.isValueType, "smartSelect requires value type, got: " + word.tpe)
    word.tpe.getTermMember(name) match
      case Some(StaticRef(sym)) if sym.isExtensionMethod =>
        createExtensionApply(sym, word, span)
      case _ =>
        Select(word, name)(span)

  /** Smart constructor for Apply that flattens partial extension method applications.
    *
    * When `fun` is a partial Apply (extension method with pre-args applied, tpe is ProcType),
    * flattens: smartApply(Apply(f, preArgs, []), postArgs, autos) => Apply(f, preArgs ++ postArgs, autos)
    *
    * Also handles a partial Apply wrapped by TypeApply for method type arguments:
    * smartApply(TypeApply(Apply(f, preArgs, []), targs), postArgs, autos)
    *   => Apply(TypeApply(f, targs), preArgs ++ postArgs, autos)
    *
    * Current code handles up to two nested partial levels, which matches existing
    * extension method call shapes.
    *
    * When `fun` is already fully applied (not a ProcType) and there are no args/autos,
    * returns `fun` as-is.
    */
  def smartApply(fun: Word, args: List[Word], autos: List[Word])(span: Span)(using Definitions): Word =
    fun match
      // Level 1: partial apply
      case partial1 @ Apply(innerFun1, preArgs1, Nil) if partial1.tpe.is[ProcType] =>
        Apply(innerFun1, preArgs1 ++ args, autos)(span)

      // Level 1: type-apply(partial apply)
      case TypeApply(partial1 @ Apply(innerFun1, preArgs1, Nil), targs1) if partial1.tpe.is[ProcType] =>
        // Level 2: nested type-apply(partial apply)
        innerFun1 match
          case TypeApply(innerFun2, targs2) =>
            val typed = TypeApply(innerFun2, targs2 ++ targs1)(fun.span)
            Apply(typed, preArgs1 ++ args, autos)(span)

          case _ =>
            Apply(TypeApply(innerFun1, targs1)(fun.span), preArgs1 ++ args, autos)(span)

      case _ =>
        if args.isEmpty && autos.isEmpty && !fun.tpe.isInvokableType then fun
        else Apply(fun, args, autos)(span)

  /** Create a lambda from a lambda type
    *
    * @param lambdaType The lambda type for the lambda
    * @param lambdaSym The symbol for the lambda
    * @param span The source span
    * @param body Function to generate the body, given parameter idents
    * @return A lambda
    */
  def createLambdaWithSymbol
      (lambdaSym: Symbol, lambdaType: LambdaType, span: Span)
      (body: List[Ident] => Word)
      (using defn: Definitions, source: Source)
  : Word =
    val pos = span.toPos

    // Create parameter symbols for the lambda (with synthetic names)
    val paramSyms =
      for (paramType, i) <- lambdaType.params.zipWithIndex yield
        TermSymbol.create("p" + i, paramType, Flags.Param, Visibility.Default, lambdaSym, pos)

    // Generate parameter idents and call the body function
    val paramIdents = paramSyms.map(sym => Ident(sym)(span))
    val bodyWord = body(paramIdents)

    // Create and return the lambda
    val res = Lambda(lambdaSym, paramSyms, lambdaType.receives, bodyWord)(span)

    defn.add(lambdaSym, res.tpe)

    res

  def createLambda
      (lambdaType: LambdaType, owner: Symbol, span: Span)
      (body: List[Ident] => Word)
      (using defn: Definitions, source: Source)
  : Word =
    // Create a lambda symbol
    val lambdaSym = TermSymbol.create("lambda", Flags.Fun | Flags.Synthetic, Visibility.Default, owner, span.toPos)
    createLambdaWithSymbol(lambdaSym, lambdaType, span)(body)

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

            case RepeatPattern(bind, guard) =>
              bind.foreach:
                case sym: Symbol => locals += sym
                case Ident(_) =>
              guard.foreach(this.apply)

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

        case lam: Lambda =>
          free ++= freeReferences(lam)

        case fdef: FunDef =>
          locals += fdef.symbol
          free ++= fdef.freeVariables

        case _ => recur(word)
