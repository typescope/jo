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
        if tpt.tpe.isTagType then
          val encodedScrutType = RecordType(NamedInfo("tag", IntType) :: Nil)
          val encodedScrut = Encoded(scrutIdent)(encodedScrutType)

          val tagSym = Symbol.createValueSymbol("tag", IntType, owner, pat.pos)
          val tagIdent = Ident(tagSym)(scrut.span)
          val assignTag = Assign(tagSym, Select(encodedScrut, "tag")(IntType, scrut.span))(pat.span)

          val cond = transformTypePattern(scrut, tpt.tpe.asTagType, tagIdent, pat)
          Block(assignTag :: cond :: Nil)(BoolType, pat.span)

        else if tpt.tpe.isUnionType then
          assert(scrut.tpe.isUnionType, "expect union type, found = " + scrut.tpe.show)

          val unionType = tpt.tpe.asUnionType

          val encodedScrutType = RecordType(NamedInfo("tag", IntType) :: Nil)
          val encodedScrut = Encoded(scrutIdent)(encodedScrutType)

          val tagSym = Symbol.createValueSymbol("tag", IntType, owner, pat.pos)
          val tagIdent = Ident(tagSym)(scrut.span)
          val assignTag = Assign(tagSym, Select(encodedScrut, "tag")(IntType, scrut.span))(pat.span)

          val cond :: conds =
            for tagType <- unionType.branches
            yield transformTypePattern(scrut, tagType, tagIdent, pat)

          val eitherFun = Ident(eitherSym)(span)
          val condAll = conds.foldLeft(cond): (acc, cond) =>
            Apply(eitherFun, cond :: cond2 :: Nil)(BoolType, pat.span)

          Block(assignTag :: condAll :: Nil)(BoolType, pat.span)

        else
          assert(Subtyping.conforms(scrut.tpe, tpt.tpe), "scrutee type = " + scrut.tpe.show + ", type test = " + tpt.tpe.show)
          BoolLit(true)(BoolType, pat.span)

      case tagPat: TagPattern =>

        val Case(pat, body) = caseDef
        val cond = transformPattern(pat)

      case ApplyPattern(pred, nested) =>
        ???

      case WildcardPattern() =>
        assert(Subtyping.conforms(scrut.tpe, pat.tpe), "scrutee type = " + scrut.tpe.show + ", pattern type = " + pat.tpe.show)
        BoolLit(true)(BoolType, pat.span)

  private def transformTypePattern(scrut: Ident, tpe: TagType, scrutTagIdent: Ident, pat: Pattern)(using Context, Source): Word = ???
