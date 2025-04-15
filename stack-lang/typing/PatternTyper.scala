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


class PatternTyper(namer: Namer, checker: Checker):
  def transformPatDef(patDef: Ast.PatDef)(using sc: Scope, rp: Reporter, so: Source): DelayedDef[PatDef] =
    val patSym = Symbol.createSymbol(patDef.name, namer.nonCyclicTypeProvider, Flags.Pattern | Flags.Fun, sc.owner, patDef.ident.pos)
    val patScope = sc.fresh(patSym)

    lazy val tparamSyms =
      for tparam <- patDef.tparams yield
        lazy val bound =
          if tparam.bound.isEmpty then
            TypeBound(BottomType, AnyType)
          else
            val boundTree = namer.transformType(tparam.bound)(using patScope)
            TypeBound(BottomType, boundTree.tpe)

        val infoProvider: InfoProvider = (sym: Symbol) => bound
        val sym = Symbol.createSymbol(tparam.name, infoProvider, Flags.Type | Flags.Param, patSym, tparam.pos)
        patScope.define(sym)
        sym

    lazy val paramSyms =
      tparamSyms

      for param <- patDef.params yield
        val tpt = namer.transformType(param.typ)(using patScope)
        val paramSym = Symbol.createSymbol(param.name, tpt.tpe, Flags.Pattern | Flags.Param, patSym, param.pos)
        patScope.define(paramSym)
        paramSym

    lazy val givenResultType =
      tparamSyms

      assert(!patDef.resultType.isEmpty)
      val resTypeTree = namer.transformType(patDef.resultType)(using patScope)
      resTypeTree.tpe

    lazy val resultType =
      if !patDef.resultType.isEmpty then
        givenResultType
      else
        typedBody.tpe
      end if

    lazy val typedBody =
      paramSyms
      val occurs = new Occurs
      given Scope = patScope
      given Occurs = occurs
      val reporterDiscard = rp.fresh(buffer = true)
      val scrutType = if patDef.resultType.isEmpty then VoidType else givenResultType
      val body2 =
        given Reporter = reporterDiscard
        transformPattern(patDef.body, scrutType)

      if reporterDiscard.hasErrors then
        reporterDiscard.commit(rp)
      else
        // Elide checks if other errors are present
        if !patDef.resultType.isEmpty && !Subtyping.conforms(scrutType, body2.tpe) then
          Reporter.error("Result type not equal to the type of body, found = " + body2.tpe.show, patDef.resultType.pos)

        for paramSym <- paramSyms do occurs.checkOccur(paramSym)
      end if

      body2

    def computeInfo(resultType: Type) =
        val tparamRefs = tparamSyms.zipWithIndex.map: (tparamSym, i) =>
          TypeParamRef(tparamSym.name, i)
        val substs = tparamSyms.zip(tparamRefs).toMap
        val tparamInfos = tparamSyms.map(tparam => NamedInfo(tparam.name, tparam.info.as[TypeBound]))
        val rawType = ProcType(tparamInfos, paramSyms.map(_.toNamedInfo), resultType, receives = None, preParamCount = 0)
        if tparamRefs.isEmpty then rawType
        else TypeOps.substSymbols(rawType, substs)

    namer.nonCyclicTypeProvider.addProvider(patSym, () => computeInfo(resultType), () => computeInfo(ErrorType))

    val typer = () =>
      val tpt = TypeTree(resultType)(patDef.resultType.span)
      PatDef(patSym, tparamSyms, paramSyms, tpt, typedBody)(patDef.span)

    DelayedDef(patSym, typer)

  def transformMatch(patmat: Ast.Match)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val Ast.Match(scrutinee, cases) = patmat
    val scrutinee2 = namer.transform(scrutinee)(using sc, rp, so, TargetType.ValueType)
    val scrutType = scrutinee2.tpe

    val cases2 = for caseDef <- cases yield transformCase(caseDef, scrutType)
    val commonType = (cases2: @unchecked) match
      case caseDef :: rest =>
        rest.foldLeft(caseDef.body.tpe): (acc, item) =>
          checker.commonResultType(acc, item.body.tpe, item.body.pos)

    val patmat2 = Match(scrutinee2, cases2)(commonType, patmat.span)

    checkExhaustivity(patmat2)

    patmat2

  private def checkExhaustivity(patmat: Match)(using Reporter, Source): Unit =
    import Exhaustivity.Space
    var rest = Space.TypeSpace(patmat.scrutinee.tpe.widen)
    for Case(pat, _) <- patmat.cases do
      val space = Exhaustivity.project(pat)
      if Exhaustivity.isDisjoint(rest, space) then
        Reporter.error("The case is not reachable", pat.pos)
      else
        rest = Exhaustivity.subtract(rest, space)
    end for

    val cases = Exhaustivity.flatten(rest)
    if !cases.isEmpty then
      val five = cases.take(5)
      val examples = five.map(_.show).mkString(", ")
      val word = if five.size > 1 then "cases" else "case"
      Reporter.error(s"The match will failure for the $word: " + examples, patmat.scrutinee.pos)


  private def transformCase(caseDef: Ast.Case, scrutType: Type)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Case =
    given Scope = sc.fresh()
    given Occurs = new Occurs

    val Ast.Case(pat, body) = caseDef
    val pat2 = transformPattern(pat, scrutType)
    val body2 = namer.transform(body)
    Case(pat2, body2)(caseDef.span)

  private def transformApplyPattern(id: Ast.Ident, args: List[Ast.Word], scrutType: Type, patSpan: Span)
    (using sc: Scope, rp: Reporter, so: Source, oc: Occurs)
  : Pattern =

    val sym = sc.resolvePattern(id.name, id.pos)

    var fun: Word = Ident(sym)(id.span)

    if fun.tpe.isPolyType then
      fun = namer.exprTyper.instantiatePoly(fun.tpe.asProcType, fun)

    val funType = fun.tpe

    if funType.isProcType then
      val procType = funType.asProcType
      val paramSize = procType.paramTypes.size
      val resType = procType.resultType

      val explain = new StringBuilder
      if Patterns.isValidTypePattern(resType, scrutType)(using explain) then
        if args.size != paramSize then
          Reporter.error(s"The pattern predicate expects $paramSize arguments, found = ${args.size}", id.pos)
          WildcardPattern()(ErrorType, patSpan)
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
      Reporter.error(s"Not a function: " + fun.tpe.show, id.pos)
      WildcardPattern()(ErrorType, patSpan)

  private def transformTagPattern(tag: Ast.Tag, args: List[Ast.Word], scrutType: Type, patSpan: Span)
    (using sc: Scope, rp: Reporter, so: Source, oc: Occurs)
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

      val tagStringLit = StringLit(id.name)(Definitions.instance.StringType, tag.span)
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

  private def transformTypePattern(id: Ast.Ident, tpt: Ast.TypeTree, scrutType: Type, patSpan: Span)
    (using sc: Scope, rp: Reporter, so: Source, oc: Occurs)
  : Pattern =

    val name = id.name
    val tpt2 = namer.transformType(tpt)
    val tpe = tpt2.tpe

    val pattern =
      if name == "_" then
        TypePattern(tpt2)

      else if sc.owner.isPattern then
        val sym = sc.resolvePattern(name, id.pos)

        if !sym.info.isError then
          oc.occur(sym, id.pos)

        val explain = new StringBuilder
        if Patterns.isEqualType(tpe, sym.info)(using explain) then
          val patVal = Ident(sym)(id.span)
          AscribePattern(patVal, TypePattern(tpt2))

        else
          Reporter.error(s"The type ${tpe.show} not a equal to the type of $sym. The latter has type " + sym.info.show, tpt.pos)
          WildcardPattern()(ErrorType, patSpan)

      else
        val sym = Symbol.createSymbol(name, tpe, Flags.Pattern, sc.owner, id.pos)
        sc.definePatternAsTerm(sym)

        val patVal = Ident(sym)(id.span)
        AscribePattern(patVal, TypePattern(tpt2))

    val explain = new StringBuilder
    if Patterns.isValidTypePattern(tpe, scrutType)(using explain) || scrutType.isVoidType then
      pattern
    else
      Reporter.error(explain.toString, tpt.pos)
      WildcardPattern()(ErrorType, patSpan)

  private def transformIdentPattern(id: Ast.Ident, scrutType: Type)
   (using sc: Scope, rp: Reporter, so: Source, oc: Occurs)
  : Pattern =

    val name =id.name
    if id.isCapitalized then
      transformApplyPattern(id, Nil, scrutType, id.span)

    else if name == "_" then
      WildcardPattern()(scrutType, id.span)

    else if sc.owner.isPattern then
      val sym = sc.resolvePattern(name, id.pos)

      if !sym.info.isError then
        oc.occur(sym, id.pos)

      val explain = new StringBuilder
      if Patterns.isEqualType(sym.info, scrutType)(using explain) || scrutType.isVoidType then
        val patVal = Ident(sym)(id.span)
        AscribePattern(patVal, TypePattern(TypeTree(sym.info)(id.span)))

      else
        Reporter.error(s"$sym has the type ${sym.info.show}, which is not equal to the scrutinee type " + scrutType.show, id.pos)
        WildcardPattern()(ErrorType, id.span)

    else
      val sym = Symbol.createSymbol(name, scrutType, Flags.Pattern, sc.owner, id.pos)
      sc.definePatternAsTerm(sym)

      val patVal = Ident(sym)(id.span)
      val wildcard = WildcardPattern()(scrutType, id.span)
      AscribePattern(patVal, wildcard)

  private def transformPattern(pat: Ast.Word, scrutType: Type)
    (using sc: Scope, rp: Reporter, so: Source, oc: Occurs)
  : Pattern =

    (pat: @unchecked) match
      case id: Ast.Ident =>
        transformIdentPattern(id, scrutType)

      case tag: Ast.Tag =>
        transformTagPattern(tag, Nil, scrutType, pat.span)

      case Ast.TypeAscribe(id: Ast.Ident, tpt) =>
        transformTypePattern(id, tpt, scrutType, pat.span)

      case Ast.Apply(id: Ast.Ident, args) =>
        transformApplyPattern(id, args, scrutType, pat.span)

      case Ast.Apply(tag: Ast.Tag, nested) =>
        transformTagPattern(tag, nested, scrutType, pat.span)
    end match
  end transformPattern

end PatternTyper

object PatternTyper:
  class Occurs:
    private var census: Map[Symbol, SourcePosition] = Map.empty

    def occur(symbol: Symbol, pos: SourcePosition)(using rp: Reporter, so: Source) =
      if census.contains(symbol) then
        Reporter.error(s"The parameter $symbol occurred more than once in the patterns", pos)
      else
        census = census.updated(symbol, pos)

    def checkOccur(symbol: Symbol)(using rp: Reporter) =
      if !census.contains(symbol) then
        Reporter.error(s"The parameter $symbol should occur once in the patterns", symbol.sourcePos)
