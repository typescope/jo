package phases

import ast.Positions.*

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import Trees.SeqPattern.Size

import scala.collection.mutable

class PatternMatcher(using defn: Definitions) extends Phase[PatternMatcher.Context]:
  val contextObject = PatternMatcher.CacheContext

  val IntType = defn.IntType
  val BoolType = defn.BoolType
  val StringType = defn.StringType

  val abortSym = defn.Internal_abort
  val eitherSym = defn.Bool_either
  val bothSym = defn.Bool_both

  override def transform(nss: List[Namespace]): List[Namespace] =
    val implMap = mutable.Map.empty[Symbol, Symbol]

    for ns <- nss yield
      given Context = PatternMatcher.Context(implMap, ns.symbol)
      super.transformNamespace(ns)

  override def transformDefs(defs: List[Def])(using ctx: Context): List[Def] =
    defs.map:
      case pdef: PatDef =>
        implementPatDef(pdef)

      case defn =>
        super.transformDef(defn)

  /** Given a pattern definition
    *
    *     pattern Foo[T](a: T, b: S): U[T] = ...
    *
    * We transform it to
    *
    *     def Foo$impl[T](scrutinee: U[T], result: Array[Any]): Boolean =
    *       val success: Boolean = ...
    *
    *       if success then
    *         result[0] = a
    *         result[1] = b
    *
    *       success
    *
    * The caller must allocate the size of the array correctly to hold all the
    * result values.
    */
  private def createImplFunSymbol(predSym: Symbol): Symbol =
    val predType = predSym.info.asProcType

    val scrutType = NamedInfo("scrutinee", predType.resultType.stripPartial)
    val resultType = NamedInfo("res", AppliedType(defn.Array_type, AnyType :: Nil))
    val params = scrutType :: resultType :: Nil
    val autos = predType.autos
    val cands = autos.map(_ => Nil)

    val resultType = defn.BoolType

    val funType = ProcType(predType.tparams, params, params.map(_ => Nil), autos, cands, resultType, () => predType.receives, preParamCount = 0)
    TermSymbol.create(predSym.name + "$impl", funType, Flags.Fun | Flags.Synthetic, Visibility.Default, predSym.owner, predSym.sourcePos)

  private def getImplFunSymbol(predSym: Symbol, implMap: mutable.Map[Symbol, Symbol]): Symbol =
    implMap.get(predSym) match
      case Some(implSym) =>
        implSym

      case None =>
        val implSym = createImplFunSymbol(predSym)
        implMap(predSym) = implSym
        implSym

  /** See the translation scheme in `createImplFunSymbol` */
  private def implementPatDef(pdef: PatDef)(using ctx: Context): FunDef =
    val implSym = getImplFunSymbol(pdef.symbol, ctx.implMap)
    val paramTypes = implSym.info.as[ProcType].params
    val symSpan = pdef.symbol.sourcePos.span

    given Context = PatternMatcher.CacheContext.newContext(implSym, ctx)
    given Source = pdef.symbol.sourcePos.source

    val scrutSym = TermSymbol.create("scrutinee", params(0).info, Flags.Param, Visibility.Default, implSym, implSym.sourcePos)
    val scrutIdent = Ident(scrutSym)(symSpan)

    val resultSym = TermSymbol.create("result", params(1).info, Flags.Param, Visibility.Default, implSym, implSym.sourcePos)
    val resultIdent = Ident(resultSym)(symSpan)

    val successSym = TermSymbol.create("success", defn.BoolType, Flags.empty, Visibility.Default, implSym, implSym.sourcePos)
    val successIdent = Ident(successSym)(symSpan)

    val params = scrutSym :: resultSym :: Nil

    // TODO: optimize transalted code
    val successAssign = Assign(successIdent, transformPattern(scrutIdent, pdef.body))

    val endSpan = pdef.body.span.endPoint

    val arraySet = Ident(defn.Array_set)(endSpan).appliedToType(AnyType)
    val assigns = pdef.params.zipWithIndex.map: (param, i) =>
      val value = Ident(param)(endSpan)
      arraySet.appliedTo(resultIdent, IntLit(i)(endSpan).dropValue,

    val assignBlock = Block(assigns)(endSpan)
    val condAssign = If(cond, assignBlock, Block(Nil)(span))(VoidType, span)

    val body = Block(
      successAssign
      :: condAssign
      :: successIdent
    )(pdef.body.span)

    // TODO: rebind pattern param symbols
    val resultType = defn.BoolType
    val tpt = TypeTree(resultType)(pdef.resultType.span)
    val autos = Nil
    val cands = autos.map(_ => Nil)
    val adapters = params.map(_ => Nil)
    FunDef(implSym, pdef.tparams, params, adapters, autos, cands, tpt, Effects.Policy.Infer, body)(pdef.span)

  override def transformLocalPatDef(pdef: PatDef)(using ctx: Context): Word =
    implementPatDef(pdef)

  override def transformMatch(patmat: Match)(using ctx: Context): Word =
    val Match(scrutineeRaw, cases) = patmat
    val scrutinee = transform(scrutineeRaw)
    val scrutType = scrutinee.tpe.widenTermRef

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
          val scrutSym = TermSymbol.create("scrutinee", scrutType, Flags.Synthetic, Visibility.Default, ctx.owner, scrutinee.pos)
          Ident(scrutSym)(scrutinee.span)

    def transformCases(cases: List[Case]): Word =
      cases match
        case caseDef :: rest =>
          transformCase(scrutIdent, patmat.tpe, caseDef, () => transformCases(rest))

        case Nil =>
          // No need to abort if we issue error for non-exhaustive cases.
          // It is needed for code generation.
          val abort = Ident(abortSym)(scrutIdent.span)
          val arg = StringLit("Unhandled match at " + scrutIdent.pos)(scrutIdent.span)
          abort.appliedTo(arg).dropIfVoid(patmat.tpe)
      end match

    val body = transformCases(cases)

    if aliased then
      val scrutAssign = Assign(scrutIdent, scrutinee)
      Block(scrutAssign :: body :: Nil)(patmat.span)

    else
      body

  private def transformCase(scrut: Ident, resultType: Type, caseDef: Case, cont: () => Word) (using ctx: Context, source: Source): Word =
    val cond = transformPattern(scrut, caseDef.pattern)
    // TODO: optimize irrefutable patterns
    If(cond, transform(caseDef.body), cont())(resultType, caseDef.span)

  private def transformPattern(scrut: Ident, pat: Pattern)(using Context, Source): Word =
    pat match
      case AliasPattern(id, nested) =>
        val cond = transformPattern(scrut, nested)
        // It is more performant to always assign
        val assign = Assign(id, scrut.encodedAs(id.symbol.info))
        Block(assign :: cond :: Nil)(pat.span)

      case TypePattern(tpt) =>
        transformTypePattern(scrut, tpt.tpe, tpt.span)

      case appPat: ApplyPattern =>
        transformApplyPattern(scrut, appPat)

      case orPat: OrPattern =>
        transformOrPattern(scrut, orPat)

      case valuePattern: ValuePattern =>
        transformValuePattern(scrut, valuePattern)

      case seqPat: SeqPattern =>
        transformSeqPattern(scrut, seqPat)

      case GuardPattern(pattern, guard) =>
        val cond = transformPattern(scrut, pattern)
        If(cond, guard, BoolLit(false)(pat.span))(BoolType, pat.span)

      case BindPattern(pattern, bindings) =>
        val cond = transformPattern(scrut, pattern)
        val nestedBlock = Block(bindings :+ BoolLit(true)(pat.span))(pat.span)
        If(cond, nestedBlock, BoolLit(false)(pat.span))(BoolType, pat.span)

      case WildcardPattern() =>
        assert(Subtyping.conforms(scrut.tpe.widen, pat.valueType.widen), "scrutee type = " + scrut.tpe.widen.show + ", pattern type = " + pat.valueType.widen.show)
        BoolLit(true)(pat.span)

  private def transformOrPattern(scrut: Ident, orPattern: OrPattern)
    (using ctx: Context, source: Source)
  : Word =

    val OrPattern(lhs, rhs) = orPattern
    val lhsCond = transformPattern(scrut, lhs)
    val rhsCond = transformPattern(scrut, rhs)
    If(lhsCond, BoolLit(true)(lhs.span), rhsCond)(BoolType, orPattern.span)

  private def transformValuePattern(scrut: Ident, pat: ValuePattern)(using Source): Word =
    val tp = pat.value.tpe
    if tp.isSubtype(defn.ByteType) then
      Ident(defn.Int_eql)(pat.span).appliedTo(pat.value, scrut)

    else if tp.isSubtype(defn.IntType) then
      Ident(defn.Int_eql)(pat.span).appliedTo(pat.value, scrut)

    else if tp.isSubtype(defn.CharType) then
      Ident(defn.Int_eql)(pat.span).appliedTo(pat.value, scrut)

    else if tp.isSubtype(defn.BoolType) then
      val bothTrue = Ident(defn.Bool_both)(pat.span).appliedTo(pat.value, scrut)
      val notValue = Ident(defn.Bool_not)(pat.span).appliedTo(pat.value)
      val notScrut = Ident(defn.Bool_not)(pat.span).appliedTo(scrut)
      val bothFalse = Ident(defn.Bool_both)(pat.span).appliedTo(notValue, notScrut)
      Ident(defn.Bool_either)(pat.span).appliedTo(bothTrue, bothFalse)

    else if tp.isSubtype(defn.StringType) then
      scrut.select("==").appliedTo(pat.value)

    else throw new Exception("Unexpected literal type: " + pat.value.tpe.show)

  private def transformApplyPattern
      (scrut: Ident, applyPattern: ApplyPattern)
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
          val implProcType = impl.info.asProcType.instantiate(tpts.map(_.tpe))
          TypeApply(Ident(impl)(id.span), tpts)(implProcType, pred.span)
      end match

    val call =
      if noNeedTypeTest then Apply(implFun, scrut :: Nil, autos = Nil)(implFun.span)
      else Apply(implFun, Encoded(scrut)(paramType) :: Nil, autos = Nil)(implFun.span)

    val resSym = TermSymbol.create("res", resultType, Flags.Synthetic, Visibility.Default, ctx.owner, pred.pos)
    val assignRes = Assign(Ident(resSym)(pred.span), call)
    val assignTag = scrutineeTagAssign(assignRes.ident, span)

    // TODO: no test for irrefutable predicate
    val resCond =
      Block(
        assignRes
          :: assignTag
          :: TaggedEncoding.testTagValue(assignTag.ident, "Success", span)
          :: Nil
      )(span)

    val callCond =
      if noNeedTypeTest then
        resCond

      else
        val typeTest = transformTypePattern(scrut, paramType, span)
        If(typeTest, resCond, BoolLit(false)(span))(BoolType, span)

    val assigns =
      for param <- successType.params
      yield
        val valueSym = TermSymbol.create(param.name, param.info, Flags.Synthetic, Visibility.Default, ctx.owner, pred.pos)
        val valueIdent = Ident(valueSym)(span)
        val rhs = TaggedEncoding.selectVariantField(assignRes.ident, successType, param.name, span)
        Assign(valueIdent, rhs)

    if nested.isEmpty then
      callCond
    else
      val nestedConds =
        for (pattern, Assign(id, _)) <- nested.zip(assigns)
        yield transformPattern(id, pattern)

      val head :: rest = nestedConds: @unchecked

      val nestedCond =
        rest.foldLeft(head): (acc, cond) =>
          Ident(bothSym)(span).appliedTo(acc, cond)

      val nestedBlock = Block(assigns :+ nestedCond)(span)
      If(callCond, nestedBlock, BoolLit(false)(span))(BoolType, span)

  private def transformTypePattern
      (scrut: Ident, patternType: Type, span: Span)
      (using Context, Source)
  : Word =

    assert(Subtyping.conforms(patternType, scrut.tpe.widen), "scrutee type = " + scrut.tpe.widen.show + ", type test = " + patternType.show)

    if Subtyping.conforms(scrut.tpe, patternType) then
      BoolLit(true)(span)

    else if patternType.isClassType then
      defn.Internal_typeTest.appliedToType(patternType).appliedTo(scrut)

    else if patternType.isUnionType then
      val unionType = patternType.asUnionType

      val conds =
        for classType <- unionType.classTypes
        yield defn.Internal_typeTest.appliedToType(patternType).appliedTo(scrut)

      val cond :: rest = conds: @unchecked

      // either is faster than short-cutting or
      val eitherFun = Ident(eitherSym)(span)
      rest.foldLeft(cond): (acc, cond) =>
        eitherFun.appliedTo(acc, cond)

    else
      throw new Exception("Unexpected tag pattern, scrutee type = " + scrut.tpe.show + ", type test = " + patternType.show)

  private def transformSeqPattern(scrut: Ident, seqPattern: SeqPattern)
      (using ctx: Context, source: Source)
  : Word =

    val conds = new mutable.ArrayBuffer[Word]

    // var index = 0
    val indexSym = TermSymbol.create("index", IntType, Flags.Mutable | Flags.Synthetic, Visibility.Default, ctx.owner, seqPattern.pos)
    val indexIdent = Ident(indexSym)(seqPattern.span)
    val indexInit = Assign(indexIdent, IntLit(0)(seqPattern.span))

    // val size = scrut.size()
    val sizeSym = TermSymbol.create("size", IntType, Flags.Synthetic, Visibility.Default, ctx.owner, seqPattern.pos)
    val sizeIdent = Ident(sizeSym)(seqPattern.span)
    val sizeInit = Assign(sizeIdent, scrut.select("size").appliedTo())

    // index = index + 1
    def indexIncrement(span: Span): Word =
      val addOne = Ident(defn.Int_add)(span).appliedTo(indexIdent, IntLit(1)(span))
      Assign(indexIdent, addOne)

    // x = scrutine(index)
    def itemAtIndexAssign(span: Span): Assign =
      val appType = scrut.tpe.termMember("get").asProcType
      val itemType = appType.resultType
      val itemValue = Select(scrut, "get")(span).appliedTo(indexIdent)

      val itemSym = TermSymbol.create("item", itemType, Flags.Synthetic, Visibility.Default, ctx.owner, span.toPos)
      val itemIdent = Ident(itemSym)(span)
      Assign(itemIdent, itemValue)

    def distanceToEndCheck(dist: Size, span: Span): Word =
      dist match
        case Size.GreatEq(m) =>
          // index + m <= size
          val le = Ident(defn.Int_le)(span)
          val distLit = IntLit(m)(span)
          val lhs = Ident(defn.Int_add)(span).appliedTo(indexIdent, distLit)
          le.appliedTo(lhs, sizeIdent)

        case Size.Exact(m) =>
          // index + m == size
          val eql = Ident(defn.Int_eql)(span)
          val distLit = IntLit(m)(span)
          val lhs = Ident(defn.Int_add)(span).appliedTo(indexIdent, distLit)
          eql.appliedTo(lhs, sizeIdent)

    def totalSizeCheck(): Word =
      val span = seqPattern.span

      seqPattern.totalSize match
        case Size.GreatEq(m) =>
          // m <= size
          val le = Ident(defn.Int_le)(span)
          val distLit = IntLit(m)(span)
          le.appliedTo(distLit, sizeIdent)

        case Size.Exact(m) =>
          // index == size
          val eql = Ident(defn.Int_eql)(span)
          val distLit = IntLit(m)(span)
          eql.appliedTo(distLit, sizeIdent)

    // TODO: optimize last irrefutable star pattern with no bindings
    for (pat, i) <- seqPattern.patterns.zipWithIndex do
      val increment = indexIncrement(pat.span)
      val distanceOK = distanceToEndCheck(seqPattern.distanceToEnd(i), pat.span)
      val distanceAllowMore = distanceToEndCheck(seqPattern.distanceToEnd(i) + Size.GreatEq(1), pat.span)

      pat match
        case AtomPattern(pattern) =>
          // if distanceAllowMore then
          //   x = scrutinee(index)
          //   index = index + 1
          //   x ~ pattern && distanceOK
          // else
          //   false

          // Make sure new symbol created
          val itemAssign = itemAtIndexAssign(pat.span)

          val nestedCond = transformPattern(itemAssign.ident, pattern)
          val finalCond = all(nestedCond, distanceOK)
          val block = Block(itemAssign :: increment :: finalCond :: Nil)(pattern.span)

          conds += If(distanceAllowMore, block, BoolLit(false)(pattern.span))(BoolType, pattern.span)

        case SkipToPattern(pattern) =>
          // var found = false
          // while !found && distanceAllowMore do
          //   x = scrutinee(index)
          //   index = index + 1
          //   found = x ~ pattern
          //
          // found && distanceOK

          // Make sure new symbol created
          val itemAssign = itemAtIndexAssign(pat.span)

          val foundSym = TermSymbol.create("found", BoolType, Flags.Mutable | Flags.Synthetic, Visibility.Default, ctx.owner, pat.pos)
          val foundIdent = Ident(foundSym)(pat.span)
          val foundInit = Assign(foundIdent, BoolLit(false)(pat.span))

          val notFound = Ident(defn.Bool_not)(pat.span).appliedTo(foundIdent)
          val cond = all(notFound, distanceAllowMore)

          val nestedCond = transformPattern(itemAssign.ident, pattern)
          val foundUpdate = Assign(foundIdent, nestedCond)
          val body = Block(itemAssign :: increment :: foundUpdate :: Nil)(pat.span)
          val whileLoop = While(cond, body)(pat.span)

          val finalCond = all(foundIdent, distanceOK)
          conds += Block(foundInit :: whileLoop :: finalCond :: Nil)(pat.span)

        case RestPattern(pattern) =>
          assert(i == seqPattern.patterns.size - 1, ".. should be the last pattern")
          // val rest = scrutinee.slice(index, if index == size then index else size - 1)
          // items ~ pattern

          val restSym = TermSymbol.create("rest", pattern.scrutineeType, Flags.Synthetic, Visibility.Default, ctx.owner, pat.pos)
          val restIdent = Ident(restSym)(pat.span)
          val to =
            val sub = Ident(defn.Int_sub)(pat.span)
            val cond = Ident(defn.Int_eql)(pat.span).appliedTo(indexIdent, sizeIdent)
            If(
              cond,
              indexIdent,
              sub.appliedTo(sizeIdent, IntLit(1)(pat.span))
            )(defn.IntType, pat.span)

          val slice = scrut.select("slice").appliedTo(indexIdent, to)
          val restAssign = Assign(restIdent, slice)
          val nestedCond = transformPattern(restIdent, pattern)
          conds += Block(restAssign :: nestedCond :: Nil)(pat.span)

        case starPat @ StarPattern(pattern) =>
          //  var continue = true
          //  ys = []   -- outer pattern bound symbol corresponding to inner symbol y
          //  while continue && distanceAllowMore do
          //    x = scrutinee(index)
          //    continue = x ~ pattern
          //    if continue then
          //      index = index + 1
          //      ys = ys + y
          //  distanceOK

          // Make sure new symbol created
          val itemAssign = itemAtIndexAssign(pat.span)

          val continueSym = TermSymbol.create("continue", BoolType, Flags.Mutable | Flags.Synthetic, Visibility.Default, ctx.owner, pat.pos)
          val continueIdent = Ident(continueSym)(pat.span)
          val continueInit = Assign(continueIdent, BoolLit(true)(pat.span))

          val inits =
            for (outerSym, innerSym) <- starPat.bindings yield
              val emptyList = Ident(defn.List_empty)(pat.span).appliedToTypes(innerSym.info).appliedTo()
              Assign(Ident(outerSym)(pat.span), emptyList)

          val updates =
            for (outerSym, innerSym) <- starPat.bindings yield
              val outer = Ident(outerSym)(pat.span)
              val inner = Ident(innerSym)(pat.span)
              val append = outer.select("+").appliedTo(inner)
              Assign(Ident(outerSym)(pat.span), append)

          val cond = all(continueIdent, distanceAllowMore)
          val nestedCond = transformPattern(itemAssign.ident, pattern)
          val continueUpdate = Assign(continueIdent, nestedCond)
          val nestedIf = If(continueIdent, Block(increment :: updates)(pat.span), Block(Nil)(pat.span))(VoidType, pat.span)
          val body = Block(itemAssign :: continueUpdate :: nestedIf :: Nil)(pat.span)
          val whileLoop = While(cond, body)(pat.span)

          val finalCond = distanceOK
          conds += Block((continueInit :: inits) :+ whileLoop :+ finalCond)(pat.span)
      end match
    end for

    val allCond =
      if conds.isEmpty then
        totalSizeCheck()
      else
        val patternConds =
          conds.reduceRight: (cond, acc) =>
            If(cond, acc, BoolLit(false)(cond.span))(BoolType, cond.span | acc.span)

        If(totalSizeCheck(), patternConds, BoolLit(false)(seqPattern.span))(BoolType, seqPattern.span)

    Block(indexInit :: sizeInit :: allCond :: Nil)(seqPattern.span)

object PatternMatcher:
  class Context(val implMap: mutable.Map[Symbol, Symbol], val owner: Symbol)
  object CacheContext extends Phase.ContextObject[Context]:
    def newContext(owner: Symbol, old: Context) = Context(old.implMap, owner)
    def newContext(namespace: Symbol) = throw new Exception("Namespace context should use global symbol map")
