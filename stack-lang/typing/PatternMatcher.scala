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

    if !scrutType.isUnionType then
      Reporter.error("Expect a union type, found = " + scrutType.show, scrutinee.pos)
      return Namer.errorWord(patmat.span)

    val unionType = scrutType.asUnionType
    val allTags = unionType.tags

    val scrutSym = Symbol.createValueSymbol("scrutinee", scrutType, sc.owner, scrutinee2.pos)
    val scrutIdent = Ident(scrutSym)(scrutinee.span)
    val vdefScrut = ValDef(scrutSym, scrutinee2)(scrutinee.span)

    val IntType = Definitions.instance.IntType

    val encodedScrutType = RecordType(NamedInfo("tag", IntType) :: Nil)
    val encodedScrut = Encoded(scrutIdent)(encodedScrutType)

    val tagSym = Symbol.createValueSymbol("tag", IntType, sc.owner, scrutinee2.pos)
    val tagIdent = Ident(tagSym)(scrutinee.span)
    val vdefTag = ValDef(tagSym, Select(encodedScrut, "tag")(IntType, scrutinee.span))(scrutinee.span)

    def subtractPattern(tags: List[String], pat: Ast.Word): List[String] =
      if tags.isEmpty then
        Reporter.error("The case is unreachable", pat.pos)
        Nil
      else (pat: @unchecked) match
        case Ast.Ident(_) => Nil

        case Ast.TypeAscribe(_, tpt) =>
          // TODO: avoid re-typing
          val tpe = namer.transformType(tpt).tpe
          val matchedTags =
            if tpe.isTagType then
              tpe.asTagType.tag :: Nil
            else if tpe.isUnionType then
              tpe.asUnionType.tags
            else
              Nil

          var res = tags
          for name <- matchedTags do
            if res.contains(name) then
              res = res.filter(_ != name)
            else if allTags.contains(name) then
              Reporter.error(s"The tag $name is already matched in previous cases", pat.pos)
          end for
          res

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
          transformCase(scrutIdent, tagIdent, unionType, caseDef, resType, tp => transformCases(rest, tp, tagsRest2))(using sc2)

        case Nil =>
          if tagsRest.nonEmpty then
            Reporter.error("Unmatched case(s): " + tagsRest.mkString(", "), scrutIdent.pos)

          // No need to abort if we issue error for non-exhaustive cases.
          // It is needed for code generation.
          val abortSym = Definitions.instance.Predef_abort
          val stringType = Definitions.instance.StringType
          val abort = Ident(abortSym)(scrutIdent.span)
          val arg = Literal(Constant.String("Unhandled match at " + scrutIdent.pos))(stringType, scrutIdent.span)
          val app = Apply(abort, arg :: Nil)(BottomType, patmat.span)
          checker.adapt(app, resType)

      end match

    val body = transformCases(cases, BottomType, allTags)
    Block(vdefScrut :: vdefTag :: body :: Nil)(body.tpe, patmat.span)

  private def transformCase
      (scrut: Ident, tagIdent: Ident, unionType: UnionType, caseDef: Ast.Case, resType: Type, cont: Type => Word)
      (using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =

    val caseScope = sc.fresh()

    val Ast.Case(pat, body) = caseDef

    (pat: @unchecked) match
      case Ast.Ident(name) =>
        val sym = Symbol.createValueSymbol(name, unionType, sc.owner, pat.pos)
        val vdef = ValDef(sym, scrut)(pat.span)
        caseScope.define(sym)

        val body2 = namer.transform(body)(using caseScope)
        val commonType = checker.commonResultType(body2.tpe, resType, body2.pos)
        val elsep = cont(commonType)

        val block = Block(vdef :: body2 :: Nil)(elsep.tpe, caseDef.span)
        checker.adapt(block, elsep.tpe)

      case Ast.TypeAscribe(Ast.Ident(name), tpt) =>
        val tpe = namer.transformType(tpt).tpe

        def transform(tags: List[String]): Word =
          val sym = Symbol.createValueSymbol(name, tpe, sc.owner, pat.pos)
          val vdef = ValDef(sym, Encoded(scrut)(tpe))(pat.span)
          caseScope.define(sym)

          val cond = TaggedEncoding.testTagValues(tagIdent, tags, pat.span)
          val body2 = namer.transform(body)(using caseScope, rp, so, tt)
          val commonType = checker.commonResultType(body2.tpe, resType, body2.pos)
          val elsep = cont(commonType)
          val commonType2 = checker.commonResultType(body2.tpe, elsep.tpe, body2.pos)
          val adapted = checker.adapt(body2, commonType2)

          val body3 = Block(vdef :: adapted :: Nil)(adapted.tpe, caseDef.span)
          If(cond, body3, elsep)(body3.tpe, caseDef.span)

        if tpe.isUnionType then
          if Subtyping.conforms(tpe, unionType) then
            val tags = tpe.asUnionType.tags
            transform(tags)
          else
            val explain = "Union type must be a prefix of the scrutinee type in type patterns"
            Reporter.error("The type is not a subtype of the scrutinee. " + explain, tpt.pos)
            Namer.errorWord(caseDef.span)

        else if tpe.isTagType then
          val tagType = tpe.asTagType
          if !unionType.hasTag(tagType.tag) then
            Reporter.error(s"The scrutinee of the type ${unionType.show} does not contain the tag ${tagType.tag}", tpt.pos)
            Namer.errorWord(caseDef.span)

          else if !Subtyping.conforms(tpe, unionType) then
            val tagType2 = unionType.tagType(tagType.tag)
            Reporter.error(s"The tag type ${tagType} does not match the same tag in scrutinee ${tagType2.show}", tpt.pos)
            Namer.errorWord(caseDef.span)

          else
            transform(tagType.tag :: Nil)

        else
          val explain =
            if tpe.isUnionType then "Union type must be a prefix of the scrutinee type"
            else ""
          Reporter.error("The type is not a subtype of the scrutinee. " + explain, tpt.pos)
          Namer.errorWord(caseDef.span)


      case Ast.Apply(Ast.Tag(tag), bindings: List[Ast.Ident] @unchecked) =>
        if !unionType.hasTag(tag.name) then
          Reporter.error(s"The tag ${tag.name} does not exist in union type ${unionType.show}", tag.pos)
          cont(BottomType)

        else
          val tagType = unionType.tagType(tag.name)
          val tagTypes = tagType.paramTypes

          if tagTypes.size != bindings.size then
            Reporter.error(s"The tag takes ${tagTypes.size} arguments, found = ${bindings.size}", tag.pos)
            return cont(BottomType)

          val vals = mutable.ArrayBuffer.empty[ValDef]
          for (binding, i) <- bindings.zipWithIndex if binding.name != "_" do
            val arg = TaggedEncoding.selectVariantField(scrut, tagType, i, binding.span)
            val sym = Symbol.createValueSymbol(binding.name, arg.tpe, sc.owner, arg.pos)
            vals += ValDef(sym, arg)(binding.span)
            caseScope.define(sym)

          val cond = TaggedEncoding.testTagValue(tagIdent, tag.name, tag.span)
          val body2 = namer.transform(body)(using caseScope, rp, so, tt)
          val commonType = checker.commonResultType(body2.tpe, resType, body2.pos)
          val elsep = cont(commonType)
          val commonType2 = checker.commonResultType(body2.tpe, elsep.tpe, body2.pos)
          val adapted = checker.adapt(body2, commonType2)
          val body3 = Block(vals.toList :+ adapted)(adapted.tpe, caseDef.span)
          If(cond, body3, elsep)(body3.tpe, caseDef.span)
