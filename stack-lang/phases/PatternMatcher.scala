package phases

import ast.Positions.*

import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import scala.collection.mutable

class PatternMatcher(using defn: Definitions) extends Phase[PatternMatcher.Context]:
  val contextObject = PatternMatcher.CacheContext

  val IntType = defn.IntType
  val BoolType = defn.BoolType
  val StringType = defn.StringType

  val abortSym = defn.Predef_abort
  val eitherSym = defn.Predef_either
  val bothSym = defn.Predef_both

  override def transform(nss: List[Namespace]): List[Namespace] =
    val implMap = mutable.Map.empty[Symbol, Symbol]

    for ns <- nss yield
      given Context = PatternMatcher.Context(implMap, ns.symbol)
      super.transformNamespace(ns)

  override def transformTopLevelDefs(defs: List[Def])(using ctx: Context): List[Def] =
    defs.map:
      case pdef: PatDef =>
        implementPatDef(pdef)

      case defn =>
        super.transformTopLevelDef(defn)

  private def createImplFunSymbol(predSym: Symbol): Symbol =
    val predType = predSym.info.asProcType
    val bounds = predType.tparams
    val params = NamedInfo("scrutinee", predType.resultType.stripPartial) :: Nil

    val successType = TagType("Success", predType.params)
    val failType = TagType("Fail", Nil)
    val resultType = UnionType(successType :: failType :: Nil)
    val receives = Some(Nil)

    val funType = ProcType(bounds, params, resultType, receives, preParamCount = 0)
    Symbol.createSymbol(predSym.name + "$impl", funType, Flags.Fun | Flags.Synthetic, predSym.owner, predSym.sourcePos)

  private def getImplFunSymbol(predSym: Symbol, implMap: mutable.Map[Symbol, Symbol]): Symbol =
    implMap.get(predSym) match
      case Some(implSym) =>
        implSym

      case None =>
        val implSym = createImplFunSymbol(predSym)
        implMap(predSym) = implSym
        implSym

  private def implementPatDef(pdef: PatDef)(using ctx: Context): FunDef =
    val implSym = getImplFunSymbol(pdef.symbol, ctx.implMap)
    val span = pdef.body.span

    given Context = PatternMatcher.CacheContext.newContext(implSym, ctx)
    given Source = pdef.symbol.sourcePos.source

    val scrutSym = Symbol.createSymbol("scrutinee", pdef.resultType.tpe.stripPartial, Flags.Param, implSym, implSym.sourcePos)
    val scrutIdent = Ident(scrutSym)(span)

    // TODO: optimize irrefutable pattern
    val cond = transformPattern(scrutIdent, pdef.body)
    val successType = TagType("Success", pdef.params.map(_.toNamedInfo))
    val failType = TagType("Fail", Nil)
    val resultType = UnionType(successType :: failType :: Nil)

    val values = pdef.params.map(param => Ident(param)(span))
    val success = TaggedLit(StringLit("Success")(span), values)(successType, span)
    val fail = TaggedLit(StringLit("Fail")(span), args = Nil)(failType, span)
    val body = If(cond, success, fail)(resultType, span)

    // TODO: rebind param symbols
    val tpt = TypeTree(resultType)(pdef.resultType.span)
    FunDef(implSym, pdef.tparams, scrutSym :: Nil, tpt, body)(pdef.span)

  override def transformNestedPatDef(pdef: PatDef)(using ctx: Context): Word =
    implementPatDef(pdef)

  override def transformMatch(patmat: Match)(using ctx: Context): Word =
    val Match(scrutineeRaw, cases) = patmat
    val scrutinee = transform(scrutineeRaw)
    val scrutType = scrutinee.tpe

    given Source = ctx.owner.sourcePos.source

    var aliased: Boolean = false
    val scrutIdent: Ident =
      scrutinee match
        case id: Ident =>
          id

        case Encoded(id: Ident) =>
          id

        case _ =>
          aliased = true
          val scrutSym = Symbol.createSymbol("scrutinee", scrutType, Flags.Synthetic, ctx.owner, scrutinee.pos)
          Ident(scrutSym)(scrutinee.span)

    def transformCases(cases: List[Case]): Word =
      cases match
        case caseDef :: rest =>
          transformCase(scrutIdent, caseDef, () => transformCases(rest))

        case Nil =>
          // No need to abort if we issue error for non-exhaustive cases.
          // It is needed for code generation.
          val abort = Ident(abortSym)(scrutIdent.span)
          val arg = StringLit("Unhandled match at " + scrutIdent.pos)(scrutIdent.span)
          Apply(abort, arg :: Nil)(BottomType, patmat.span).dropIfVoid(patmat.tpe)
      end match

    val body = transformCases(cases)

    if aliased then
      val scrutAssign = Assign(scrutIdent, scrutinee)(scrutinee.span)
      Block(scrutAssign :: body :: Nil)(body.tpe, patmat.span)

    else
      body

  private def transformCase(scrut: Ident, caseDef: Case, cont: () => Word) (using ctx: Context, source: Source): Word =
    val cond = transformPattern(scrut, caseDef.pattern)
    // TODO: optimize irrefutable patterns
    If(cond, transform(caseDef.body), cont())(caseDef.body.tpe, caseDef.span)

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

      case appPat: ApplyPattern =>
        transformApplyPattern(scrut, appPat)

      case orPat: OrPattern =>
        transformOrPattern(scrut, orPat)

      case valuePattern: ValuePattern =>
        transformValuePattern(scrut, valuePattern)

      case GuardPattern(pattern, guard) =>
        val cond = transformPattern(scrut, pattern)
        If(cond, guard, BoolLit(false)(pat.span))(BoolType, pat.span)

      case TermBindingPattern(pattern, bindings) =>
        val cond = transformPattern(scrut, pattern)
        val nestedBlock = Block(bindings :+ BoolLit(true)(pat.span))(BoolType, pat.span)
        If(cond, nestedBlock, BoolLit(false)(pat.span))(BoolType, pat.span)

      case WildcardPattern() =>
        assert(Subtyping.conforms(scrut.tpe, pat.tpe), "scrutee type = " + scrut.tpe.show + ", pattern type = " + pat.tpe.show)
        BoolLit(true)(pat.span)

  private def transformOrPattern(scrut: Ident, orPattern: OrPattern)
    (using ctx: Context, source: Source)
  : Word =

    val OrPattern(lhs, rhs) = orPattern
    val lhsCond = transformPattern(scrut, lhs)
    val rhsCond = transformPattern(scrut, rhs)
    If(lhsCond, BoolLit(true)(lhs.span), rhsCond)(BoolType, orPattern.span)

  private def transformValuePattern(scrut: Ident, pat: ValuePattern): Word =
    pat.value.tpe match
      case defn.ByteType   =>
        Ident(defn.Predef_eql)(pat.span).appliedTo(pat.value, scrut)

      case defn.IntType    =>
        Ident(defn.Predef_eql)(pat.span).appliedTo(pat.value, scrut)

      case defn.CharType   =>
        Ident(defn.Predef_eql)(pat.span).appliedTo(pat.value, scrut)

      case defn.BoolType   =>
        val bothTrue = Ident(defn.Predef_both)(pat.span).appliedTo(pat.value, scrut)
        val notValue = Ident(defn.Predef_not)(pat.span).appliedTo(pat.value)
        val notScrut = Ident(defn.Predef_not)(pat.span).appliedTo(scrut)
        val bothFalse = Ident(defn.Predef_both)(pat.span).appliedTo(notValue, notScrut)
        Ident(defn.Predef_either)(pat.span).appliedTo(bothTrue, bothFalse)

      case defn.StringType =>
        scrut.select("==").appliedTo(pat.value)

      case _ => throw new Exception("Unexpected literal type: " + pat.value.tpe.show)

  private def transformApplyPattern(scrut: Ident, applyPattern: ApplyPattern)
    (using ctx: Context, source: Source)
  : Word =

    val ApplyPattern(pred, nested) = applyPattern
    val procType = pred.tpe.asProcType
    val span = pred.span

    val paramType = procType.resultType.stripPartial
    val successType = TagType("Success", procType.params)
    val failType = TagType("Fail", Nil)
    val resultType = UnionType(successType :: failType :: Nil)

    val noNeedTypeTest = Subtyping.conforms(scrut.tpe, paramType)

    val implFun =
      (pred: @unchecked) match
        case Ident(sym) =>
          val impl = getImplFunSymbol(sym, ctx.implMap)
          Ident(impl)(pred.span)

        case TypeApply(id @ Ident(sym), tpts) =>
          val impl = getImplFunSymbol(sym, ctx.implMap)
          val implProcType = ProcType(Nil, NamedInfo("scrutinee", paramType) :: Nil, resultType, Some(Nil), preParamCount = 0)
          TypeApply(Ident(impl)(id.span), tpts)(implProcType, span)
      end match

    val call =
      if noNeedTypeTest then Apply(implFun, scrut :: Nil)(resultType, span)
      else Apply(implFun, Encoded(scrut)(paramType) :: Nil)(resultType, span)

    val resSym = Symbol.createSymbol("res", resultType, Flags.Pattern | Flags.Synthetic, ctx.owner, pred.pos)
    val assignRes = Assign(Ident(resSym)(pred.span), call)(span)
    val assignTag =  scrutineeTagAssign(assignRes.ident, span)

    // TODO: no test for irrefutable predicate
    val resCond =
      Block(
        assignRes
          :: assignTag
          :: TaggedEncoding.testTagValue(assignTag.ident, "Success", span)
          :: Nil
      )(BoolType, span)

    val callCond =
      if noNeedTypeTest then
        resCond

      else
        val typeTest = transformTypePattern(scrut, paramType, span)
        If(typeTest, resCond, BoolLit(false)(span))(BoolType, span)

    val assigns =
      for param <- successType.params
      yield
        val valueSym = Symbol.createSymbol(param.name, param.info, Flags.Pattern | Flags.Synthetic, ctx.owner, pred.pos)
        val valueIdent = Ident(valueSym)(span)
        Assign(valueIdent, TaggedEncoding.selectVariantField(assignRes.ident, successType, param.name, span))(span)

    if nested.isEmpty then
      callCond
    else
      val nestedConds =
        for (pattern, Assign(id, _)) <- nested.zip(assigns)
        yield transformPattern(id, pattern)

      val head :: rest = nestedConds: @unchecked

      val nestedCond =
        rest.foldLeft(head): (acc, cond) =>
          Apply(Ident(bothSym)(span), acc :: cond :: Nil)(BoolType, span)

      val nestedBlock = Block(assigns :+ nestedCond)(BoolType, span)
      If(callCond, nestedBlock, BoolLit(false)(span))(BoolType, span)

  private def transformTagPattern(scrut: Ident, tagPattern: TagPattern)(using ctx: Context, source: Source): Word =
    val span = tagPattern.span
    val tag = tagPattern.tag
    val scrutTagType = scrutineeTagType(scrut.tpe, tag)

    assert(
      tagPattern.nested.size <= scrutTagType.params.size,
      s"The tag pattern has more arguments than the scrutinee type ${scrutTagType.show}"
    )

    val assignTag =  scrutineeTagAssign(scrut, span)
    val condTag = Block(
      assignTag
      :: TaggedEncoding.testTagValue(assignTag.ident, tag, tagPattern.tagTree.span)
      :: Nil)(BoolType, tagPattern.tagTree.span)

    val assigns =
      for param <- scrutTagType.params
      yield
        val valueSym = Symbol.createSymbol(param.name, param.info, Flags.Pattern | Flags.Synthetic, ctx.owner, span.toPos)
        val valueIdent = Ident(valueSym)(span)
        Assign(valueIdent, TaggedEncoding.selectVariantField(scrut, scrutTagType, param.name, span))(span)

    if tagPattern.nested.isEmpty then
      if needTagTest(scrut, tag) then
        condTag

      else
        BoolLit(true)(span)

    else
      val nestedConds =
        for (pattern, Assign(id, _)) <- tagPattern.nested.zip(assigns)
        yield transformPattern(id, pattern)

      val head :: rest = nestedConds: @unchecked

      val nestedCond =
        rest.foldLeft(head): (acc, cond) =>
          Apply(Ident(bothSym)(span), acc :: cond :: Nil)(BoolType, span)

      val nestedBlock = Block(assigns :+ nestedCond)(BoolType, span)

      if needTagTest(scrut, tag) then
        If(condTag, nestedBlock, BoolLit(false)(span))(BoolType, span)
      else
        nestedBlock

  private def transformTypePattern(scrut: Ident, patternType: Type, span: Span)
    (using Context, Source)
  : Word =

    assert(Subtyping.conforms(patternType, scrut.symbol.info), "scrutee type = " + scrut.tpe.show + ", type test = " + patternType.show)

    if Subtyping.conforms(scrut.symbol.info, patternType) then
      BoolLit(true)(span)

    else if patternType.isTagType then
      val patternTagType = patternType.asTagType

      if needTagTest(scrut, patternTagType.tag) then
        val assignTag = scrutineeTagAssign(scrut, span)
        val cond = transformTagTypePattern(scrut, patternTagType, Some(assignTag.ident), span)
        Block(assignTag :: cond :: Nil)(BoolType, span)

      else
        transformTagTypePattern(scrut, patternTagType, None, span)

    else if patternType.isUnionType then
      assert(scrut.tpe.isUnionType, "expect union type, found = " + scrut.tpe.show)

      val unionType = patternType.asUnionType

      val assignTag =  scrutineeTagAssign(scrut, span)
      val conds =
        for tagType <- unionType.tagTypes
        yield transformTagTypePattern(scrut, tagType, Some(assignTag.ident), span)

      val cond :: rest = conds: @unchecked

      val eitherFun = Ident(eitherSym)(span)
      val condAll = rest.foldLeft(cond): (acc, cond) =>
        Apply(eitherFun, acc :: cond :: Nil)(BoolType, span)

      Block(assignTag :: condAll :: Nil)(BoolType, span)

    else
      throw new Exception("Unexpected tag pattern, scrutee type = " + scrut.tpe.show + ", type test = " + patternType.show)

  private def transformTagTypePattern
    (scrut: Ident, patternType: TagType, scrutTagIdent: Option[Ident], span: Span)
    (using ctx: Context, source: Source)
  : Word =

    val tag = patternType.tag
    val scrutType = scrut.tpe
    val scrutTagType = scrutineeTagType(scrutType, tag)

    assert(
      patternType.params.size <= scrutTagType.params.size,
      s"The tag type ${patternType.show} has more params than the scrutinee type ${scrutTagType.show}"
    )

    val assigns =
      for param <- scrutTagType.params
      yield
        val valueSym = Symbol.createSymbol(param.name, param.info, Flags.Pattern | Flags.Synthetic, ctx.owner, span.toPos)
        val valueIdent = Ident(valueSym)(span)
        Assign(valueIdent, TaggedEncoding.selectVariantField(scrut, scrutTagType, param.name, span))(span)

    if patternType.params.isEmpty then
      scrutTagIdent match
        case Some(tagIdent) =>
          TaggedEncoding.testTagValue(tagIdent, tag, span)

        case _ =>
          BoolLit(true)(span)

    else
      val nestedConds =
        for (param, Assign(id, _)) <- patternType.params.zip(assigns)
        yield transformTypePattern(id, param.info, span)

      val head :: rest = nestedConds: @unchecked

      val nestedCond =
        rest.foldLeft(head): (acc, cond) =>
          Apply(Ident(bothSym)(span), acc :: cond :: Nil)(BoolType, span)

      val nestedBlock = Block(assigns :+ nestedCond)(BoolType, span)

      scrutTagIdent match
        case Some(tagIdent) =>
          val condTag = TaggedEncoding.testTagValue(tagIdent, tag, span)
          If(condTag, nestedBlock, BoolLit(false)(span))(BoolType, span)

        case _ => nestedBlock

  private def needTagTest(scrut: Ident, tag: String): Boolean =
    !scrut.tpe.isTagType || scrut.tpe.asTagType.tag != tag

  private def scrutineeTagType(scrutType: Type, tag: String): TagType =
    if scrutType.isTagType then
      scrutType.asTagType
    else
      assert(scrutType.isUnionType, "expect union type, found = " + scrutType.show)
      val scrutUnionType = scrutType.asUnionType
      assert(scrutUnionType.hasTag(tag), s"expect union type with tag $tag, found = " + scrutUnionType.show)
      scrutUnionType.tagType(tag)

  private def scrutineeTagAssign(scrut: Ident, span: Span)(using ctx: Context, source: Source): Assign =
    val encodedScrutType = RecordType(NamedInfo("tag", IntType) :: Nil)
    val encodedScrut = Encoded(scrut)(encodedScrutType)

    val tagSym = Symbol.createSymbol("tag", IntType, Flags.Pattern | Flags.Synthetic, ctx.owner, span.toPos)
    val tagIdent = Ident(tagSym)(span)
    Assign(tagIdent, Select(encodedScrut, "tag")(IntType, span))(span)

object PatternMatcher:
  class Context(val implMap: mutable.Map[Symbol, Symbol], val owner: Symbol)
  object CacheContext extends Phase.ContextObject[Context]:
    def newContext(owner: Symbol, old: Context) = Context(old.implMap, owner)
    def newContext(namespace: Symbol) = throw new Exception("Namespace context should use global symbol map")
