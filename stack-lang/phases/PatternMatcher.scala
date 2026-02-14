package phases

import ast.Positions.*

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import Trees.SeqPattern.Size

import scala.collection.mutable

import PatternMatcher.implMap

class PatternMatcher(using defn: Definitions) extends Phase:
  val IntType = defn.IntType
  val BoolType = defn.BoolType
  val StringType = defn.StringType

  val abortSym = defn.abort

  /** The type for holding successful matched values in a PatDef */
  val ResultArrayType = AppliedType(defn.ObjectArray_class, AnyType :: Nil)

  override def initContext()(using Context) =
    implMap.set(mutable.Map.empty[Symbol, Symbol])

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
    *
    * If the pattern does not have parameters, then there is no `result`
    * parameter in the translation.
    */
  private def createImplFunSymbol(predSym: Symbol): Symbol =
    val predType = predSym.info.asProcType

    val needsResultArray = predType.params.nonEmpty

    val scrutType = NamedInfo("scrutinee", predType.resultType.stripPartial)
    val params =
      if needsResultArray then
        val resultType = NamedInfo("result", ResultArrayType)
        scrutType :: resultType :: Nil
      else
        scrutType :: Nil

    val autos = predType.autos
    val cands = autos.map(_ => Nil)

    val resultType = defn.BoolType

    val funType = ProcType(predType.tparams, params, autos, cands, resultType, predType.receives, preParamCount = 0, preTypeParamCount = 0)
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
  private def implementPatDef(pdef: PatDef)(using Context): FunDef =
    val implSym = getImplFunSymbol(pdef.symbol, implMap.value)
    val procType = implSym.info.as[ProcType]
    val paramTypes = procType.params
    val symSpan = pdef.symbol.sourcePos.span

    Phase.owner.set(implSym)
    given Source = Phase.source.value

    val scrutSym = TermSymbol.create("scrutinee", paramTypes(0).info, Flags.Param, Visibility.Default, implSym, implSym.sourcePos)
    val scrutIdent = Ident(scrutSym)(symSpan)

    // TODO: rebind pattern param symbols
    val resultType = defn.BoolType
    val tpt = TypeTree(resultType)(pdef.resultType.span)
    val autos = Nil
    val cands = autos.map(_ => Nil)

    val patternTranslated = transformPattern(scrutIdent, pdef.body)

    // If no result is needed, return early
    if pdef.params.isEmpty then
      val body = patternTranslated
      return FunDef(implSym, pdef.tparams, scrutSym :: Nil, autos, cands, tpt, Effects.Policy.Infer, body)(pdef.span)

    val resultSym = TermSymbol.create("result", ResultArrayType, Flags.Param, Visibility.Default, implSym, implSym.sourcePos)
    val resultIdent = Ident(resultSym)(symSpan)

    val successSym = TermSymbol.create("success", defn.BoolType, Flags.empty, Visibility.Default, implSym, implSym.sourcePos)
    val successIdent = Ident(successSym)(symSpan)

    val params = scrutSym :: resultSym :: Nil

    // TODO: optimize transalted code
    val successAssign = Assign(successIdent, patternTranslated)

    val endSpan = pdef.body.span.endPoint

    val assigns = pdef.params.zipWithIndex.map: (param, i) =>
      val value = Ident(param)(endSpan)
      resultIdent.select("set").appliedTo(IntLit(i)(endSpan), value).dropValue

    val assignBlock = Block(assigns)(endSpan)
    val condAssign = If(successIdent, assignBlock, Block(Nil)(endSpan))(VoidType, endSpan)

    val body = Block(
      successAssign
      :: condAssign
      :: successIdent
      :: Nil
    )(pdef.body.span)

    FunDef(implSym, pdef.tparams, params, autos, cands, tpt, Effects.Policy.Infer, body)(pdef.span)

  override def transformLocalPatDef(pdef: PatDef)(using Context): Word =
    implementPatDef(pdef)

  override def transformMatch(patmat: Match)(using Context): Word =
    val Match(scrutineeRaw, cases) = patmat
    val scrutinee = transform(scrutineeRaw)
    val scrutType = scrutinee.tpe.widenTermRef

    given Source = Phase.source.value

    var aliased: Boolean = false
    val scrutIdent: Ident =
      scrutinee match
        case id: Ident =>
          id

        case Encoded(id: Ident) =>
          id

        case _ =>
          aliased = true
          val owner = Phase.owner.value
          val scrutSym = TermSymbol.create("scrutinee", scrutType, Flags.Synthetic, Visibility.Default, owner, scrutinee.pos)
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

  override def transformIsExpr(isExpr: IsExpr)(using Context): Word =
    val IsExpr(scrutinee, pattern) = isExpr

    given Source = Phase.source.value
    transformPatternGeneric(scrutinee, pattern, isExpr.span)

  /** val pat = value */
  override def transformPatValDef(patValDef: PatValDef)(using Context): Word =
    given Source = Phase.source.value
    val test = transformPatternGeneric(patValDef.rhs, patValDef.pattern, patValDef.span)

    val abortState =
      val abortFun = Ident(abortSym)(patValDef.span)
      val arg = StringLit("Unhandled match at " + patValDef.pos)(patValDef.span)
      abortFun.appliedTo(arg).dropValue

    If(test, Block(Nil)(patValDef.span), abortState)(VoidType, patValDef.span)

  private def transformPatternGeneric(scrutinee: Word, pattern: Pattern, span: Span)(using ctx: Context, source: Source): Word =
    scrutinee match
      case scrut: Ident =>
        transformPattern(scrut, pattern)

      case _ =>
        val owner = Phase.owner.value
        val scrutSym = TermSymbol.create("scrut", scrutinee.tpe.widen, Flags.Synthetic, Visibility.Default, owner, scrutinee.pos)
        val scrutId = Ident(scrutSym)(scrutinee.span)
        val cond = transformPattern(scrutId, pattern)
        Block(Assign(scrutId, scrutinee) :: cond :: Nil)(span)

  private def transformPattern(scrut: Ident, pat: Pattern)(using ctx: Context, source: Source): Word =
    pat match
      case BindPattern(id, nested) =>
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

      case AndPattern(lhs, rhs) =>
        val cond1 = transformPattern(scrut, lhs)
        val cond2 = transformPattern(scrut, rhs)
        If(cond1, cond2, BoolLit(false)(pat.span))(BoolType, pat.span)

      case NotPattern(nested) =>
        val cond = transformPattern(scrut, nested)
        If(cond, BoolLit(false)(pat.span), BoolLit(true)(pat.span))(BoolType, pat.span)

      case valuePattern: ValuePattern =>
        transformValuePattern(scrut, valuePattern)

      case seqPat: SeqPattern =>
        transformSeqPattern(scrut, seqPat)

      case GuardPattern(guard) =>
        super.transform(guard)

      case AssignPattern(assignments) =>
        val assignments2 =
          for Assign(id, rhs) <- assignments
          yield Assign(id, super.transform(rhs))

        // Execute all assignments and return true
        Block(assignments2 :+ BoolLit(true)(pat.span))(pat.span)

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

  private def transformValuePattern(scrut: Ident, pat: ValuePattern): Word =
    val tp = pat.value.tpe

    if tp.isSubtype(defn.IntType) then
      Select(pat.value, "==")(pat.span).appliedTo(scrut)

    else if tp.isSubtype(defn.StringType) then
      scrut.select("==").appliedTo(pat.value)

    else if tp.isSubtype(defn.BoolType) then
      pat.value.select("==").appliedTo(scrut)

    else if tp.isSubtype(defn.ByteType) then
      Select(pat.value, "==")(pat.span).appliedTo(scrut)

    else if tp.isSubtype(defn.CharType) then
      Select(pat.value, "==")(pat.span).appliedTo(scrut)

    else if tp.isSubtype(defn.FloatType) then
      Select(pat.value, "==")(pat.span).appliedTo(scrut)

    else throw new Exception("Unexpected literal type: " + pat.value.tpe.show)

  private def transformApplyPattern
      (scrut: Ident, applyPattern: ApplyPattern)
      (using ctx: Context, source: Source)
  : Word =

    val ApplyPattern(pred, nested) = applyPattern
    val procType = pred.tpe.asProcType
    val span = applyPattern.span

    val scrutParamType = procType.resultType.stripPartial

    val hasReturnValue = procType.params.nonEmpty

    val implFun =
      (pred: @unchecked) match
        case Ident(sym) =>
          val impl = getImplFunSymbol(sym, implMap.value)
          Ident(impl)(pred.span)

        case TypeApply(id @ Ident(sym), tpts) =>
          val impl = getImplFunSymbol(sym, implMap.value)
          TypeApply(Ident(impl)(id.span), tpts)(pred.span)
      end match


    val noNeedTypeTest = Subtyping.conforms(scrut.tpe, scrutParamType)

    if hasReturnValue then
      val owner = Phase.owner.value
      val resultArray = TermSymbol.create("resArray", ResultArrayType, Flags.Synthetic, Visibility.Default, owner, pred.pos)
      val resultArrayIdent = Ident(resultArray)(span)

      // TODO: if parameters are all numeric types, optimization is possible
      //
      // Or create a class with mutable fields as transport to avoid boxing.
      val sizeArg = IntLit(procType.paramCount)(span)
      val arrayCreate = Ident(defn.ObjectArray)(span)
      val arrayAlloc = Assign(resultArrayIdent, arrayCreate.appliedToTypes(AnyType).appliedTo(sizeArg))

      val args =
        if noNeedTypeTest then scrut :: resultArrayIdent :: Nil
        else Encoded(scrut)(scrutParamType) :: resultArrayIdent :: Nil

      val app = Apply(implFun, args, autos = Nil)(span)

      val assigns =
        for (param, i) <- procType.params.zipWithIndex
        yield
          val owner = Phase.owner.value
          val valueSym = TermSymbol.create(param.name, param.info, Flags.Synthetic, Visibility.Default, owner, pred.pos)
          val valueIdent = Ident(valueSym)(span)
          val rhs = resultArrayIdent.select("get").appliedTo(IntLit(i)(span))
          Assign(valueIdent, Encoded(rhs)(param.info))

      val nestedConds =
        assert(assigns.size == nested.size, "nested.size = " + nested.size + ", assigns.size = " + assigns.size)

        for (pattern, Assign(id, _)) <- nested.zip(assigns)
        yield transformPattern(id, pattern)

      val head :: rest = nestedConds: @unchecked

      // Match semantics go from left to right and stop on failure
      val nestedCond =
        rest.foldLeft(head): (acc, cond) =>
          If(acc, cond, BoolLit(false)(span))(BoolType, span)

      val nestedBlock = Block(assigns :+ nestedCond)(span)
      val cond = If(app, nestedBlock, BoolLit(false)(span))(BoolType, span)
      val block = Block(arrayAlloc :: cond :: Nil)(span)

      if noNeedTypeTest then
        block

      else
        val typeTest = transformTypePattern(scrut, scrutParamType, span)
        If(typeTest, block, BoolLit(false)(span))(BoolType, span)

    else
      if noNeedTypeTest then
        Apply(implFun, scrut :: Nil, autos = Nil)(span)

      else
        val args = Encoded(scrut)(scrutParamType) :: Nil
        val app = Apply(implFun, args, autos = Nil)(implFun.span)
        val typeTest = transformTypePattern(scrut, scrutParamType, span)
        If(typeTest, app, BoolLit(false)(span))(BoolType, span)

  private def transformTypePattern
      (scrut: Ident, patternType: Type, span: Span)
  : Word =

    // Subtyping does not always hold, because Int  !<:  Int | String
    given StringBuilder = new StringBuilder
    assert(Patterns.isValidTypePattern(patternType, scrut.tpe.widen), "scrutee type = " + scrut.tpe.widen.show + ", type test = " + patternType.show)

    if Subtyping.conforms(scrut.tpe, patternType) then
      BoolLit(true)(span)

    else if patternType.isClassType then
      ClassTest(scrut, patternType.classSymbol)(span)

    else if patternType.isUnionType then
      val unionType = patternType.asUnionType

      val conds: List[Word] =
        for classType <- unionType.classTypes
        yield ClassTest(scrut, classType.classSymbol)(span)

      val cond :: rest = conds: @unchecked

      rest.foldLeft(cond): (acc, cond) =>
        acc.select("||").appliedTo(cond)

    else
      throw new Exception("Unexpected type pattern, scrutee type = " + scrut.tpe.show + ", type test = " + patternType.show)

  private def transformSeqPattern(scrut: Ident, seqPattern: SeqPattern)
      (using ctx: Context, source: Source)
  : Word =

    val conds = new mutable.ArrayBuffer[Word]

    // var index = 0
    val owner = Phase.owner.value
    val indexSym = TermSymbol.create("index", IntType, Flags.Mutable | Flags.Synthetic, Visibility.Default, owner, seqPattern.pos)
    val indexIdent = Ident(indexSym)(seqPattern.span)
    val indexInit = Assign(indexIdent, IntLit(0)(seqPattern.span))

    // val size = scrut.size()
    val sizeSym = TermSymbol.create("size", IntType, Flags.Synthetic, Visibility.Default, owner, seqPattern.pos)
    val sizeIdent = Ident(sizeSym)(seqPattern.span)
    val sizeInit =
      val span = seqPattern.span
      Assign(sizeIdent, TreeOps.smartApply(TreeOps.smartSelect(scrut, "size", span), args = Nil, autos = Nil)(span))

    val hasMore = indexIdent.select("<").appliedTo(sizeIdent)

    // index = index + 1
    def indexIncrement(span: Span): Word =
      val addOne = Select(indexIdent, "+")(span).appliedTo(IntLit(1)(span))
      Assign(indexIdent, addOne)

    // x = scrutine(index)
    def itemAtIndexAssign(span: Span): Assign =
      val appType = scrut.tpe.termMember("get").asProcType
      val itemType = appType.resultType
      val itemValue = TreeOps.smartApply(TreeOps.smartSelect(scrut, "get", span), indexIdent :: Nil, autos = Nil)(span)

      val itemSym = TermSymbol.create("item", itemType, Flags.Synthetic, Visibility.Default, owner, span.toPos)
      val itemIdent = Ident(itemSym)(span)
      Assign(itemIdent, itemValue)

    def distanceToEndCheck(dist: Size, span: Span): Word =
      dist match
        case Size.GreatEq(m) =>
          // index + m <= size
          val distLit = IntLit(m)(span)
          val lhs = Select(indexIdent, "+")(span).appliedTo(distLit)
          Select(lhs, "<=")(span).appliedTo(sizeIdent)

        case Size.Exact(m) =>
          // index + m == size
          val distLit = IntLit(m)(span)
          val lhs = Select(indexIdent, "+")(span).appliedTo(distLit)
          Select(lhs, "==")(span).appliedTo(sizeIdent)

    def totalSizeCheck(): Word =
      val span = seqPattern.span

      seqPattern.totalSize match
        case Size.GreatEq(m) =>
          // m <= size
          val distLit = IntLit(m)(span)
          Select(distLit, "<=")(span).appliedTo(sizeIdent)

        case Size.Exact(m) =>
          // index == size
          val distLit = IntLit(m)(span)
          Select(distLit, "==")(span).appliedTo(sizeIdent)

    for (pat, i) <- seqPattern.patterns.zipWithIndex do
      val increment = indexIncrement(pat.span)
      val distanceOK = distanceToEndCheck(seqPattern.distanceToEnd(i), pat.span)
      val distanceAllowMore = distanceToEndCheck(seqPattern.distanceToEnd(i) + Size.GreatEq(1), pat.span)

      pat match
        case AtomPattern(pattern) =>
          // if distanceAllowMore then
          //   x = scrutinee(index)
          //   index = index + 1
          //   x is pattern && distanceOK
          // else
          //   false

          // Make sure new symbol created
          val itemAssign = itemAtIndexAssign(pat.span)

          val nestedCond = transformPattern(itemAssign.ident, pattern)
          val finalCond = all(nestedCond, distanceOK)
          val block = Block(itemAssign :: increment :: finalCond :: Nil)(pattern.span)

          conds += If(distanceAllowMore, block, BoolLit(false)(pattern.span))(BoolType, pattern.span)

        case RepeatPattern(bindSymOpt, None) =>
          // Unguarded repeat: matches all remaining elements that fit
          //
          // val rest = scrutinee.slice(index, size - distanceFromEnd - index)
          // bind rest to symbol if present
          // index = size - distanceFromEnd
          // distanceOK

          val distSize = seqPattern.distanceToEnd(i)
          val distValue = distSize match
            case Size.Exact(n) => n
            case Size.GreatEq(n) => throw new Exception("Unguarded repeat pattern should have exact distance to end")

          val stats = new mutable.ArrayBuffer[Word]
          bindSymOpt match
            case Some(id) =>
              val sym = id match
                case sym: Symbol => sym
                case Ident(sym) => sym

              val endIndex = sizeIdent.select("-").appliedTo(IntLit(distValue)(pat.span))
              val len = endIndex.select("-").appliedTo(indexIdent)
              val slice =
                val span = pat.span
                TreeOps.smartApply(TreeOps.smartSelect(scrut, "slice", span), indexIdent :: len :: Nil, autos = Nil)(span)

              val restAssign = Assign(Ident(sym)(pat.span), slice)
              stats += restAssign

            case None =>
          end match

          // Update index to skip over matched elements
          val endIndex = Select(sizeIdent, "-")(pat.span).appliedTo(IntLit(distValue)(pat.span))
          stats += Assign(indexIdent, endIndex)
          stats += distanceOK
          conds += Block(stats.toList)(pat.span)

        case RepeatPattern(bindSymOpt, Some(guard)) =>
          // Guarded repeat: match while guard holds
          //
          //   val startIndex = index
          //   var continue = true
          //   while continue && hasMore do
          //     x = scrutinee(index)
          //     continue = x is guard
          //     if continue then
          //       index = index + 1
          //
          //   if bindSym then
          //     matched = scrutinee.slice(startIndex, index - startIndex)
          //   distanceOK


          // Save start index
          val owner = Phase.owner.value
          val startIndexSym = TermSymbol.create("startIndex", defn.IntType, Flags.Synthetic, Visibility.Default, owner, pat.pos)
          val startIndexIdent = Ident(startIndexSym)(pat.span)
          val startIndexInit = Assign(startIndexIdent, indexIdent)

          val itemAssign = itemAtIndexAssign(pat.span)

          val continueSym = TermSymbol.create("continue", BoolType, Flags.Mutable | Flags.Synthetic, Visibility.Default, owner, pat.pos)
          val continueIdent = Ident(continueSym)(pat.span)
          val continueInit = Assign(continueIdent, BoolLit(true)(pat.span))

          val cond = all(continueIdent, hasMore)
          val nestedCond = transformPattern(itemAssign.ident, guard)
          val continueUpdate = Assign(continueIdent, nestedCond)
          val nestedIf = If(continueIdent, Block(increment :: Nil)(pat.span), Block(Nil)(pat.span))(VoidType, pat.span)
          val body = Block(itemAssign :: continueUpdate :: nestedIf :: Nil)(pat.span)
          val whileLoop = While(cond, body)(pat.span)

          // Slice after loop if we need to bind
          val sliceAssign = bindSymOpt.map: id =>
            val sym = id match
              case sym: Symbol => sym
              case Ident(sym) => sym

            val len = indexIdent.select("-").appliedTo(startIndexIdent)
            val slice =
              val span = pat.span
              TreeOps.smartApply(TreeOps.smartSelect(scrut, "slice", span), startIndexIdent :: len :: Nil, autos = Nil)(span)

            Assign(Ident(sym)(pat.span), slice)

          val stmts = List(startIndexInit, continueInit, whileLoop) ++ sliceAssign.toList ++ List(distanceOK)
          conds += Block(stmts)(pat.span)
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
  val implMap: Phase.PhaseKey[mutable.Map[Symbol, Symbol]] = new Phase.PhaseKey("implMap")
