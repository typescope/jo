package typing

import ast.Ast
import ast.Positions.*

import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import Namer.Scope
import Inference.TargetType

import reporting.Reporter

import scala.collection.mutable

class PatternTyper(namer: Namer, checker: Checker):
  def transformPatDef(patdef: Ast.PatDef)(using sc: Scope, rp: Reporter, so: Source): DelayedDef[FunDef] = ???

  def transform(patmat: Ast.Match)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val Ast.Match(scrutinee, cases) = patmat
    val scrutinee2 = namer.transform(scrutinee)(using sc, rp, so, TargetType.ValueType)
    val scrutType = scrutinee2.tpe

    val cases2 = for caseDef <- cases yield transformCase(caseDef, scrutType)
    val commonType = (cases2: @unchecked) match
      case caseDef :: rest =>
        rest.foldLeft(caseDef.body.tpe): (acc, item) =>
          checker.commonResultType(acc, item.body.tpe, item.body.pos)

    Match(scurtinee2, cases2)(commonType, patmat.span)

  private def transformCase(cases: Ast.Case, scrutType: Type)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Case =
    val caseScope = sc.fresh()

    val Ast.Case(pat, body) = caseDef
    val pat2 = transformPattern(pat, scrutType)(using caseScope)
    val body2 = transform(body)(using caseScope)
    Case(pat2, body2)(caseDef.span)

  private def transformApplyPattern(id: Ast.Ident, args: List[Ast.Word], scrutType: Type)(using sc: Scope, rp: Reporter, so: Source): Pattern =
    val sym = caseScope.resolvePattern(name, id.pos)
    // if sym.info.isPro
    // ApplyPattern(Ident(sym)(id.span), nested = Nil)
    ???

  private def transformPattern(pat: Ast.Word, scrutType: Type)(using sc: Scope, rp: Reporter, so: Source): Pattern =
    (pat: @unchecked) match
      case id @ Ast.Ident(name) =>
        if id.isCapitalized then
          transformApplyPattern(id, Nil, scrutType)
        else
          val sym = Symbol.createPatternSymbol(name, scrutType, sc.owner, pat.pos)
          sc.definePatternAsTerm(sym)

          val patVal = Ident(sym)(id.span)
          val wildcard = WildcardPattern()(scrutType, id.span)
          AscribePattern(patVal, wildcard)

      case Ast.TypeAscribe(Ast.Ident(name), tpt) =>
        val tpt = namer.transformType(tpt)
        val tpe = tpt.tpe

        if Subtyping.conforms(tpe, scrutType) then
          val sym = Symbol.createPatternSymbol(name, tpe, sc.owner, pat.pos)
          sc.definePatternAsTerm(sym)

          val patVal = Ident(sym)(id.span)
          AscribePattern(patVal, TypePattern(tpt))
        else
          Reporter.error("The type is not a subtype of the scrutinee. ", tpt.pos)
          WildcardPattern()(ErrorType, pat.span)

      case Ast.Apply(id, args) =>
        transformApplyPattern(id, args, scrutType)

      case Ast.Apply(tag @ Ast.Tag(id), nested) =>
        def checkNested(tagType: TagType): Pattern =
          val paramTypes = tagType.paramTypes
          val paramCount = paramTypes.size
          val argCount = nested.size

          if argCount > paramCount then
            Reporter.error(s"The tag type ${id.name} in scrutinee has $paramCount parameters, supplied = $argCount", tag.pos)
            WildcardPattern()(ErrorType, pat.span)

          else
            val nested2 =
              for (pat, paramType) <- nested.zip(paramTypes) yield transformPattern(pat, paramType)

            val tagStringLit = StringLit(id.name)(Definitions.instance.StringType, tag.span)
            val tagTypeActual = TagType(id.name, nested2.map(_.tpe))
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

        else
          Reporter.error(s"The tag ${id.name} does not match the scrutinee type ${scrutType.show}", tag.pos)
          WildcardPattern()(ErrorType, pat.span)
