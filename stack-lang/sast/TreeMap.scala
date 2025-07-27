package sast

import Sast.*

abstract class TreeMap(using Definitions):
  type Context

  final def apply(word: Word)(using Context): Word =
    transform(word)

  final def apply(pattern: Pattern)(using Context): Pattern =
    transform(pattern)

  final def transform(word: Word)(using Context): Word =
    word match
      case lit: Literal => transformLiteral(lit)

      case ident: Ident => transformIdent(ident)

      case select: Select => transformSelect(select)

      case rc: RecordLit => transformRecord(rc)

      case tag: TaggedLit => transformTagged(tag)

      case encoding: Encoded => transformEncoded(encoding)

      case apply: Apply => transformApply(apply)

      case newExpr: New => transformNew(newExpr)

      case tapply: TypeApply => transformTypeApply(tapply)

      case withExpr: With => transformWith(withExpr)

      case allowExpr: Allow => transformAllow(allowExpr)

      case assign: Assign => transformAssign(assign)

      case fieldAssign: FieldAssign => transformFieldAssign(fieldAssign)

      case vdef: ValDef => transformValDef(vdef)

      case fdef: FunDef => transformLocalFunDef(fdef)

      case pdef: PatDef => transformLocalPatDef(pdef)

      case tdef: TypeDef => transformLocalTypeDef(tdef)

      case ifElse: If => transformIf(ifElse)

      case whileDo: While => transformWhile(whileDo)

      case block: Block => transformBlock(block)

      case patmat: Match => transformMatch(patmat)

      case obj: Object => transformObject(obj)
  end transform

  final def transform(pattern: Pattern)(using Context): Pattern =
    pattern match
      case pat: AliasPattern => transformAliasPattern(pat)

      case pat: TypePattern => transformTypePattern(pat)

      case pat: TagPattern => transformTagPattern(pat)

      case pat: ApplyPattern => transformApplyPattern(pat)

      case pat: OrPattern => transformOrPattern(pat)

      case pat: ValuePattern => transformValuePattern(pat)

      case pat: GuardPattern => transformGuardPattern(pat)

      case pat: BindPattern => transformBindPattern(pat)

      case pat: WildcardPattern => transformWildcardPattern(pat)

      case pat: SeqPattern => transformSeqPattern(pat)

  def transformLiteral(lit: Literal)(using Context): Word = recurLiteral(lit)

  private def recurLiteral(lit: Literal)(using Context): Word = lit

  def transformIdent(ident: Ident)(using Context): Word =
    recurIdent(ident)

  private def recurIdent(ident: Ident)(using Context): Word = ident

  def transformSelect(select: Select)(using Context): Word =
    recurSelect(select)

  private def recurSelect(select: Select)(using Context): Select =
    val Select(qual, name) = select
    val qual2 = this(qual)

    if qual2 `eq` qual then
      select
    else
      val memberType = qual2.tpe.termMember(name)
      Select(qual2, name)(memberType, select.span)

  def transformRecord(rc: RecordLit)(using Context): Word =
    recurRecord(rc)

  private def recurRecord(rc: RecordLit)(using Context): Word =
    val RecordLit(fields) = rc
    var changed = false
    val fields2 = fields.map:
      case field @ (f, rhs) =>
        val rhs2 = this(rhs)
        if rhs `eq` rhs2 then
          field
        else
          changed = true
          f -> rhs2

    if changed then
      RecordLit(fields2)(rc.tpe, rc.span)

    else
      rc

  def transformTagged(tagged: TaggedLit)(using Context): Word =
    recurTagged(tagged)

  private def recurTagged(tagged: TaggedLit)(using Context): Word =
    val TaggedLit(tag, args) = tagged

    var changed = false

    val args2 = args.map: arg =>
      val arg2 = this(arg)
      changed ||= arg2 `ne` arg
      arg2

    if changed then
      TaggedLit(tag, args2)(tagged.tpe, tagged.span)
    else
      tagged

  def transformEncoded(encoding: Encoded)(using Context): Word =
    recurEncoded(encoding)

  private def recurEncoded(encoding: Encoded)(using Context): Word =
    val repr = encoding.repr
    val repr2 = this(repr)

    if repr2 `eq` repr then
      encoding
    else
      Encoded(repr2)(encoding.tpe)

  def transformApply(apply: Apply)(using Context): Word =
    recurApply(apply)

  private def recurApply(apply: Apply)(using Context): Word =
    val Apply(fun, args, autos) = apply

    val fun2 = this(fun)

    var changed = fun2 `ne` fun

    val args2 = args.map: arg =>
      val arg2 = this(arg)
      changed ||= arg2 `ne` arg
      arg2

    val autos2 = autos.map: auto =>
      val auto2 = this(auto)
      changed ||= auto2 `ne` auto
      auto2

    if changed then
      Apply(fun2, args2, autos2)(apply.tpe)
    else
      apply

  def transformTypeApply(tapply: TypeApply)(using Context): Word =
    recurTypeApply(tapply)

  private def recurTypeApply(tapply: TypeApply)(using Context): Word =
    val TypeApply(fun, targs) = tapply

    val fun2 = this(fun)

    if fun2 `eq` fun then
      tapply
    else
      TypeApply(fun2, targs)(tapply.tpe)

  def transformNew(newExpr: New)(using Context): Word =
    recurNew(newExpr)

  private def recurNew(newExpr: New)(using Context): Word = newExpr

  def transformWith(withExpr: With)(using Context): Word =
    recurWith(withExpr)

  private def recurWith(withExpr: With)(using Context): Word =
    val With(expr, args) = withExpr

    val expr2 = this(expr)

    var changed = expr2 `ne` expr

    // Don't map paramRef --- the client code should match this tree
    val args2 = args.map: arg =>
      val rhs = arg.rhs
      val rhs2 = this(rhs)
      if rhs2 `eq` rhs then
        arg
      else
        changed = true
        arg.copy(rhs = rhs2)

    if changed then
      With(expr2, args2)(withExpr.tpe)
    else
      withExpr

  def transformAllow(allowExpr: Allow)(using Context): Word =
    recurAllow(allowExpr)

  private def recurAllow(allowExpr: Allow)(using Context): Word =
    val Allow(expr, params) = allowExpr

    val expr2 = this(expr)

    if expr2 `eq` expr then
      allowExpr
    else
      Allow(expr2, params)(allowExpr.tpe)

  def transformAssign(assign: Assign)(using Context): Word =
    recurAssign(assign)

  private def recurAssign(assign: Assign)(using Context): Word =
    val Assign(id, rhs) = assign
    // Don't map id --- the client code should match Assign
    val rhs2 = this(rhs)

    if rhs2 `eq` rhs then
      assign
    else
      Assign(id, rhs2)

  def transformFieldAssign(fieldAssign: FieldAssign)(using Context): Word =
    recurFieldAssign(fieldAssign)

  private def recurFieldAssign(fieldAssign: FieldAssign)(using Context): Word =
    val FieldAssign(lhs, rhs) = fieldAssign

    val lhs2 = recurSelect(lhs)
    val rhs2 = this(rhs)

    if lhs2.eq(lhs) && rhs2.eq(rhs) then
      fieldAssign
    else
      FieldAssign(lhs2, rhs2)

  def transformValDef(vdef: ValDef)(using Context): Word =
    recurValDef(vdef)

  private def recurValDef(vdef: ValDef)(using Context): ValDef =
    val rhs2 = this(vdef.rhs)
    if rhs2 `eq` vdef.rhs then
      vdef
    else
      ValDef(vdef.symbol, rhs2)(vdef.span)

  def transformLocalFunDef(fdef: FunDef)(using Context): Word =
    recurFunDef(fdef)

  private def recurFunDef(fdef: FunDef)(using ctx: Context): FunDef =
    val body2 = this(fdef.body)
    if body2 `eq` fdef.body then
      fdef
    else
      fdef.copy(body = body2)(fdef.span)

  def transformLocalPatDef(pdef: PatDef)(using Context): Word =
    recurPatDef(pdef)

  private def recurPatDef(pdef: PatDef)(using Context): PatDef =
    val body2 = this(pdef.body)
    if body2 `eq` pdef.body then
      pdef
    else
      pdef.copy(body = body2)(pdef.span)

  def transformLocalTypeDef(tdef: TypeDef)(using Context): TypeDef =
    recurTypeDef(tdef)

  private def recurTypeDef(tdef: TypeDef)(using Context): TypeDef = tdef

  def transformIf(ifElse: If)(using Context): Word =
    recurIf(ifElse)

  private def recurIf(ifElse: If)(using Context): Word =
    val If(cond, thenp, elsep) = ifElse
    val cond2 = this(cond)
    val thenp2 = this(thenp)
    val elsep2 = this(elsep)

    if cond2.eq(cond) && thenp2.eq(thenp) && elsep2.eq(elsep) then
      ifElse
    else
      If(cond2, thenp2, elsep2)(ifElse.tpe, ifElse.span)

  def transformWhile(whileDo: While)(using Context): Word =
    recurWhile(whileDo)

  private def recurWhile(whileDo: While)(using Context): Word =
    val While(cond, body) = whileDo
    val cond2 = this(cond)
    val body2 = this(body)
    if cond2.eq(cond) && body2.eq(body) then
      whileDo
    else
      While(cond2, body2)(whileDo.span)

  def transformMatch(patmat: Match)(using Context): Word =
    recurMatch(patmat)

  private def recurMatch(patmat: Match)(using Context): Word =
    val Match(scrutinee, cases) = patmat

    val scrutinee2 = this(scrutinee)

    var changed = scrutinee2 `ne` scrutinee

    val cases2 =
      for branch <- cases
      yield
        val pattern2 = this(branch.pattern)
        val body2 = this(branch.body)
        if pattern2.eq(branch.pattern) && body2.eq(branch.body) then
          branch
        else
          changed = true
          branch.copy(pattern2, body2)(branch.span)

    if changed then
      Match(scrutinee2, cases2)(patmat.tpe, patmat.span)
    else
      patmat

  def transformBlock(block: Block)(using Context): Word =
    recurBlock(block)

  private def recurBlock(block: Block)(using Context): Word =
    val Block(words) = block

    var changed = false
    val words2 = words.map: word =>
      val word2 = this(word)
      changed ||= word2 `ne` word
      word2

    if changed then
      Block(words2)(block.tpe, block.span)
    else
      block

  def transformObject(obj: Object)(using Context): Word =
    recurObject(obj)

  private def recurObject(obj: Object)(using Context): Word =
    val Object(self, vals, funs) = obj

    var changed = false

    val vals2: List[ValDef] = vals.map: vdef =>
      val vdef2 = recurValDef(vdef)
      changed ||= vdef2 `ne` vdef
      vdef2

    val funs2: List[FunDef] = funs.map: fdef =>
      val fdef2 = recurFunDef(fdef)
      changed ||= fdef2 `ne` fdef
      fdef2

    if changed then
      Object(self, vals2, funs2)(obj.tpe, obj.span)
    else
      obj

  def transformAliasPattern(pat: AliasPattern)(using Context): Pattern =
    recurAliasPattern(pat)

  private def recurAliasPattern(pat: AliasPattern)(using Context): Pattern =
    val AliasPattern(id, nested) = pat
    val nested2 = this(nested)
    if nested2 `eq` nested then
      pat
    else
      AliasPattern(id, nested2)

  def transformTypePattern(pat: TypePattern)(using Context): Pattern =
    recurTypePattern(pat)

  private def recurTypePattern(pat: TypePattern)(using Context): Pattern = pat

  def transformTagPattern(pat: TagPattern)(using Context): Pattern =
    recurTagPattern(pat)

  private def recurTagPattern(pat: TagPattern)(using Context): Pattern =
    val TagPattern(tag, nested) = pat

    var changed = false
    val nested2 = nested.map: patNested =>
      val patNested2 = this(patNested)
      changed ||= patNested2 `ne` patNested
      patNested2

    if changed then
      TagPattern(tag, nested2)(pat.tpe)
    else
      pat

  def transformApplyPattern(pat: ApplyPattern)(using Context): Pattern =
    recurApplyPattern(pat)

  private def recurApplyPattern(pat: ApplyPattern)(using Context): Pattern =
    val ApplyPattern(fun, nested) = pat

    var changed = false
    val nested2 = nested.map: patNested =>
      val patNested2 = this(patNested)
      changed ||= patNested2 `ne` patNested
      patNested2

    if changed then
      ApplyPattern(fun, nested2)(pat.tpe)
    else
      pat

  def transformOrPattern(pat: OrPattern)(using Context): Pattern =
    recurOrPattern(pat)

  private def recurOrPattern(pat: OrPattern)(using Context): Pattern =
    val OrPattern(lhs, rhs) = pat
    val lhs2 = this(lhs)
    val rhs2 = this(rhs)

    if lhs2.eq(lhs) && rhs2.eq(rhs) then
      pat
    else
      OrPattern(lhs2, rhs2)

  def transformValuePattern(pat: ValuePattern)(using Context): Pattern =
    recurValuePattern(pat)

  private def recurValuePattern(pat: ValuePattern)(using Context): Pattern =
    val value2 = this(pat.value)
    if value2 `eq` pat.value then
      pat
    else
      ValuePattern(value2)(pat.scrutineeType)

  def transformGuardPattern(pat: GuardPattern)(using Context): Pattern =
    recurGuardPattern(pat)

  private def recurGuardPattern(pat: GuardPattern)(using Context): Pattern =
    val GuardPattern(pattern, guard) = pat
    val pattern2 = this(pattern)
    val guard2 = this(guard)
    if pattern2.eq(pattern) && guard2.eq(guard) then
      pat
    else
      GuardPattern(pattern2, guard2)

  def transformSeqPattern(pat: SeqPattern)(using Context): Pattern =
    recurSeqPattern(pat)

  private def recurSeqPattern(pat: SeqPattern)(using Context): Pattern =
    val SeqPattern(patterns) = pat
    var changed = false

    val patterns2 =
      for regPat <- patterns yield
        regPat match
          case AtomPattern(pattern) =>
            val pattern2 = this(pattern)
            if pattern2 `eq` pattern then
              regPat
            else
              changed = true
              AtomPattern(pattern2)

          case SkipToPattern(pattern) =>
            val pattern2 = this(pattern)
            if pattern2 `eq` pattern then
              regPat
            else
              changed = true
              SkipToPattern(pattern2)(regPat.span)

          case RestPattern(pattern) =>
            val pattern2 = this(pattern)
            if pattern2 `eq` pattern then
              regPat
            else
              changed = true
              RestPattern(pattern2)(regPat.span)

          case starPat @ StarPattern(pattern) =>
            val pattern2 = this(pattern)
            if pattern2 `eq` pattern then
              regPat
            else
              changed = true
              StarPattern(pattern2)(regPat.span, starPat.bindings)
        end match
      end for

    if changed then
      SeqPattern(patterns2)(pat.tpe, pat.span)
    else
      pat

  def transformBindPattern(pat: BindPattern)(using Context): Pattern =
    recurBindPattern(pat)

  private def recurBindPattern(pat: BindPattern)(using Context): Pattern =
    val BindPattern(pattern, bindings) = pat

    val pattern2 = this(pattern)

    var changed = pattern2 `ne` pattern

    val bindings2 =
      for ass @ Assign(id, rhs) <- bindings
      yield
        val rhs2 = this(rhs)
        if rhs2 `eq` rhs then
          ass
        else
          changed = true
          Assign(id, rhs2)

    if changed then
      BindPattern(pattern2, bindings2)
    else
      pat

  def transformWildcardPattern(pat: WildcardPattern)(using Context): Pattern =
    recurWildcardPattern(pat)

  private def recurWildcardPattern(pat: WildcardPattern)(using Context) = pat
end TreeMap
