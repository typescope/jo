package typing

import ast.Ast
import ast.Positions.*

import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*
import reporting.Reporter

import Namer.{ Scope, DelayedDef }
import Inference.TargetType
import PatternTyper.Occurs

import scala.collection.mutable

class PatternTyper(namer: Namer, checker: Checker):
  def transformPatDef(patDef: Ast.PatDef)
      (using lazyDefn: Definitions.Lazy | Definitions, sc: Scope, rp: Reporter, so: Source)
  : DelayedDef[PatDef] =

    given Definitions = lazyDefn match
      case lazyDefn: Definitions.Lazy => lazyDefn.value
      case defn: Definitions => defn

    val patSym = Symbol.createSymbol(patDef.name, namer.nonCyclicTypeProvider, Flags.Pattern | Flags.Fun, sc.owner, patDef.ident.pos)
    given patScope: Scope = sc.fresh(patSym)

    lazy val tparamSyms =
      for tparam <- patDef.tparams yield
        lazy val bound =
          if tparam.bound.isEmpty then
            TypeBound(BottomType, AnyType)
          else
            val boundTree = namer.transformType(tparam.bound)
            TypeBound(BottomType, boundTree.tpe)

        val infoProvider: InfoProvider = (sym: Symbol) => bound
        val sym = Symbol.createSymbol(tparam.name, infoProvider, Flags.Type | Flags.Param, patSym, tparam.pos)
        patScope.define(sym)
        sym

    lazy val paramSyms =
      tparamSyms
      for param <- patDef.params yield
        val tpt = namer.transformType(param.tpt)
        val paramSym = Symbol.createSymbol(param.name, tpt.tpe, Flags.Pattern | Flags.Param, patSym, param.pos)
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
          val patternTyped = transformPattern(pattern, scrutType)

          if !reporterTemp.hasErrors then
            for paramSym <- paramSyms do occurs.checkOccur(paramSym)

          patternTyped

      // Elide checks if other errors are present
      if reporterTemp.hasErrors then
        reporterTemp.commit(rp)

      else
        checkExhaustivity(patterns, resultTypeTree)

      end if

      if patterns.isEmpty then
        Reporter.error("Expect case patterns, found none", patDef.pos)
        WildcardPattern()(ErrorType, patDef.span)

      else
        patterns.tail.foldLeft(patterns.head)(OrPattern.apply)

    def computeInfo(resultType: Type) =
      val tparamRefs = tparamSyms.zipWithIndex.map: (tparamSym, i) =>
        TypeParamRef(tparamSym.name, i)
      val substs = tparamSyms.zip(tparamRefs).toMap
      val tparamInfos = tparamSyms.map(tparam => NamedInfo(tparam.name, tparam.info.as[TypeBound]))
      val rawType = ProcType(tparamInfos, paramSyms.map(_.toNamedInfo), resultType, receives = None, preParamCount = patDef.preParamCount)
      if tparamRefs.isEmpty then rawType
      else TypeOps.substSymbols(rawType, substs)

    namer.nonCyclicTypeProvider.addProvider(patSym, () => computeInfo(resultTypeTree.tpe), () => computeInfo(ErrorType))

    val typer = () =>
      PatDef(patSym, tparamSyms, paramSyms, resultTypeTree, typedBody)(patDef.span)

    DelayedDef(patSym, typer)

  private def checkExhaustivity(patterns: List[Pattern], coveredTypeTree: TypeTree)
      (using defn: Definitions, rp: Reporter, so: Source): Unit =

    import Exhaustivity.Space
    val coveredType = coveredTypeTree.tpe
    val isPartial = coveredType.refersTo(defn.Predef_Partial)
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
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType)
  : Word =

    val Ast.Match(scrutinee, cases) = patmat
    val scrutinee2 =
      given TargetType = TargetType.ValueType
      namer.transform(scrutinee)

    val scrutType = scrutinee2.tpe

    val rp2: Reporter = rp.fresh(buffer = true)
    val cases2 =
      for caseDef <- cases yield
        given Reporter = rp2
        transformCase(caseDef, scrutType)

    val commonType = (cases2: @unchecked) match
      case caseDef :: rest =>
        rest.foldLeft(caseDef.body.tpe): (acc, item) =>
          checker.commonResultType(acc, item.body.tpe, item.body.pos)

    val patmat2 = Match(scrutinee2, cases2)(commonType, patmat.span)

    // Skip the check if there are errors in patterns
    if rp2.hasErrors then
      rp2.commit(rp)
    else
      checkExhaustivity(patmat2)

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
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType)
  : Case =
    val Ast.Case(pat, body) = caseDef

    given Scope = sc.fresh()
    given Occurs = new Occurs

    val rp2 = rp.fresh(buffer = true)
    val pat2 =
      given Reporter = rp2
      transformPattern(pat, scrutType)

    val body2 =
      if rp2.hasErrors then
        rp2.commit(rp)
        Block(Nil)(BottomType, body.span)

      else
        namer.transform(body)

    Case(pat2, body2)(caseDef.span)

  private def transformApplyPattern(
      id: Ast.Ident, args: List[Ast.Word], scrutType: Type, patSpan: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs)
  : Pattern =

    val sym = resolvePatternPredicate(id)

    var fun: Word = Ident(sym)(id.span)

    if fun.tpe.isPolyType then
      fun = namer.instantiatePoly(fun.tpe.asProcType, fun)

    val funType = fun.tpe

    if funType.isProcType then
      val procType = funType.asProcType
      val paramSize = procType.paramTypes.size
      val resType = procType.resultType.stripPartial

      val explain = new StringBuilder
      if Patterns.isValidTypePattern(resType, scrutType)(using explain) then
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
        Reporter.error(s"The pattern result type ${resType.show} is invalid with respect to the scrutinee type ${scrutType.show}. " + explain, id.pos)
        WildcardPattern()(ErrorType, patSpan)

    else
      if !fun.tpe.isError then
        Reporter.error(s"Not a pattern predicate: " + fun.tpe.show, id.pos)

      WildcardPattern()(ErrorType, patSpan)


  private def transformOrPattern(lhs: Ast.Word, rhs: Ast.Word, scrutType: Type, patSpan: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs)
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

    rp2.commit(rp)

    for (sym, pos) <- resLHS do oc.occur(sym, pos)

    OrPattern(lhsPat, rhsPat)

  private def transformInfixCallPattern(
      preArgs: List[Ast.Word], id: Ast.Ident, postArgs: List[Ast.Word],
      scrutType: Type, patSpan: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs)
  : Pattern =

    val sym = resolvePatternPredicate(id)

    var fun: Word = Ident(sym)(id.span)

    if fun.tpe.isPolyType then
      fun = namer.instantiatePoly(fun.tpe.asProcType, fun)

    val funType = fun.tpe

    if funType.isProcType then
      val procType = funType.asProcType
      val preParamCount = procType.preParamCount
      val postParamCount = procType.postParamCount
      val resType = procType.resultType.stripPartial

      val explain = new StringBuilder
      if Patterns.isValidTypePattern(resType, scrutType)(using explain) then
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
        Reporter.error(s"The pattern result type ${resType.show} is invalid with respect to the scrutinee type ${scrutType.show}. " + explain, id.pos)
        WildcardPattern()(ErrorType, patSpan)

    else
      if !fun.tpe.isError then
        Reporter.error(s"Not a pattern predicate: " + fun.tpe.show, id.pos)

      WildcardPattern()(ErrorType, patSpan)

  private def transformTagPattern(
      tag: Ast.Tag, args: List[Ast.Word], scrutType: Type, patSpan: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs)
  : Pattern =

    val id = tag.name

    def checkNested(tagType: TagType): Pattern =
      val paramTypes = tagType.paramTypes
      val paramCount = paramTypes.size
      val argCount = args.size

      if argCount > paramCount then
        Reporter.error(s"The tag type ${id.name} in scrutinee has $paramCount parameters, supplied = $argCount", tag.pos)

      val args2 =
        for (pat, paramType) <- args.zip(paramTypes) yield transformPattern(pat, paramType)

      val tagStringLit = StringLit(id.name)(tag.span)
      val tagTypeActual = TagType.from(id.name, args2.map(_.tpe))
      TagPattern(tagStringLit, args2)(tagTypeActual)

    if scrutType.isUnionType then
      val unionType = scrutType.asUnionType
       if !unionType.hasTag(id.name) then
         Reporter.error(s"The tag ${id.name} does not exist in union type ${unionType.show}", tag.pos)
         WildcardPattern()(ErrorType, patSpan)

       else
         val tagType = unionType.tagType(id.name)
         checkNested(tagType)

    else if scrutType.isTagType then
      val tagType = scrutType.asTagType
      if tagType.tag != id.name then
        Reporter.error(s"The tag ${id.name} does not match the scrutinee type ${tagType.show}", tag.pos)
        WildcardPattern()(ErrorType, patSpan)
      else
        checkNested(tagType)

    else if scrutType.isVoidType then
      val tagType = TagType.from(id.name, args.map(_ => VoidType))
      checkNested(tagType)

    else
      Reporter.error(s"The tag ${id.name} does not match the scrutinee type ${scrutType.show}", tag.pos)
      WildcardPattern()(ErrorType, patSpan)

  private def transformTypePattern(
      id: Ast.Ident, tpt: Ast.TypeTree, scrutType: Type, patSpan: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs)
  : Pattern =

    val name = id.name
    val tpt2 = namer.transformType(tpt)
    val tpe = tpt2.tpe

    val pattern =
      if name == "_" then
        TypePattern(tpt2)

      else
        sc.resolvePattern(name) match
          case Some(sym) =>
            oc.occur(sym, id.pos)

            val explain = new StringBuilder
            // TODO: conform should be same, no need to be equal
            if Patterns.isEqualType(tpe, sym.info)(using explain) then
              val patVal = Ident(sym)(id.span)
              AscribePattern(patVal, TypePattern(tpt2))

            else
              Reporter.error(s"The type ${tpe.show} not a equal to the type of $sym. The latter has type " + sym.info.show, tpt.pos)
              WildcardPattern()(ErrorType, patSpan)

          case None =>
            val sym = Symbol.createSymbol(name, tpe, Flags.Pattern, sc.owner, id.pos)
            sc.definePatternAsTerm(sym)

            val patVal = Ident(sym)(id.span)
            AscribePattern(patVal, TypePattern(tpt2))
        end match
      end if

    val explain = new StringBuilder
    if Patterns.isValidTypePattern(tpe, scrutType)(using explain) || scrutType.isVoidType then
      pattern
    else
      Reporter.error(explain.toString, tpt.pos)
      WildcardPattern()(ErrorType, patSpan)

  private def transformIdentPattern(id: Ast.Ident, scrutType: Type)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs)
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

          val explain = new StringBuilder
          // TODO: conform should be same, no need to be equal
          if Patterns.isEqualType(sym.info, scrutType)(using explain) || scrutType.isVoidType then
            val patVal = Ident(sym)(id.span)
            AscribePattern(patVal, TypePattern(TypeTree(sym.info)(id.span)))

          else
            Reporter.error(s"$sym has the type ${sym.info.show}, which is not equal to the scrutinee type " + scrutType.show, id.pos)
            WildcardPattern()(ErrorType, id.span)

        case None =>
          val sym = Symbol.createSymbol(name, scrutType, Flags.Pattern, sc.owner, id.pos)
          sc.definePatternAsTerm(sym)
          sc.define(sym)

          oc.occur(sym, id.pos)

          val patVal = Ident(sym)(id.span)
          val wildcard = WildcardPattern()(scrutType, id.span)
          AscribePattern(patVal, wildcard)

  private def transformAscribePattern(id: Ast.Ident, nested: Ast.Word, scrutType: Type)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs)
  : Pattern =

    val name = id.name
    if name == "_" then
      WildcardPattern()(scrutType, id.span)

    else
      sc.resolvePattern(name) match
        case Some(sym) =>
          oc.occur(sym, id.pos)

          val nestedPattern = transformPattern(nested, scrutType)

          val explain = new StringBuilder
          // TODO: conform should be same, no need to be equal
          if Patterns.isEqualType(sym.info, nestedPattern.tpe)(using explain) || scrutType.isVoidType then
            val patVal = Ident(sym)(id.span)
            AscribePattern(patVal, nestedPattern)

          else
            Reporter.error(s"$sym has the type ${sym.info.show}, which is not equal to the scrutinee type " + scrutType.show, id.pos)
            WildcardPattern()(ErrorType, id.span)

        case None =>
          val nestedPattern = transformPattern(nested, scrutType)
          val sym = Symbol.createSymbol(name, nestedPattern.tpe, Flags.Pattern, sc.owner, id.pos)
          sc.definePatternAsTerm(sym)
          sc.define(sym)

          oc.occur(sym, id.pos)

          val patVal = Ident(sym)(id.span)
          AscribePattern(patVal, nestedPattern)

  private def transformExprPattern(expr: Ast.Expr, scrutType: Type)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs)
  : Pattern =

    expr.words: @unchecked match
      case head :: Nil =>
        transformPattern(head, scrutType)

      case (tag: Ast.Tag) :: args =>
        transformTagPattern(tag, args, scrutType, expr.span)

      case words =>
        // mixed prefix/infix/postfix pattern, arity depends on type of the function
        val wordList: mutable.ListBuffer[Ast.Word] = mutable.ListBuffer.from(words)

        val resolveProc: Ast.Word => Option[ProcType] = (word: Ast.Word) => word match
          case id: Ast.Ident =>
            resolvePatternPredicateOpt(id) match
              case Some(sym) =>
                val procType = sym.info.asProcType
                // parameterless predicates should not interfere with expression typing
                if procType.params.isEmpty then None else Some(procType)

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

  private def transformSeqPattern(seq: Ast.SeqLit, scrutType: Type)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs)
  : Pattern =

    val tvar = TypeVar("T", this.namer.inferencer)
    val seqType = AppliedType(TypeRef(defn.Predef_Seq), tvar :: Nil)

    if Subtyping.conforms(scrutType, seqType) then
      if !tvar.isInstantiated then
        Reporter.error("Tvar is not instantiated", seq.pos)
        WildcardPattern()(ErrorType, seq.span)

      else
        val regexPatterns = new mutable.ArrayBuffer[RegexPattern]
        val itemType = tvar.instantiated

        for pat <- seq.words do
          pat match
            case Ast.Expr(Ast.Ident(">") :: pat :: Nil) =>
              val inner = transformPattern(pat, itemType)
              regexPatterns += SkipToPattern(inner)(scrutType, pat.span)

            case Ast.Expr(nested :: Ast.Ident("*") :: Nil) =>
              regexPatterns += transformStarPattern(nested, itemType, scrutType, pat)

            case expr: Ast.Expr =>
              Reporter.error("Unrecognized sequence pattern. Do you forget parenthesis for the nested item pattern?", expr.pos)

            case Ast.Apply(Ast.Ident(">"), pat :: Nil) =>
              val inner = transformPattern(pat, itemType)
              regexPatterns += SkipToPattern(inner)(scrutType, pat.span)

            case pat =>
              val pattern = transformPattern(pat, itemType)
              regexPatterns += AtomPattern(pattern)
          end match
        end for

        SeqPattern(regexPatterns.toList)(scrutType, seq.span)

    else
      Reporter.error(s"The scrutinee type ${scrutType.show}, does not conform to Seq[T] expected by a sequence pattern", seq.pos)
      WildcardPattern()(ErrorType, seq.span)


  private def transformStarPattern(nested: Ast.Word, itemType: Type, scrutType: Type, pat: Ast.Word)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs)
  : RegexPattern =

    // inner pattern has no access to outer locally introduced pattern variables
    val occursInner: Occurs = new Occurs

    val inner =
      given Occurs = occursInner
      given Scope = sc.freshFlatPatternScope()
      transformPattern(nested, itemType)

    val bindings = new mutable.ArrayBuffer[(Symbol, Symbol)]
    occursInner.result().foreach: (innerSym, pos) =>
      val expectedType = AppliedType(TypeRef(defn.Predef_Seq), innerSym.info :: Nil)

      // first check if there is a pattern variable of the same name exists
      sc.resolvePattern(innerSym.name) match
        case Some(outerSym) =>
          oc.occur(outerSym, pos)

          if Subtyping.conforms(outerSym.info, expectedType) then
            bindings += outerSym -> innerSym

          else
            Reporter.error(s"$outerSym has the type ${outerSym.info.show}, which does not conform to the type " + expectedType.show, pos)

        case None =>
          val outerSym = Symbol.createSymbol(innerSym.name, expectedType, Flags.Pattern, sc.owner, pos)
          sc.definePatternAsTerm(outerSym)
          sc.define(outerSym)
          oc.occur(outerSym, pos)
          bindings += outerSym -> innerSym
      end match


    StarPattern(inner)(scrutType, pat.span, bindings.toList)

  private def resolvePatternPredicate(id: Ast.Ident)(using sc: Scope, rp: Reporter, so: Source): Symbol =
    resolvePatternPredicateOpt(id) match
      case Some(sym) => sym
      case None =>
        Reporter.error(s"Undefined pattern identifier " + id.name, id.pos)
        Symbol.createSymbol(id.name, ErrorType, Flags.Synthetic, sc.owner, id.pos)

  private def resolvePatternPredicateOpt(id: Ast.Ident)(using sc: Scope): Option[Symbol] =
    sc.resolvePattern(id.name) match
      case None =>
        sc.resolveTerm(id.name) match
          case Some(sym) if sym.is(Flags.Section) =>
            val nameTable = sym.info.as[NameTableInfo]
            nameTable.resolvePattern(sym.name)

          case _ =>
            None

      case Some(sym) =>
        if sym.is(Flags.Fun) then Some(sym) else None


  private def transformPattern(
      pat: Ast.Word, scrutType: Type)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, oc: Occurs)
  : Pattern =

    (pat: @unchecked) match
      case id: Ast.Ident =>
        transformIdentPattern(id, scrutType)

      case tag: Ast.Tag =>
        transformTagPattern(tag, Nil, scrutType, pat.span)

      case Ast.IntLit(value) =>
        given TargetType = TargetType.Known(scrutType)
        val literal = namer.transform(pat)
        ValuePattern(literal)

      case Ast.BoolLit(value) =>
        given TargetType = TargetType.Known(scrutType)
        val literal = namer.transform(pat)
        ValuePattern(literal)

      case Ast.CharLit(value) =>
        given TargetType = TargetType.Known(scrutType)
        val literal = namer.transform(pat)
        ValuePattern(literal)

      case Ast.StringLit(value) =>
        given TargetType = TargetType.Known(scrutType)
        val literal = namer.transform(pat)
        ValuePattern(literal)

      case Ast.TypeAscribe(id: Ast.Ident, tpt) =>
        transformTypePattern(id, tpt, scrutType, pat.span)

      case Ast.Apply(id: Ast.Ident, args) =>
        transformApplyPattern(id, args, scrutType, pat.span)

      case Ast.InfixCall(preArgs, id: Ast.Ident, postArgs) =>
        transformInfixCallPattern(preArgs, id, postArgs, scrutType, pat.span)

      case Ast.Apply(tag: Ast.Tag, nested) =>
        transformTagPattern(tag, nested, scrutType, pat.span)

      case Ast.Assign(id: Ast.Ident, nested) =>
        transformAscribePattern(id, nested, scrutType)

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

            bindings2 += Assign(Ident(sym)(id.span), expr2)(id.span | expr.span)
        end for

        TermBindingPattern(pattern2, bindings2.toList)

      case expr: Ast.Expr =>
        transformExprPattern(expr, scrutType)

      case seq: Ast.SeqLit =>
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
