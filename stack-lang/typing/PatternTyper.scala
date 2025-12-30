package typing

import ast.{ Trees => Ast }
import ast.Positions.*
import ast.Naming

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import reporting.Reporter
import reporting.Diagnostics

import Inference.TargetType
import PatternTyper.{ ShadowedPatternError, RemainingSlice, SkipTo }

import scala.collection.mutable

class PatternTyper(namer: Namer):
  def transformPatDef(patDef: Ast.PatDef)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, checks: Checks)
  : DelayedDef[PatDef] =

    given Definitions = lazyDefn.value

    val flags = Checker.checkModifiers(patDef) | Flags.Fun

    val patSym = PatternSymbol.create(patDef.name, flags, Checker.visibility(patDef, sc.owner), sc.owner, patDef.ident.pos)
    given patScope: Scope = sc.fresh(patSym)

    lazy val tparamSyms = namer.transformTypeParams(patDef.tparams)

    lazy val paramSyms =
      tparamSyms
      for param <- patDef.params yield
        val tpt = namer.transformType(param.tpt)
        val paramSym = PatternSymbol.create(param.name, tpt.tpe, Flags.Param, Visibility.Default, patSym, param.pos)
        paramSym

    lazy val resultTypeTree =
      assert(!patDef.resultType.isEmpty, "result type of pattern predicates is mandatory")

      tparamSyms
      namer.transformType(patDef.resultType)

    lazy val typedBody =
      paramSyms
      val scrutType = resultTypeTree.tpe.stripPartial

      val reporterTemp = rp.fresh(buffer = true)

      val patterns =
        given Reporter = reporterTemp
        for Ast.Case(pattern, _) <- patDef.cases yield
          given flowScope: FlowScope = new FlowScope(patScope)
          paramSyms.foreach { param => flowScope.define(param) }
          val patternTyped = Inference.freshIsolate:
            transformPattern(pattern, scrutType)

          if !reporterTemp.hasErrors then
            for
              paramSym <- paramSyms if !flowScope.isPromoted(paramSym)
            do
              Reporter.error(s"The parameter $paramSym is not bound in the patterns", pattern.pos)

          patternTyped

      // Elide checks if other errors are present
      if !reporterTemp.hasErrors then
        checkExhaustivity(patterns, resultTypeTree)

      reporterTemp.commit(rp)


      if patterns.isEmpty then
        Reporter.error("Expect case patterns, found none", patDef.pos)
        WildcardPattern()(ErrorType, patDef.span)

      else
        patterns.tail.foldLeft(patterns.head): (acc, pat) =>
          OrPattern(acc, pat)(scrutType)

    def computeInfo(resultType: Type) =
      val autoTypes = Nil
      ProcType(tparamSyms, paramSyms.map(_.toNamedInfo), autoTypes, Nil, resultType, () => Nil, patDef.preParamCount)

    val ip = lazyDefn.infoProvider
    ip.addLazy(patSym, () => computeInfo(resultTypeTree.tpe), () => computeInfo(ErrorType))

    val typer = () =>
      PatDef(patSym, tparamSyms, paramSyms, resultTypeTree, typedBody)(patDef.span)

    DelayedDef(patSym, typer)

  private def checkExhaustivity(patterns: List[Pattern], coveredTypeTree: TypeTree)
      (using defn: Definitions, rp: Reporter, so: Source): Unit =

    import Exhaustivity.Space
    val coveredType = coveredTypeTree.tpe
    val isPartial = coveredType.isPartial
    val scrutSpace = Space.TypeSpace(coveredType.stripPartial)

    var rest = scrutSpace
    for pattern <- patterns do
      val space = Exhaustivity.project(pattern)
      if Exhaustivity.isDisjoint(rest, space) then
        Reporter.warn("The case is not reachable", pattern.pos)
      else
        rest = Exhaustivity.subtract(rest, space)
    end for

    val cases = Exhaustivity.flatten(rest)
    if !cases.isEmpty && !isPartial then
      val five = cases.take(5)
      val examples = five.map(_.show).mkString(", ")
      val explain = "An inexhaustive pattern can be specified by Partial[" + coveredType.show + "]."
      val word = if five.size > 1 then "cases" else "case"
      Reporter.warn(s"The match will fail for the $word: " + examples + ". " + explain, coveredTypeTree.pos)

    else if cases.isEmpty && isPartial then
      Reporter.warn(s"The match is exhaustive. There is no need to mark the type with `Partial`.", coveredTypeTree.pos)

  def transformMatch(patmat: Ast.Match)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word =

    val Ast.Match(scrutinee, cases) = patmat
    val scrutinee2 =
      given TargetType = TargetType.ValueType
      Inference.freshIsolate:
        namer.transform(scrutinee)

    val scrutType = scrutinee2.tpe.widenTermRef

    val rp2: Reporter = rp.fresh(buffer = true)
    val cases2 =
      for caseDef <- cases yield
        given Reporter = rp2
        transformCase(caseDef, scrutType)

    val commonType = (cases2: @unchecked) match
      case caseDef :: rest =>
        rest.foldLeft(caseDef.body.tpe): (acc, item) =>
          Checker.commonResultType(acc, item.body.tpe, item.body.pos)

    val patmat2 = Match(scrutinee2, cases2)(commonType, patmat.span)

    // Skip the check if there are errors in patterns
    if !rp2.hasErrors then
      checkExhaustivity(patmat2)

    // may contain warnings
    rp2.commit(rp)

    patmat2

  def transformCaseDef(caseDef: Ast.CaseDef)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word =
    val Ast.CaseDef(pat, rhs) = caseDef

    // Type the rhs expression first
    val rhs2 =
      given TargetType = TargetType.ValueType
      Inference.freshIsolate:
        namer.transform(rhs)

    val rhsType = rhs2.tpe.widen

    // Create a flow scope for pattern matching
    given flowScope: FlowScope = new FlowScope(sc)

    // Transform the pattern
    val rp2 = rp.fresh(buffer = true)
    val pat2 =
      given Reporter = rp2
      Inference.freshIsolate:
        transformPattern(pat, rhsType)

    if rp2.hasErrors then
      rp2.commit(rp)
      errorWord(caseDef.span)
    else
      // Check exhaustivity of the pattern
      checkCaseDefExhaustivity(pat2, rhsType, rhs2.pos)

      // Define pattern bindings in the current scope
      // The bindings from the pattern should be available in subsequent code
      for sym <- flowScope.promotedSet() do
        sc.definePatternAsTerm(sym)

      rp2.commit(rp)
      CaseDef(pat2, rhs2)(caseDef.span)

  private def checkExhaustivity(patmat: Match)(using Definitions, Reporter, Source): Unit =
    import Exhaustivity.Space
    var rest = Space.TypeSpace(patmat.scrutinee.tpe.widenTermRef)
    for Case(pat, _) <- patmat.cases do
      val space = Exhaustivity.project(pat)
      if Exhaustivity.isDisjoint(rest, space) then
        Reporter.warn("The case is not reachable", pat.pos)
      else
        rest = Exhaustivity.subtract(rest, space)
    end for

    val cases = Exhaustivity.flatten(rest)
    if !cases.isEmpty then
      val five = cases.take(5)
      val examples = five.map(_.show).mkString(", ")
      val word = if five.size > 1 then "cases" else "case"
      Reporter.warn(s"The match will fail for the $word: " + examples, patmat.scrutinee.pos)

  private def checkCaseDefExhaustivity(pattern: Pattern, rhsType: Type, rhsPos: SourcePosition)
      (using Definitions, Reporter, Source): Unit =
    import Exhaustivity.Space
    val typeSpace = Space.TypeSpace(rhsType.widenTermRef)
    val patternSpace = Exhaustivity.project(pattern)
    val rest = Exhaustivity.subtract(typeSpace, patternSpace)

    val cases = Exhaustivity.flatten(rest)
    if !cases.isEmpty then
      val five = cases.take(5)
      val examples = five.map(_.show).mkString(", ")
      val word = if five.size > 1 then "cases" else "case"
      Reporter.warn(s"The case definition will fail for the $word: " + examples, rhsPos)

  private def transformCase(caseDef: Ast.Case, scrutType: Type)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Case =
    val Ast.Case(pat, body) = caseDef

    given flowScope: FlowScope = new FlowScope(sc)

    val rp2 = rp.fresh(buffer = true)
    val pat2 =
      given Reporter = rp2
      Inference.freshIsolate:
        transformPattern(pat, scrutType)

    val body2 =
      if rp2.hasErrors then
        errorWord(body.span)

      else
        given Scope = flowScope.fresh()
        namer.transform(body)

    // may contain warnings
    rp2.commit(rp)

    Case(pat2, body2)(caseDef.span)

  private def transformApplyPattern(
      id: Ast.RefTree, args: List[Ast.Pattern], scrutType: Type, patSpan: Span)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tvars: TypeVars)
  : Pattern =

    val sym = resolvePatternPredicate(id)

    var fun: Word = Ident(sym)(id.span)

    if fun.tpe.isProcType then
      if fun.tpe.isPolyType then
        fun = TreeOps.instantiatePoly(fun.tpe.asProcType, fun)

      val funType = fun.tpe

      val procType = funType.asProcType
      val paramSize = procType.paramTypes.size
      val resType = procType.resultType.stripPartial

      val explain = new StringBuilder
      if Patterns.isValidTypePattern(resType, scrutType)(using explain) then
        if !Subtyping.conforms(resType, scrutType) && !Subtyping.conforms(scrutType, resType) then
          Reporter.error(s"The pattern has different type from the scrutinee type, scrutinee = ${scrutType.show}, pattern = ${resType.show}", id.pos)

        if args.size != paramSize then
          Reporter.error(s"The pattern predicate expects $paramSize arguments, found = ${args.size}", id.pos)
          WildcardPattern()(ErrorType, patSpan)

        else
          if sym == defn.Predef_orPattern then
            assert(args.size == 2, "args.size = " + args.size)
            transformOrPattern(args(0), args(1), scrutType, patSpan)

          else if sym == defn.Predef_andPattern then
            assert(args.size == 2, "args.size = " + args.size)
            transformAndPattern(args(0), args(1), scrutType)

          else
            val argsTyped =
              for (arg, paramType) <- args.zip(procType.paramTypes) yield
                transformPattern(arg, paramType)

            ApplyPattern(fun, argsTyped)(resType, patSpan)

        end if
      else
        if !scrutType.isError then
          Reporter.error(s"The pattern result type ${resType.show} is invalid with respect to the scrutinee type ${scrutType.show}. " + explain, id.pos)
        WildcardPattern()(ErrorType, patSpan)

    else
      if !fun.tpe.isError then
        Reporter.error(s"Not a pattern predicate: " + fun.tpe.show, id.pos)

      WildcardPattern()(ErrorType, patSpan)


  private def transformOrPattern(lhs: Ast.Pattern, rhs: Ast.Pattern, scrutType: Type, patSpan: Span)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tvars: TypeVars)
  : Pattern =
    given rp2: Reporter = rp.fresh(buffer = true)

    val snapShot = sc.promotedSet()

    val lhsPat = transformPattern(lhs, scrutType)

    // reset promoted set
    val setLHS = sc.resetPromotedSet(snapShot) -- snapShot

    val rhsPat = transformPattern(rhs, scrutType)

    val setRHS = sc.promotedSet() -- snapShot

    if !rp2.hasErrors && setLHS != setRHS then
      Reporter.error(
        s"The lhs and rhs bind should bind same set of symbols, found lhs = " + setLHS + ", rhs = " + setRHS,
        patSpan.toPos
      )

    // may contain warnings
    rp2.commit(rp)

    val valueType =
      given TargetType = TargetType.Unknown
      Inference.commonResultType(lhsPat.valueType, rhsPat.valueType) match
        case Some(tpe) => tpe
        case None => scrutType

    OrPattern(lhsPat, rhsPat)(valueType)

  private def transformAndPattern(lhs: Ast.Pattern, rhs: Ast.Pattern, scrutType: Type)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tvars: TypeVars)
  : Pattern =
    val lhsPat = transformPattern(lhs, scrutType)
    val rhsPat = transformPattern(rhs, scrutType)

    val valueType =
      given TargetType = TargetType.Unknown
      Inference.commonResultType(lhsPat.valueType, rhsPat.valueType) match
        case Some(tpe) => tpe
        case None => scrutType

    AndPattern(lhsPat, rhsPat)(valueType)

  private def transformTypePattern(
      id: Ast.Ident, tpt: Ast.TypeTree, scrutType: Type, patSpan: Span)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source)
  : Pattern =

    val name = id.name
    val tpt2 = Checks.eager:
      given Scope = sc.outer
      namer.transformType(tpt)

    val tpe = tpt2.tpe

    val pattern =
      if name == "_" then
        TypePattern(tpt2)(scrutType)

      else
        sc.resolvePattern(name) match
          case Some(sym) =>
            sc.promote(sym, id.pos)

            if Subtyping.conforms(tpe, sym.info) then
              val patVal = Ident(sym)(id.span)
              BindPattern(patVal, TypePattern(tpt2)(scrutType))(isDef = false)

            else
              Reporter.error(s"The type ${tpe.show} not a equal to the type of $sym. The latter has type " + sym.info.show, tpt.pos)
              WildcardPattern()(ErrorType, patSpan)

          case None =>
            val sym = PatternSymbol.create(name, tpe, Flags.empty, Visibility.Default, sc.owner, id.pos)
            sc.define(sym)
            sc.promote(sym, id.pos)

            val patVal = Ident(sym)(id.span)
            BindPattern(patVal, TypePattern(tpt2)(scrutType))(isDef = true)
        end match
      end if

    val explain = new StringBuilder
    if Patterns.isValidTypePattern(tpe, scrutType)(using explain) then
      pattern
    else
      Reporter.error(explain.toString, tpt.pos)
      WildcardPattern()(ErrorType, patSpan)

  private def transformIdentPattern(id: Ast.Ident, scrutType: Type)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tvars: TypeVars)
  : Pattern =

    val name = id.name
    if id.isCapitalized then
      transformApplyPattern(id, Nil, scrutType, id.span)

    else if name == "_" then
      WildcardPattern()(scrutType, id.span)

    else
      sc.resolvePattern(name) match
        case Some(sym) =>
          sc.promote(sym, id.pos)

          if Subtyping.conforms(scrutType, sym.info) then
            val patVal = Ident(sym)(id.span)
            BindPattern(patVal, WildcardPattern()(sym.info, id.span.endPoint))(isDef = false)

          else
            Reporter.error(s"$sym has the type ${sym.info.show}, which is not equal to the scrutinee type " + scrutType.show, id.pos)
            WildcardPattern()(ErrorType, id.span)

        case None =>
          val sym = PatternSymbol.create(name, scrutType, Flags.empty, Visibility.Default, sc.owner, id.pos)
          sc.promote(sym, id.pos)
          sc.define(sym)

          val patVal = Ident(sym)(id.span)
          val wildcard = WildcardPattern()(scrutType, id.span.endPoint)
          BindPattern(patVal, wildcard)(isDef = true)

  private def transformBindPattern(id: Ast.Ident, nested: Ast.Pattern, scrutType: Type)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tvars: TypeVars)
  : Pattern =

    val name = id.name
    if name == "_" then
      // TODO: add test
      transformPattern(nested, scrutType)

    else
      sc.resolvePattern(name) match
        case Some(sym) =>
          sc.promote(sym, id.pos)

          val nestedPattern = transformPattern(nested, scrutType)

          if Subtyping.conforms(nestedPattern.valueType, sym.info) then
            val patVal = Ident(sym)(id.span)
            BindPattern(patVal, nestedPattern)(isDef = false)

          else
            Reporter.error(s"$sym has the type ${sym.info.show}, which is not equal to the scrutinee type " + scrutType.show, id.pos)
            WildcardPattern()(ErrorType, id.span)

        case None =>
          val nestedPattern = transformPattern(nested, scrutType)
          val sym = PatternSymbol.create(name, nestedPattern.valueType, Flags.empty, Visibility.Default, sc.owner, id.pos)
          sc.promote(sym, id.pos)
          sc.define(sym)

          val patVal = Ident(sym)(id.span)
          BindPattern(patVal, nestedPattern)(isDef = true)

  private def transformExprPattern(expr: Ast.ExprPattern, scrutType: Type)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tvars: TypeVars)
  : Pattern =

    expr.patterns match
      case head :: Nil =>
        transformPattern(head, scrutType)

      case patterns =>
        val isOperatorExpr = patterns.exists:
          case Ast.Ident(name) if Naming.isOperator(name) => true
          case _ => false

        if isOperatorExpr then transformOperatorExprPattern(patterns, scrutType)
        else transformShapeExprPattern(patterns, scrutType)

  private def transformOperatorExprPattern(patterns: List[Ast.Pattern], scrutType: Type)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tvars: TypeVars)
  : Pattern =

    val handler = new ExprTyper.OperatorHandler[Ast.Pattern]:
      def prefix(binder: Ast.Ident, rhs: Ast.Pattern): Ast.Pattern =
        Ast.ApplyPattern(binder, rhs :: Nil)(binder.span | rhs.span)

      def infix(lhs: Ast.Pattern, binder: Ast.Ident, rhs: Ast.Pattern): Ast.Pattern =
        Ast.ApplyPattern(binder, lhs :: rhs :: Nil)(lhs.span | rhs.span)

      def error(span: Span): Ast.Pattern = Ast.Ident("_")(span)

    val words = mutable.ListBuffer.from(patterns)
    val word = namer.exprTyper.parseOperatorExpr(words, handler)
    transformPattern(word, scrutType)

  private def transformShapeExprPattern(patterns: List[Ast.Pattern], scrutType: Type)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tvars: TypeVars)
  : Pattern =
    // mixed prefix/infix/postfix pattern, arity depends on type of the function
    val patternList: mutable.ListBuffer[Ast.Pattern] = mutable.ListBuffer.from(patterns)

    val resolveProc = new ExprTyper.ShapeHandler[Ast.Pattern, Ast.RefTree]:
      def bundle(preArgs: List[Ast.Pattern], binder: Ast.RefTree, postArgs: List[Ast.Pattern]): Ast.Pattern =
        val startSpan = if preArgs.isEmpty then binder.span else preArgs.head.span
        val endSpan = if postArgs.isEmpty then binder.span else postArgs.last.span
        Ast.ApplyPattern(binder, preArgs ++ postArgs)(startSpan | endSpan)

      def resolveShape(tpt: Ast.Pattern): Option[ExprTyper.Shape[Ast.RefTree]] =
        tpt match
          case id: Ast.RefTree =>
            // Ignore errors in resolution
            given tempReporter: Reporter = rp.fresh(buffer = true)
            // resolveQuliad requires a normal scope
            given Scope = sc.outer
            namer.resolveQualid(id, Universe.Pattern) match
              case Some(sym) if sym.is(Flags.Fun) =>
                val procType = sym.info.asProcType
                // parameterless predicates should not interfere with expression typing
                if procType.params.isEmpty then
                  None
                else
                  val shape = ExprTyper.Shape(id, procType.preParamCount, procType.postParamCount)
                  Some(shape)

              case _ =>
                // Report errors for selection -- selection must be a predicate
                if id.isInstanceOf[Ast.Select] then
                  tempReporter.commit(rp)

                None

          case _ =>
            None

    val values = namer.exprTyper.parseShapeExpr(patternList, resolveProc)

    assert(patternList.isEmpty, patternList)
    if values.size > 1 then
      val rest = values.init
      val span = rest.head.span | rest.last.span
      Reporter.error("Found extra pattern, an expression pattern should form a single pattern", span.toPos)

    transformPattern(values.last, scrutType)
  end transformShapeExprPattern

  private def transformSeqPattern(seq: Ast.SequencePattern, scrutType: Type)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tvars: TypeVars)
  : Pattern =

    val tvar = TypeVar("T", seq.span)

    val members = Map(
      "size" -> ProcType(
        tparams = Nil,
        params = Nil,
        autos = Nil,
        candidates = Nil,
        resultType = defn.IntType,
        receivesInfo = () => Nil,
        preParamCount = 0
      ),

      "get" -> ProcType(
        tparams = Nil,
        params = NamedInfo("i", defn.IntType) :: Nil,
        autos = Nil,
        candidates = Nil,
        resultType = tvar,
        receivesInfo = () => Nil,
        preParamCount = 0
      ),

      "slice" -> ProcType(
        tparams = Nil,
        params = NamedInfo("from", defn.IntType) :: NamedInfo("to", defn.IntType)  :: Nil,
        autos = Nil,
        candidates = Nil,
        resultType = scrutType.widenTermRef,
        receivesInfo = () => Nil,
        preParamCount = 0
      ),
    )

    def memberConforms(name: String) =
      scrutType.getTermMember(name) match
        case Some(tp) if tp.isProcType =>
          val tp1 = tp.asProcType
          val tp2 = members(name)
          Subtyping.conforms(tp1, tp2)

        case _ => false


    val sizeConforms = memberConforms("size")
    val getConforms = memberConforms("get")
    val sliceConforms = memberConforms("slice")

    val signatureConforms = sizeConforms && getConforms && sliceConforms

    if signatureConforms then
      if !tvar.isInstantiated then
        Reporter.error("Tvar is not instantiated", seq.pos)
        WildcardPattern()(ErrorType, seq.span)

      else
        val partPatterns = new mutable.ArrayBuffer[SeqPartPattern]
        val itemType = tvar.instantiated

        val tempReporter = rp.fresh(buffer = true)

        for pat <- seq.patterns do
          given Reporter = tempReporter
          pat match
            case SkipTo(nested) =>
              val inner = transformPattern(nested, itemType)
              partPatterns += SkipToPattern(inner)(pat.span)

            case Ast.ExprPattern(nested :: Ast.Ident("*") :: Nil) =>
              partPatterns += transformStarPattern(nested, itemType, pat)

            case RemainingSlice(nested) =>
              if pat `ne` seq.patterns.last then
                Reporter.error(".. may only be used in the last position of a sequence pattern", pat.pos)

              else
                val inner = transformPattern(nested, scrutType)
                partPatterns += RestPattern(inner)(pat.span)

            case expr: Ast.ExprPattern =>
              Reporter.error("Unrecognized sequence pattern. Do you forget parenthesis for the nested item pattern?", expr.pos)

            case pat =>
              val pattern = transformPattern(pat, itemType)
              partPatterns += AtomPattern(pattern)
          end match
        end for

        val seqPattern = SeqPattern(partPatterns.toList)(scrutType, seq.span)

        if !tempReporter.hasErrors then
          // check determinism of patterns
          var i = 0
          val size = seqPattern.patternCount
          // the last one is always deterministic
          while i < size - 1 && !seqPattern.distanceToEnd(i).isExact do
            val pat = seqPattern(i)
            pat match
              case StarPattern(itemPat) =>
                val next = seqPattern(i + 1) // i never points to the last
                val headPattern = next.headPattern

                val space1 = Exhaustivity.project(itemPat)
                val space2 = Exhaustivity.project(headPattern)
                val reachableSpace = Exhaustivity.subtract(space2, space1)
                if Exhaustivity.isEmpty(reachableSpace) then
                  rp.report(ShadowedPatternError(itemPat, headPattern))

              case _ =>
            end match
            i = i + 1
          end while
        end if

        // may contain warnings
        tempReporter.commit(rp)
        seqPattern

    else
      if !sizeConforms then
        Reporter.error("The scrutinee does not have a method `def size: Int` to support sequence pattern", seq.pos)

      if !getConforms then
        Reporter.error("The scrutinee does not have a method `def get(i: Int): T` to support sequence pattern", seq.pos)

      if !sliceConforms then
        Reporter.error("The scrutinee does not have a method `def slice(from: Int, to: Int)` to support sequence pattern", seq.pos)

      WildcardPattern()(ErrorType, seq.span)

  private def transformStarPattern(nested: Ast.Pattern, itemType: Type, pat: Ast.Pattern)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tvars: TypeVars)
  : SeqPartPattern =

    // For star patterns, inner pattern has no access to outer locally introduced pattern variables
    val innerFlowScope: FlowScope = new FlowScope(sc.fresh())

    val inner =
      given FlowScope = innerFlowScope
      transformPattern(nested, itemType)

    val bindings = new mutable.ArrayBuffer[(Symbol, Symbol)]
    for
      innerSym <- innerFlowScope.promotedSet()
      if !innerSym.name.startsWith("_")
    do
      val pos = innerSym.sourcePos

      val expectedType = AppliedType(defn.List_type, innerSym.info :: Nil)

      // first check if there is a pattern variable of the same name exists
      sc.resolvePattern(innerSym.name) match
        case Some(outerSym) =>
          sc.promote(outerSym, pos)

          if Subtyping.conforms(outerSym.info, expectedType) then
            bindings += outerSym -> innerSym

          else
            Reporter.error(s"$outerSym has the type ${outerSym.info.show}, which does not conform to the type " + expectedType.show, pos)

        case None =>
          // It is OK to not set Flags.Mutable because after initialization it cannot be changed.
          val outerSym = PatternSymbol.create(innerSym.name, expectedType, Flags.empty, Visibility.Default, sc.owner, pos)
          sc.promote(outerSym, pos)
          sc.define(outerSym)
          bindings += outerSym -> innerSym
      end match


    StarPattern(inner)(pat.span, bindings.toList)

  private def resolvePatternPredicate(id: Ast.RefTree)(using sc: FlowScope, rp: Reporter, so: Source, defn: Definitions): Symbol =
    // resolveQuliad requires a normal scope
    given Scope = sc.outer
    namer.resolveQualid(id, Universe.Pattern) match
      case Some(sym) =>
        if sym.is(Flags.Fun) then
          sym

        else
          Reporter.error(s"A pattern predicate expected, found = " + sym, id.pos)
          PatternSymbol.create(id.name, ErrorType, Flags.Synthetic, Visibility.Default, sc.owner, id.pos)

      case None =>
        id match
          case id: Ast.Ident =>
            Reporter.error(s"Undefined pattern name " + id.name, id.pos)

          case _ =>
            // error already reported

        PatternSymbol.create(id.name, ErrorType, Flags.Synthetic, Visibility.Default, sc.owner, id.pos)

  private def transformAssignPattern(
      basePat: Ast.Pattern, assignments: List[(Ast.Ident, Ast.Word)], scrutType: Type)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tvars: TypeVars)
  : Pattern =

    // Transform the base pattern
    val basePat2 = transformPattern(basePat, scrutType)

    // Transform each assignment: evaluate expression and bind to parameter symbol
    val assignments2 = assignments.map { case (id, expr) =>
      // Look up the pattern parameter symbol from scope
      val sym = sc.resolvePattern(id.name) match
        case Some(sym) if !sym.is(Flags.Fun) =>
          sc.promote(sym, id.pos)
          sym

        case _ =>
          Reporter.error(s"Pattern variable ${id.name} not found", id.pos)
          PatternSymbol.create(id.name, ErrorType, Flags.Param, Visibility.Default, sc.owner, id.pos)

      // Evaluate the expression
      val expr2 =
        given TargetType =
          if sym.info.isError then TargetType.ValueType
          else TargetType.Known(sym.info)

        given Scope = sc.fresh()
        namer.transform(expr)

      val idTree = Ident(sym)(id.span)

      Assign(idTree, expr2)
    }

    // Desugar to AndPattern(basePat, AssignPattern(assignments))
    val assignPat = AssignPattern(assignments2)(scrutType)
    AndPattern(basePat2, assignPat)(scrutType)

  def transformPattern(
      pat: Ast.Pattern, scrutType: Type)
      (using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, tvars: TypeVars)
  : Pattern =

    pat match
      // RefTree (Ident, Select) - variable patterns or constructor names
      case id: Ast.Ident =>
        transformIdentPattern(id, scrutType)

      case ref: Ast.Select =>
        transformApplyPattern(ref, args = Nil, scrutType, pat.span)

      // LiteralPattern
      case Ast.LiteralPattern(value) =>
        val literal =
          given Scope = sc.outer
          given TargetType = TargetType.Known(scrutType)
          namer.transform(value)

        ValuePattern(literal)(scrutType)

      // TypePattern: x: Type
      case Ast.TypePattern(id, tpt) =>
        transformTypePattern(id, tpt, scrutType, pat.span)

      // BindPattern: x @ pattern
      case Ast.BindPattern(id, nested) =>
        transformBindPattern(id, nested, scrutType)

      // ApplyPattern: Constructor(args)
      case Ast.ApplyPattern(ref, args) =>
        transformApplyPattern(ref, args, scrutType, pat.span)

      // SequencePattern: [p1, p2, ...]
      case seq: Ast.SequencePattern =>
        transformSeqPattern(seq, scrutType)

      // GuardPattern: pattern if condition
      case Ast.GuardPattern(pattern, guard) =>
        val pattern2 = transformPattern(pattern, scrutType)

        val guard2 =
          given TargetType = TargetType.Known(defn.BoolType)
          FlowTyper.transformFlow(guard, namer)

        val guardPat = GuardPattern(guard2)(scrutType)
        AndPattern(pattern2, guardPat)(scrutType)

      // ExprPattern: p1 p2 p3 (infix operators)
      case expr: Ast.ExprPattern =>
        transformExprPattern(expr, scrutType)

      // AssignPattern: pattern with x = expr, y = expr2
      case Ast.AssignPattern(pattern, assignments) =>
        transformAssignPattern(pattern, assignments, scrutType)

    end match
  end transformPattern

end PatternTyper

object PatternTyper:
  object RemainingSlice:
    def unapply(pat: Ast.Pattern): Option[Ast.Pattern] =
      pat match
        case Ast.ExprPattern(Ast.Ident("..") :: nested :: Nil) => Some(nested)
        case Ast.ApplyPattern(Ast.Ident(".."), nested :: Nil) => Some(nested)
        case _ => None

  object SkipTo:
    def unapply(pat: Ast.Pattern): Option[Ast.Pattern] =
      pat match
        case Ast.ExprPattern(Ast.Ident(">") :: nested :: Nil) => Some(nested)
        case Ast.ApplyPattern(Ast.Ident(">"), nested :: Nil) => Some(nested)
        case _ => None

  class ShadowedPatternError(pat1: Pattern, pat2: Pattern)(using Source)
  extends Diagnostics.DoublePositionedReport:
    val kind = Diagnostics.Kind.Warning

    val pos1 = pat1.pos
    val pos2 = pat2.pos

    val message1 = "* pattern shadows the following head pattern, potentially makes the next pattern unreachable."
    val message2 = s"The star pattern covers the head pattern of the next pattern:"
