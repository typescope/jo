package typing

import ast.{ Trees => Ast }
import ast.Positions.*

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import reporting.Reporter
import reporting.Diagnostics

import Inference.TargetType
import PatternTyper.{ Occurs, ShadowedPatternError, RemainingSlice, SkipTo }

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

    for param <- patDef.params if param.adapters.nonEmpty do
      val span = param.adapters.head.span | param.adapters.last.span
      Reporter.error("A pattern parameter cannot have adapters", span.toPos)

    lazy val paramSyms =
      tparamSyms
      for param <- patDef.params yield
        val tpt = namer.transformType(param.tpt)
        val paramSym = PatternSymbol.create(param.name, tpt.tpe, Flags.Param, Visibility.Default, patSym, param.pos)
        patScope.define(paramSym)
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
          val occurs = new Occurs
          given Occurs = occurs
          given Scope = patScope.fresh()
          val patternTyped = Inference.freshIsolate:
            transformPattern(pattern, scrutType)

          if !reporterTemp.hasErrors then
            for paramSym <- paramSyms do occurs.checkOccur(paramSym)

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
      ProcType(tparamSyms, paramSyms.map(_.toNamedInfo), paramSyms.map(_ => Nil), autoTypes, Nil, resultType, () => Nil, patDef.preParamCount)

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

  private def transformCase(caseDef: Ast.Case, scrutType: Type)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Case =
    val Ast.Case(pat, body) = caseDef

    given Scope = sc.freshLocalPatternScope()
    given Occurs = new Occurs

    val rp2 = rp.fresh(buffer = true)
    val pat2 =
      given Reporter = rp2
      Inference.freshIsolate:
        transformPattern(pat, scrutType)

    val body2 =
      if rp2.hasErrors then
        errorWord(body.span)

      else
        namer.transform(body)

    // may contain warnings
    rp2.commit(rp)

    Case(pat2, body2)(caseDef.span)

  private def transformApplyPattern(
      id: Ast.RefTree, args: List[Ast.Word], scrutType: Type, patSpan: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs, tvars: TypeVars)
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


  private def transformOrPattern(lhs: Ast.Word, rhs: Ast.Word, scrutType: Type, patSpan: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs, tvars: TypeVars)
  : Pattern =
    given rp2: Reporter = rp.fresh(buffer = true)

    val occursLHS = new Occurs
    val occursRHS = new Occurs

    val lhsPat =
      given Occurs = occursLHS
      transformPattern(lhs, scrutType)

    val rhsPat =
      given Occurs = occursRHS
      transformPattern(rhs, scrutType)

    val resLHS = occursLHS.result()
    val resRHS = occursRHS.result()

    val setLHS = resLHS.keySet
    val setRHS = resRHS.keySet

    if !rp2.hasErrors && setLHS != setRHS then
      Reporter.error(
        s"The lhs and rhs bind should bind same set of symbols, found lhs = " + setLHS + ", rhs = " + setRHS,
        patSpan.toPos
      )

    // may contain warnings
    rp2.commit(rp)

    for (sym, pos) <- resLHS do oc.occur(sym, pos)

    val valueType =
      given TargetType = TargetType.Unknown
      Inference.commonResultType(lhsPat.valueType, rhsPat.valueType) match
        case Some(tpe) => tpe
        case None => scrutType

    OrPattern(lhsPat, rhsPat)(valueType)

  private def transformInfixCallPattern(
      preArgs: List[Ast.Word], id: Ast.RefTree, postArgs: List[Ast.Word],
      scrutType: Type, patSpan: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs, tvars: TypeVars)
  : Pattern =

    val sym = resolvePatternPredicate(id)

    var fun: Word = Ident(sym)(id.span)

    if fun.tpe.isPolyType then
      fun = TreeOps.instantiatePoly(fun.tpe.asProcType, fun)

    val funType = fun.tpe

    if funType.isProcType then
      val procType = funType.asProcType
      val preParamCount = procType.preParamCount
      val postParamCount = procType.postParamCount
      val resType = procType.resultType.stripPartial

      val explain = new StringBuilder
      if Patterns.isValidTypePattern(resType, scrutType)(using explain) then
        if !Subtyping.conforms(resType, scrutType) && !Subtyping.conforms(scrutType, resType) then
          Reporter.error(s"The pattern has different type from the scrutinee type, scrutinee = ${scrutType.show}, pattern = ${resType.show}", id.pos)

        if preArgs.size != preParamCount then
          Reporter.error(
            s"Function ${fun.show} expects $preParamCount pre arguments, found = ${preArgs.size}",
            id.pos)
          WildcardPattern()(ErrorType, patSpan)

        else if postArgs.size != postParamCount then
          Reporter.error(
            s"Function ${fun.show} expects $postParamCount post arguments, found = ${postArgs.size}",
            id.pos)
          WildcardPattern()(ErrorType, patSpan)

        else
          if sym == defn.Predef_orPattern then
            assert(preArgs.size == 1, "preArgs.size = " + preArgs.size)
            assert(postArgs.size == 1, "postArgs.size = " + postArgs.size)

            transformOrPattern(preArgs.head, postArgs.head, scrutType, patSpan)
          else
            val preArgs2 =
              for (arg, paramType) <- preArgs.zip(procType.preParamTypes) yield
                transformPattern(arg, paramType)

            val postArgs2 =
              for (arg, paramType) <- postArgs.zip(procType.postParamTypes) yield
                transformPattern(arg, paramType)

            ApplyPattern(fun, preArgs2 ++ postArgs2)(resType, patSpan)

        end if
      else

        if !scrutType.isError then
          Reporter.error(s"The pattern result type ${resType.show} is invalid with respect to the scrutinee type ${scrutType.show}. " + explain, id.pos)
        WildcardPattern()(ErrorType, patSpan)

    else
      if !fun.tpe.isError then
        Reporter.error(s"Not a pattern predicate: " + fun.tpe.show, id.pos)

      WildcardPattern()(ErrorType, patSpan)

  private def transformTypePattern(
      id: Ast.Ident, tpt: Ast.TypeTree, scrutType: Type, patSpan: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs)
  : Pattern =

    val name = id.name
    val tpt2 = Checks.eager { namer.transformType(tpt) }
    val tpe = tpt2.tpe

    val pattern =
      if name == "_" then
        TypePattern(tpt2)(scrutType)

      else
        sc.resolvePattern(name) match
          case Some(sym) =>
            oc.occur(sym, id.pos)

            if Subtyping.conforms(tpe, sym.info) then
              val patVal = Ident(sym)(id.span)
              AliasPattern(patVal, TypePattern(tpt2)(scrutType))(isDef = false)

            else
              Reporter.error(s"The type ${tpe.show} not a equal to the type of $sym. The latter has type " + sym.info.show, tpt.pos)
              WildcardPattern()(ErrorType, patSpan)

          case None =>
            val sym = PatternSymbol.create(name, tpe, Flags.empty, Visibility.Default, sc.owner, id.pos)
            sc.definePatternAsTerm(sym)

            val patVal = Ident(sym)(id.span)
            AliasPattern(patVal, TypePattern(tpt2)(scrutType))(isDef = true)
        end match
      end if

    val explain = new StringBuilder
    if Patterns.isValidTypePattern(tpe, scrutType)(using explain) then
      pattern
    else
      Reporter.error(explain.toString, tpt.pos)
      WildcardPattern()(ErrorType, patSpan)

  private def transformIdentPattern(id: Ast.Ident, scrutType: Type)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs, tvars: TypeVars)
  : Pattern =

    val name = id.name
    if id.isCapitalized then
      transformApplyPattern(id, Nil, scrutType, id.span)

    else if name == "_" then
      WildcardPattern()(scrutType, id.span)

    else
      sc.resolvePattern(name) match
        case Some(sym) =>
          oc.occur(sym, id.pos)

          if Subtyping.conforms(scrutType, sym.info) then
            val patVal = Ident(sym)(id.span)
            AliasPattern(patVal, WildcardPattern()(sym.info, id.span.endPoint))(isDef = false)

          else
            Reporter.error(s"$sym has the type ${sym.info.show}, which is not equal to the scrutinee type " + scrutType.show, id.pos)
            WildcardPattern()(ErrorType, id.span)

        case None =>
          val sym = PatternSymbol.create(name, scrutType, Flags.empty, Visibility.Default, sc.owner, id.pos)
          sc.definePatternAsTerm(sym)
          sc.define(sym)

          oc.occur(sym, id.pos)

          val patVal = Ident(sym)(id.span)
          val wildcard = WildcardPattern()(scrutType, id.span.endPoint)
          AliasPattern(patVal, wildcard)(isDef = true)

  private def transformAliasPattern(id: Ast.Ident, nested: Ast.Word, scrutType: Type)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs, tvars: TypeVars)
  : Pattern =

    val name = id.name
    if name == "_" then
      // TODO: add test
      transformPattern(nested, scrutType)

    else
      sc.resolvePattern(name) match
        case Some(sym) =>
          oc.occur(sym, id.pos)

          val nestedPattern = transformPattern(nested, scrutType)

          if Subtyping.conforms(nestedPattern.valueType, sym.info) then
            val patVal = Ident(sym)(id.span)
            AliasPattern(patVal, nestedPattern)(isDef = false)

          else
            Reporter.error(s"$sym has the type ${sym.info.show}, which is not equal to the scrutinee type " + scrutType.show, id.pos)
            WildcardPattern()(ErrorType, id.span)

        case None =>
          val nestedPattern = transformPattern(nested, scrutType)
          val sym = PatternSymbol.create(name, nestedPattern.valueType, Flags.empty, Visibility.Default, sc.owner, id.pos)
          sc.definePatternAsTerm(sym)
          sc.define(sym)

          oc.occur(sym, id.pos)

          val patVal = Ident(sym)(id.span)
          AliasPattern(patVal, nestedPattern)(isDef = true)

  private def transformExprPattern(expr: Ast.Expr, scrutType: Type)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs, tvars: TypeVars)
  : Pattern =

    expr.words: @unchecked match
      case head :: Nil =>
        transformPattern(head, scrutType)

      case words =>
        // mixed prefix/infix/postfix pattern, arity depends on type of the function
        val wordList: mutable.ListBuffer[Ast.Word] = mutable.ListBuffer.from(words)

        val resolveProc = new ExprTyper.Handler[Ast.Word]:
          def bundle(preArgs: List[Ast.Word], binder: Ast.Word, postArgs: List[Ast.Word]): Ast.Word =
            val startSpan = if preArgs.isEmpty then binder.span else preArgs.head.span
            val endSpan = if postArgs.isEmpty then binder.span else postArgs.last.span
            Ast.InfixCall(preArgs, binder, postArgs)(startSpan | endSpan)

          def resolveShape(tpt: Ast.Word): Option[ExprTyper.Shape] =
            tpt match
              case id: Ast.RefTree =>
                transformPatternRef(id) match
                  case Some(sym) =>
                    val procType = sym.info.asProcType
                    // parameterless predicates should not interfere with expression typing
                    if procType.params.isEmpty then
                      None
                    else
                      val prec = ExprTyper.precedence(id)
                      val shape = ExprTyper.Shape(procType.preParamCount, procType.postParamCount, prec)
                      Some(shape)

                  case _ =>
                    None

              case _ =>
                None

        val values = namer.exprTyper.parseMixed(wordList, -1, resolveProc)

        assert(wordList.isEmpty, wordList)
        if values.size > 1 then
          val rest = values.init
          val span = rest.head.span | rest.last.span
          Reporter.error("Found extra pattern, an expression pattern should form a single pattern", span.toPos)

        transformPattern(values.last, scrutType)

  private def transformSeqPattern(seq: Ast.ListLit, scrutType: Type)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs, tvars: TypeVars)
  : Pattern =

    val tvar = TypeVar("T", seq.span)
    val seqType = AppliedType(defn.Internal_Seq, tvar :: Nil)

    val sliceMethodType =
      ProcType(
        tparams = Nil,
        params = NamedInfo("from", defn.IntType) :: NamedInfo("to", defn.IntType)  :: Nil,
        adapters = List(Nil, Nil),
        autos = Nil,
        candidates = Nil,
        resultType = scrutType.widenTermRef,
        receivesInfo = () => Nil,
        preParamCount = 0
      )

    lazy val sliceMethodConforms: Boolean =
      scrutType.getTermMember("slice") match
        case Some(tp) if tp.isProcType =>
          val tp1 = tp.asProcType
          // ignore effects
          Subtyping.conforms(tp1.copy(receivesInfo = sliceMethodType.receivesInfo), sliceMethodType)

        case _ => false

    def memberConforms(name: String) =
      scrutType.getTermMember(name) match
        case Some(tp) if tp.isProcType =>
          val tp1 = tp.asProcType
          val tp2 = seqType.termMember(name).asProcType
          // ignore effects
          Subtyping.conforms(tp1.copy(receivesInfo = tp2.receivesInfo), tp2)

        case _ => false

    val signatureConforms =
      memberConforms("get") && memberConforms("size")

    if signatureConforms then
      if !tvar.isInstantiated then
        Reporter.error("Tvar is not instantiated", seq.pos)
        WildcardPattern()(ErrorType, seq.span)

      else
        val partPatterns = new mutable.ArrayBuffer[SeqPartPattern]
        val itemType = tvar.instantiated

        val tempReporter = rp.fresh(buffer = true)

        for pat <- seq.words do
          given Reporter = tempReporter
          pat match
            case SkipTo(nested) =>
              val inner = transformPattern(nested, itemType)
              partPatterns += SkipToPattern(inner)(pat.span)

            case Ast.Expr(nested :: Ast.Ident("*") :: Nil) =>
              partPatterns += transformStarPattern(nested, itemType, pat)

            case RemainingSlice(nested) =>
              if pat `ne` seq.words.last then
                Reporter.error(".. may only be used in the last position of a sequence pattern", pat.pos)

              else if !sliceMethodConforms then
                Reporter.error("The scrutinee does not have a `slice(from: Int, to: Int)` method to support the pattern `..`", pat.pos)

              else
                val inner = transformPattern(nested, scrutType)
                partPatterns += RestPattern(inner)(pat.span)

            case expr: Ast.Expr =>
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
      Reporter.error(s"The scrutinee type ${scrutType.show}, does not conform to Seq[T] expected by a sequence pattern", seq.pos)
      WildcardPattern()(ErrorType, seq.span)


  private def transformStarPattern(nested: Ast.Word, itemType: Type, pat: Ast.Word)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs, tvars: TypeVars)
  : SeqPartPattern =

    // inner pattern has no access to outer locally introduced pattern variables
    val occursInner: Occurs = new Occurs

    val inner =
      given Occurs = occursInner
      given Scope = sc.freshLocalPatternScope()
      transformPattern(nested, itemType)

    val bindings = new mutable.ArrayBuffer[(Symbol, Symbol)]
    for
      (innerSym, pos) <- occursInner.result()
      if !innerSym.name.startsWith("_")
    do

      val expectedType = AppliedType(defn.List_type, innerSym.info :: Nil)

      // first check if there is a pattern variable of the same name exists
      sc.resolvePattern(innerSym.name) match
        case Some(outerSym) =>
          oc.occur(outerSym, pos)

          if Subtyping.conforms(outerSym.info, expectedType) then
            bindings += outerSym -> innerSym

          else
            Reporter.error(s"$outerSym has the type ${outerSym.info.show}, which does not conform to the type " + expectedType.show, pos)

        case None =>
          // It is OK to not set Flags.Mutable because after initialization it cannot be changed.
          val outerSym = PatternSymbol.create(innerSym.name, expectedType, Flags.empty, Visibility.Default, sc.owner, pos)
          sc.definePatternAsTerm(outerSym)
          sc.define(outerSym)
          oc.occur(outerSym, pos)
          bindings += outerSym -> innerSym
      end match


    StarPattern(inner)(pat.span, bindings.toList)

  private def resolvePatternPredicate(id: Ast.RefTree)(using sc: Scope, rp: Reporter, so: Source, defn: Definitions): Symbol =
    transformPatternRef(id) match
      case Some(sym) => sym
      case None =>
        id match
          case id: Ast.Ident =>
            Reporter.error(s"Undefined pattern name " + id.name, id.pos)

          case _ =>
            // error already reported

        PatternSymbol.create(id.name, ErrorType, Flags.Synthetic, Visibility.Default, sc.owner, id.pos)

  private def transformPatternRef(qualid: Ast.RefTree)
    (using sc: Scope, defn: Definitions, so: Source, rp: Reporter): Option[Symbol] =

    qualid match
      case id: Ast.Ident =>
        sc.resolvePattern(id.name) match
          case None => None

          case Some(sym) =>
            if sym.is(Flags.Fun) then Some(sym) else None

      case Ast.Select(qual, name) =>
        // selection must be a pattern predicate
        val qualTyped = namer.transformRefTree(qual.asInstanceOf[Ast.RefTree])

        qualTyped.tpe.getPatternMember(name) match
          case None =>
            Reporter.error("A selection must be a pattern predicate", qualid.pos)
            None

          case res @ Some(target) =>
            Checker.checkAccess(target, sc.owner, qualid.span)

            res

  private def transformPattern(
      pat: Ast.Word, scrutType: Type)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs, tvars: TypeVars)
  : Pattern =

    (pat: @unchecked) match
      case id: Ast.Ident =>
        transformIdentPattern(id, scrutType)

      case Ast.IntLit(value) =>
        given TargetType = TargetType.Known(scrutType)
        val literal = namer.transform(pat)
        ValuePattern(literal)(scrutType)

      case Ast.BoolLit(value) =>
        given TargetType = TargetType.Known(scrutType)
        val literal = namer.transform(pat)
        ValuePattern(literal)(scrutType)

      case Ast.CharLit(value) =>
        given TargetType = TargetType.Known(scrutType)
        val literal = namer.transform(pat)
        ValuePattern(literal)(scrutType)

      case Ast.StringLit(value) =>
        given TargetType = TargetType.Known(scrutType)
        val literal = namer.transform(pat)
        ValuePattern(literal)(scrutType)

      case Ast.TypeAscribe(id: Ast.Ident, tpt) =>
        transformTypePattern(id, tpt, scrutType, pat.span)

      case Ast.Apply(ref: Ast.RefTree, args, _) =>
        transformApplyPattern(ref, args, scrutType, pat.span)

      case ref: Ast.Select =>
        transformApplyPattern(ref, args = Nil, scrutType, pat.span)

      case Ast.InfixCall(preArgs, id: Ast.RefTree, postArgs) =>
        transformInfixCallPattern(preArgs, id, postArgs, scrutType, pat.span)

      case Ast.Assign(id: Ast.Ident, nested) =>
        transformAliasPattern(id, nested, scrutType)

      case Ast.If(cond, pattern, Ast.Block(Nil)) =>
        val pattern2 = transformPattern(pattern, scrutType)

        val guard =
          given TargetType = TargetType.Known(defn.BoolType)
          namer.transform(cond)

        GuardPattern(pattern2, guard)

      case Ast.With(pattern, bindings) =>
        val pattern2 = transformPattern(pattern, scrutType)
        val bindings2 = new mutable.ArrayBuffer[Assign]
        for Ast.WithArg(id, expr) <- bindings yield
          val sym = sc.resolvePattern(id.name, id.pos)

          if !sym.info.isError then
            oc.occur(sym, id.pos)

            val expr2 =
              given TargetType = TargetType.Known(sym.info)
              namer.transform(expr)

            bindings2 += Assign(Ident(sym)(id.span), expr2)
        end for

        BindPattern(pattern2, bindings2.toList)

      case expr: Ast.Expr =>
        transformExprPattern(expr, scrutType)

      case seq: Ast.ListLit =>
        transformSeqPattern(seq, scrutType)

    end match
  end transformPattern

