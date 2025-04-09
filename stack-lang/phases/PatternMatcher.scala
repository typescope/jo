package phases

import ast.Positions.*

import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import reporting.Reporter

import scala.collection.mutable

class PatternMatcher(using rp: Reporter) extends Phase[Symbol]:
  val contextObject = Phase.OwnerContext

  val IntType = Definitions.instance.IntType
  val BoolType = Definitions.instance.BoolType
  val StringType = Definitions.instance.StringType

  val abortSym = Definitions.instance.Predef_abort
  val eitherSym = Definitions.instance.Predef_either
  val bothSym = Definitions.instance.Predef_both
  val andSym = Definitions.instance.Predef_and
  val eqlSym = Definitions.instance.Predef_eql

  def transform(patmat: Match)(using owner: Context): Word =
    val Match(scrutinee, cases) = patmat
    val scrutType = scrutinee.tpe

    given Source = owner.sourcePos.source

    val scrutSym = Symbol.createValueSymbol("scrutinee", scrutType, owner, scrutinee.pos)
    val scrutIdent = Ident(scrutSym)(scrutinee.span)
    val vdefScrut = ValDef(scrutSym, scrutinee2)(scrutinee.span)

    def transformCases(cases: List[Case]): Word =
      cases match
        case caseDef :: rest =>
          transformCase(scrutIdent, caseDef, () => transformCases(rest))

        case Nil =>
          // No need to abort if we issue error for non-exhaustive cases.
          // It is needed for code generation.
          val abort = Ident(abortSym)(scrutIdent.span)
          val arg = StringLit("Unhandled match at " + scrutIdent.pos)(StringType, scrutIdent.span)
          // TODO: adapt to result type for Void
          Apply(abort, arg :: Nil)(BottomType, patmat.span)
      end match

    val body = transformCases(cases)
    Block(vdefScrut :: body :: Nil)(body.tpe, patmat.span)

  private def transformCase(scrut: Ident, caseDef: Case, cont: () => Word) (using owner: Context, source: Source): Word =
    If(cond, body, cont())(body.tpe, caseDef.span)

  private def transformPattern(scrut: Ident, pat: Pattern)(using Context, Source): Word =
    pat match
      case AscribePattern(id, nested) =>
        val cond = transformPattern(scrut, nested)
        // It is more performant to always assign
        val assign = Assign(id, scrut)(pat.span)
        Block(assign :: cond :: Nil)(BoolType, pat.span)

      case TypePattern(tpt) =>
        transformTypePattern(scrut, tpt.tpe, tpt.span)

      case tagPat: TagPattern =>
        transformTagPattern(scrut, tagPat)

      case ApplyPattern(pred, nested) =>
        ???

      case WildcardPattern() =>
        assert(Subtyping.conforms(scrut.tpe, pat.tpe), "scrutee type = " + scrut.tpe.show + ", pattern type = " + pat.tpe.show)
        BoolLit(true)(BoolType, pat.span)

  private def transformTagPattern(scrut: Ident, tagPattern: TagPattern)(using Context, Source): Word =
    val tag = tagPattern.tag
    val scrutTagType = scrutineeTagType(scrut.tpe, tag)

    if tagPattern.nested.size > scrutTagType.params.size then
      Report.error(s"The tag pattern has more arguments than the scrutinee type ${scrutTagType.show}", tagPattern.pos)
      BoolLit(false)(BoolType, span)

    else
      val assignTag =  scrutineeTagAssign(scrut, span)
      val condTag = TaggedEncoding.testTagValue(assignTag.ident, tag, tagPattern.tagTree.span)

      val assigns =
        for param <- scrutTagType.params
        yield
          val valueSym = Symbol.createValueSymbol(param.name, param.info, owner, span.toPos)
          val valueIdent = Ident(valueSym)(pat.span)
          Assign(valueIdent, TaggedEncoding.selectVariantField(scrut, scrutTagType, param.name, span))(span)

      if tagPattern.nested.isEmpty then
        condTag

      else
        val nestedConds =
          for (pattern, Assign(id, _)) <- tagPattern.nested.zip(assigns)
          yield transformPattern(id, pattern)

        val head :: rest = nestedConds: @unchecked

        val nestedCond =
          rest.foldLeft(head): (acc, cond) =>
            Apply(Ident(bothSym)(span), acc :: cond :: Nil))(span)

        val nestedBlock = Block(assigns :+ nestedCond)(BoolType, span)

        Apply(Ident(andSym)(span), condTag :: nestedBlock :: Nil)(BoolType, span)

  private def transformTypePattern(scrut: Ident, tpe: Type, span: Span)(using Context, Source): Word =
    if tpe.isTagType then
      val assignTag =  scrutineeTagAssign(scrut, span)
      val cond = transformTagTypePattern(scrut, tpt.tpe.asTagType, assignTag.ident, span)
      Block(assignTag :: cond :: Nil)(BoolType, span)

    else if tpt.tpe.isUnionType then
      assert(scrut.tpe.isUnionType, "expect union type, found = " + scrut.tpe.show)

      val unionType = tpt.tpe.asUnionType

      val assignTag =  scrutineeTagAssign(scrut, span)
      val conds =
        for tagType <- unionType.branches
        yield transformTagTypePattern(scrut, tagType, assignTag.ident, span)

      val cond :: rest = conds: @unchecked

      val eitherFun = Ident(eitherSym)(span)
      val condAll = rest.foldLeft(cond): (acc, cond) =>
        Apply(eitherFun, cond :: cond2 :: Nil)(BoolType, span)

      Block(assignTag :: condAll :: Nil)(BoolType, span)

    else
      assert(Subtyping.conforms(scrut.tpe, tpt.tpe), "scrutee type = " + scrut.tpe.show + ", type test = " + tpt.tpe.show)
      BoolLit(true)(BoolType, tpt.span)

  private def transformTagTypePattern
    (scrut: Ident, patternType: TagType, scrutTagIdent: Ident, span: Span)
    (using owner: Context, source: Source)
  : Word =

    val tag = patternType.tag
    val scrutType = scrut.tpe
    val scrutTagType = scrutineeTagType(scrutType, tag)

    if patternType.params.size > scrutTagType.params.size then
      Report.error(s"The tag type ${patternType.show} in the pattern has more params than the scrutinee type ${scrutTagType.show}", span.toPos)
      BoolLit(false)(BoolType, span)

    else
      val condTag = TaggedEncoding.testTagValue(scrutTagIdent, tag, span)

      val assigns =
        for param <- scrutTagType.params
        yield
          val valueSym = Symbol.createValueSymbol(param.name, param.info, owner, span.toPos)
          val valueIdent = Ident(valueSym)(pat.span)
          Assign(valueIdent, TaggedEncoding.selectVariantField(scrut, scrutTagType, param.name, span))(span)

      if patternType.params.isEmpty then
        condTag

      else
        val nestedConds =
          for (param, Assign(id, _)) <- patternType.params.zip(assigns)
          yield transformTypePattern(id, param.info, span)

        val head :: rest = nestedConds: @unchecked

        val nestedCond =
          rest.foldLeft(head): (acc, cond) =>
            Apply(Ident(bothSym)(span), acc :: cond :: Nil))(span)

        val nestedBlock = Block(assigns :+ nestedCond)(BoolType, span)

        Apply(Ident(andSym)(span), condTag :: nestedBlock :: Nil)(BoolType, span)

  private def scrutineeTagType(scrutType: Type, tag): TagType =
    if scrutType.isTagType then
      scrutType.asTagType
    else
      assert(scrutType.isUnionType, "expect union type, found = " + scrutTye.show)
      val scrutUnionType = scrutType.asUnionType
      assert(scrutUnionType.hasTag(tag), s"expect union type with tag $tag, found = " + scrutUnionType.show)
      scrutUnionType.tagType(tag)

  private def scrutineeTagAssign(scrut: Ident, span: Span)(using owner: Context, source: Source): Assign =
    val encodedScrutType = RecordType(NamedInfo("tag", IntType) :: Nil)
    val encodedScrut = Encoded(scrut)(encodedScrutType)

    val tagSym = Symbol.createValueSymbol("tag", IntType, owner, span.toPos)
    val tagIdent = Ident(tagSym)(span)
    Assign(tagIdent, Select(encodedScrut, "tag")(IntType, span))(span)
