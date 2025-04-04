package typing

import ast.Ast
import ast.Positions.*

import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import Namer.{ Scope, DelayedDef }
import Inference.TargetType

import reporting.Reporter

class PatternTyper(namer: Namer, checker: Checker):
  def transformPatDef(patDef: Ast.PatDef)(using sc: Scope, rp: Reporter, so: Source): DelayedDef[PatDef] =
    val patSym = Symbol.createPatternSymbol(patDef.name, namer.nonCyclicTypeProvider, sc.owner, patDef.ident.pos)
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
        val sym = Symbol.createTypeParamSymbol(tparam.name, infoProvider, patSym, tparam.pos)
        patScope.define(sym)
        sym

    lazy val paramSyms =
      tparamSyms

      for param <- patDef.params yield
        val tpt = namer.transformType(param.typ)(using patScope)
        val paramSym = Symbol.createPatternSymbol(param.name, tpt.tpe, patSym, param.pos)
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
      given Scope = patScope
      transformPattern(patDef.body, AnyType)

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

    Match(scrutinee2, cases2)(commonType, patmat.span)

  private def transformCase(caseDef: Ast.Case, scrutType: Type)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Case =
    val caseScope = sc.fresh()

    val Ast.Case(pat, body) = caseDef
    val pat2 = transformPattern(pat, scrutType)(using caseScope)
    val body2 = namer.transform(body)(using caseScope)
    Case(pat2, body2)(caseDef.span)

  private def transformApplyPattern(id: Ast.Ident, args: List[Ast.Word], scrutType: Type)(using sc: Scope, rp: Reporter, so: Source): Pattern =
    val sym = sc.resolvePattern(id.name, id.pos)

    var fun: Word = Ident(sym)(id.span)

    if fun.tpe.isPolyType then
      fun = namer.exprTyper.instantiatePoly(fun.tpe.asProcType, fun)

    val span = if args.isEmpty then id.span else id.span | args.last.span
    val funType = fun.tpe

    if funType.isProcType then
      val procType = funType.asProcType
      val paramSize = procType.paramTypes.size
      val resType = procType.resultType

      if Subtyping.conforms(resType, scrutType) then
        if args.size != paramSize then
          Reporter.error(s"The pattern predicate expects $paramSize arguments, found = ${args.size}", id.pos)
          WildcardPattern()(ErrorType, id.span)
        else
          val argsTyped =
            for (arg, paramType) <- args.zip(procType.paramTypes) yield
              transformPattern(arg, paramType)

          ApplyPattern(fun, argsTyped)(resType, span)
        end if
      else
        Reporter.error(s"The pattern predicate result type ${resType.show} does not conform to scrutinee type ${scrutType.show}", id.pos)
        WildcardPattern()(ErrorType, span)

    else
      Reporter.error(s"Not a function: " + fun.tpe.show, id.pos)
      WildcardPattern()(ErrorType, span)

  private def transformPattern(pat: Ast.Word, scrutType: Type)(using sc: Scope, rp: Reporter, so: Source): Pattern =
    (pat: @unchecked) match
      case id @ Ast.Ident(name) =>
        if id.isCapitalized then
          transformApplyPattern(id, Nil, scrutType)

        else if sc.owner.isPattern then
          val sym = sc.resolvePattern(name, id.pos)

          if Subtyping.conforms(sym.info, scrutType) then
            val patVal = Ident(sym)(id.span)
            AscribePattern(patVal, TypePattern(TypeTree(sym.info)(id.span)))

          else
            Reporter.error(s"$sym is not a subtype of the scrutinee type " + scrutType.show, id.pos)
            WildcardPattern()(ErrorType, pat.span)

        else
          val sym = Symbol.createPatternSymbol(name, scrutType, sc.owner, pat.pos)
          sc.definePatternAsTerm(sym)

          val patVal = Ident(sym)(id.span)
          val wildcard = WildcardPattern()(scrutType, id.span)
          AscribePattern(patVal, wildcard)

      case Ast.TypeAscribe(id @ Ast.Ident(name), tpt) =>
        val tpt2 = namer.transformType(tpt)
        val tpe = tpt2.tpe

        if Subtyping.conforms(tpe, scrutType) then
          if sc.owner.isPattern then
            val sym = sc.resolvePattern(name, id.pos)

            if Subtyping.conforms(tpe, sym.info) then
              val patVal = Ident(sym)(id.span)
              AscribePattern(patVal, TypePattern(tpt2))

            else
              Reporter.error(s"${tpe.show} not a subtype of $sym. The latter has type " + sym.info.show, tpt.pos)
              WildcardPattern()(ErrorType, pat.span)

          else
            val sym = Symbol.createPatternSymbol(name, tpe, sc.owner, pat.pos)
            sc.definePatternAsTerm(sym)

            val patVal = Ident(sym)(id.span)
            AscribePattern(patVal, TypePattern(tpt2))

        else
          Reporter.error("The type is not a subtype of the scrutinee. ", tpt.pos)
          WildcardPattern()(ErrorType, pat.span)

      case Ast.Apply(id: Ast.Ident, args) =>
        transformApplyPattern(id, args, scrutType)

      case Ast.Apply(tag @ Ast.Tag(id), nested) =>
        def checkNested(tagType: TagType): Pattern =
          val paramTypes = tagType.paramTypes
          val paramCount = paramTypes.size
          val argCount = nested.size

          if argCount != paramCount then
            Reporter.error(s"The tag type ${id.name} in scrutinee has $paramCount parameters, supplied = $argCount", tag.pos)
            WildcardPattern()(ErrorType, pat.span)

          else
            val nested2 =
              for (pat, paramType) <- nested.zip(paramTypes) yield transformPattern(pat, paramType)

            val tagStringLit = StringLit(id.name)(Definitions.instance.StringType, tag.span)
            val tagTypeActual = TagType.from(id.name, nested2.map(_.tpe))
            TagPattern(tagStringLit, nested2)(tagTypeActual)

        if scrutType.isUnionType then
          val unionType = scrutType.asUnionType
           if !unionType.hasTag(id.name) then
             Reporter.error(s"The tag ${id.name} does not exist in union type ${unionType.show}", tag.pos)
             WildcardPattern()(ErrorType, pat.span)

           else
             val tagType = unionType.tagType(id.name)
             checkNested(tagType)

        else if scrutType.isTagType then
          val tagType = scrutType.asTagType
          if tagType.tag != id.name then
            Reporter.error(s"The tag ${id.name} does not match the scrutinee type ${tagType.show}", tag.pos)
            WildcardPattern()(ErrorType, pat.span)
          else
            checkNested(tagType)

        else if scrutType.isAnyType then
          val tagType = TagType.from(id.name, nested.map(_ => AnyType))
          checkNested(tagType)

        else
          Reporter.error(s"The tag ${id.name} does not match the scrutinee type ${scrutType.show}", tag.pos)
          WildcardPattern()(ErrorType, pat.span)