end PatternTyper

object PatternTyper:
  class Occurs(init: Map[Symbol, SourcePosition]):
    def this() = this(Map.empty)

    private var census: Map[Symbol, SourcePosition] = init

    def result(): Map[Symbol, SourcePosition] = census

    def occur(symbol: Symbol, pos: SourcePosition)(using rp: Reporter, so: Source) =
      if census.contains(symbol) then
        Reporter.error(s"The parameter $symbol occurred more than once in the patterns", pos)
      else
        census = census.updated(symbol, pos)

    def checkOccur(symbol: Symbol)(using rp: Reporter) =
      if !census.contains(symbol) then
        Reporter.error(s"The parameter $symbol should occur once in the patterns", symbol.sourcePos)

  object RemainingSlice:
    def unapply(word: Ast.Word): Option[Ast.Word] =
      word match
        case Ast.Expr(Ast.Ident("..") :: nested :: Nil) => Some(nested)
        case Ast.Apply(Ast.Ident(".."), nested :: Nil, _) => Some(nested)
        case _ => None

  object SkipTo:
    def unapply(word: Ast.Word): Option[Ast.Word] =
      word match
        case Ast.Expr(Ast.Ident(">") :: nested :: Nil) => Some(nested)
        case Ast.Apply(Ast.Ident(">"), nested :: Nil, _) => Some(nested)
        case _ => None

  class ShadowedPatternError(pat1: Pattern, pat2: Pattern)(using Source)
  extends Diagnostics.DoublePositionedReport:
    val kind = Diagnostics.Kind.Warning

    val pos1 = pat1.pos
    val pos2 = pat2.pos

    val message1 = "* pattern shadows the following head pattern, potentially makes the next pattern unreachable."
    val message2 = s"The star pattern covers the head pattern of the next pattern:"
