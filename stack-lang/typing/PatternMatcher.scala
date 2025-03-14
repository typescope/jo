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

class PatternMatcher(namer: Namer, checker: Checker):
  def transform(patmat: Ast.Match)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val sc2 = sc.fresh()

    val Ast.Match(scrutinee, cases) = patmat
    val scrutinee2 = namer.transform(scrutinee)(using sc, rp, so, TargetType.ValueType)

    val scrutType = scrutinee2.tpe
    val scrutSym = Symbol.createValueSymbol("scrutinee", scrutType, sc.owner, scrutinee2.pos)
    val scrutIdent = Ident(scrutSym)(scrutinee.span)
    val bind = ValDef(scrutSym, scrutinee2)(scrutinee.span)
    sc2.define(scrutSym)

    val allTags = if scrutType.isUnionType then scrutType.asUnionType.tags else Nil

    def subtractPattern(tags: List[String], pat: Ast.Word): List[String] =
      if tags.isEmpty then
        Reporter.error("The case is unreachable", pat.pos)
        Nil
      else (pat: @unchecked) match
        case Ast.Ident(_) => Nil
        case Ast.Apply(Ast.Tag(Ast.Ident(name)), _) =>
          if tags.contains(name) then
            tags.filter(_ != name)
          else
            if allTags.contains(name) then
              Reporter.error("The case is unreachable", pat.pos)
            tags

    def transformCases(cases: List[Ast.Case], resType: Type, tagsRest: List[String]): Word =
      cases match
        case caseDef :: rest =>
          val tagsRest2 = subtractPattern(tagsRest, caseDef.pat)
          transform(scrutIdent, caseDef, resType, tp => transformCases(rest, tp, tagsRest2))(using sc2)

        case Nil =>
          if tagsRest.nonEmpty then
            Reporter.error("Unmatched case(s): " + tagsRest.mkString(", "), scrutIdent.pos)

          // abort
          val abortSym = Definitions.instance.Predef_abort
          val stringType = Definitions.instance.StringType
          val abort = Ident(abortSym)(scrutIdent.span)
          val arg = Literal(Constant.String("Unhandled match at " + scrutIdent.pos))(stringType, scrutIdent.span)
          val app = Apply(abort, arg :: Nil)(BottomType, patmat.span)
          checker.adapt(app, resType)

      end match

    val body = transformCases(cases, BottomType, allTags)
    Block(bind :: body :: Nil)(body.tpe, patmat.span)

  private def transform
      (scrut: Ident, caseDef: Ast.Case, resType: Type, cont: Type => Word)
      (using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =

    val caseScope = sc.fresh()

    val Ast.Case(pat, body) = caseDef
    val scrutSpan = scrut.span
    val scrutType = scrut.tpe

    (pat: @unchecked) match
      case Ast.Ident(name) =>
        val sym = Symbol.createValueSymbol(name, scrutType, sc.owner, pat.pos)
        val vdef = ValDef(sym, scrut)(pat.span)
        caseScope.define(sym)

        val body2 = namer.transform(body)(using caseScope)
        val commonType = checker.commonResultType(body2.tpe, resType, body2.pos)
        val elsep = cont(commonType)

        val block = Block(vdef :: body2 :: Nil)(elsep.tpe, caseDef.span)
        checker.adapt(block, elsep.tpe)

      case Ast.Apply(Ast.Tag(tag), bindings: List[Ast.Ident] @unchecked) =>
        val tagTypesOpt = checker.tagTypes(tag, scrutType, scrutSpan)
        val tagTypes = tagTypesOpt.getOrElse(Nil)

        if tagTypesOpt.isEmpty then
          cont(BottomType)

        else if tagTypes.size != bindings.size then
          Reporter.error(s"The tag takes ${tagTypes.size} arguments, found = ${bindings.size}", tag.pos)
          cont(BottomType)

        else
          val encodeType = Desugaring.encodeUnionType(tagTypes)
          val encodedScrut = Encoded(scrut)(encodeType)

          val vals = mutable.ArrayBuffer.empty[ValDef]
          for (binding, i) <- bindings.zipWithIndex if binding.name != "_" do
            val arg = Desugaring.selectVariantArg(encodedScrut, i, binding.span)
            val sym = Symbol.createValueSymbol(binding.name, arg.tpe, sc.owner, arg.pos)
            vals += ValDef(sym, arg)(binding.span)
            caseScope.define(sym)

          val tagIndex =
            if tagTypesOpt.isEmpty then -1
            else scrutType.asUnionType.tagIndex(tag.name)

          val cond = Desugaring.testVariantTag(encodedScrut, tagIndex, tag.span)
          val body2 = namer.transform(body)(using caseScope, rp, so, tt)
          val commonType = checker.commonResultType(body2.tpe, resType, body2.pos)
          val elsep = cont(commonType)
          val commonType2 = checker.commonResultType(body2.tpe, elsep.tpe, body2.pos)
          val adapted = checker.adapt(body2, commonType2)

          val body3 = Block(vals.toList :+ adapted)(adapted.tpe, caseDef.span)
          If(cond, body3, elsep)(body3.tpe, caseDef.span)
