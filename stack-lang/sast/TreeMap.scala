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

  private def recurSelect(select: Select)(using Context): Word =
    val Select(qual, name) = select
    val qual2 = this(qual)
    val memberType = qual2.tpe.termMember(name)
    Select(qual2, name)(memberType, select.span)

  def transformRecord(rc: RecordLit)(using Context): Word =
    recurRecord(rc)

  private def recurRecord(rc: RecordLit)(using Context): Word =
    val RecordLit(fields) = rc
    val fields2 = fields.map:
      case (f, rhs) => f -> this(rhs)

    RecordLit(fields2)(rc.tpe, rc.span)

  def transformTagged(tagged: TaggedLit)(using Context): Word =
    recurTagged(tagged)

  private def recurTagged(tagged: TaggedLit)(using Context): Word =
    val TaggedLit(tag, args) = tagged
    val args2 = args.map(this.apply)
    TaggedLit(tag, args2)(tagged.tpe, tagged.span)

  def transformEncoded(encoding: Encoded)(using Context): Word =
    recurEncoded(encoding)

  private def recurEncoded(encoding: Encoded)(using Context): Word =
    Encoded(this(encoding.repr))(encoding.tpe)

  def transformApply(apply: Apply)(using Context): Word =
    recurApply(apply)

  private def recurApply(apply: Apply)(using Context): Word =
    val Apply(fun, args, autos) = apply
    Apply(this(fun), args.map(this.apply), autos.map(this.apply))(apply.tpe)

  def transformTypeApply(tapply: TypeApply)(using Context): Word =
    recurTypeApply(tapply)

  private def recurTypeApply(tapply: TypeApply)(using Context): Word =
    val TypeApply(fun, targs) = tapply
    TypeApply(this(fun), targs)(tapply.tpe)

  def transformNew(newExpr: New)(using Context): Word =
    recurNew(newExpr)

  private def recurNew(newExpr: New)(using Context): Word = newExpr

  def transformWith(withExpr: With)(using Context): Word =
    recurWith(withExpr)

  private def recurWith(withExpr: With)(using Context): Word =
    val With(expr, args) = withExpr
    // Don't map paramRef --- the client code should match this tree
    val args2 = args.map: arg =>
      arg.copy(rhs = this(arg.rhs))

    With(this(expr), args2)(withExpr.tpe)

  def transformAllow(allowExpr: Allow)(using Context): Word =
    recurAllow(allowExpr)

  private def recurAllow(allowExpr: Allow)(using Context): Word =
    val Allow(expr, params) = allowExpr
    Allow(this(expr), params)(allowExpr.tpe)

  def transformAssign(assign: Assign)(using Context): Word =
    recurAssign(assign)

  private def recurAssign(assign: Assign)(using Context): Word =
    val Assign(id, rhs) = assign
    // Don't map id --- the client code should match Assign
    Assign(id, this(rhs))

  def transformFieldAssign(fieldAssign: FieldAssign)(using Context): Word =
    recurFieldAssign(fieldAssign)

  private def recurFieldAssign(fieldAssign: FieldAssign)(using Context): Word =
    val FieldAssign(lhs, rhs) = fieldAssign
    val lhs2 = lhs.copy(this(lhs.qual))(lhs.tpe, lhs.span)
    FieldAssign(lhs2, this(rhs))

  def transformValDef(vdef: ValDef)(using Context): Word =
    recurValDef(vdef)

  private def recurValDef(vdef: ValDef)(using Context): ValDef =
    ValDef(vdef.symbol, this(vdef.rhs))(vdef.span)

  def transformLocalFunDef(fdef: FunDef)(using Context): Word =
    recurFunDef(fdef)

  private def recurFunDef(fdef: FunDef)(using ctx: Context): FunDef =
    val body = this(fdef.body)
    fdef.copy(body = body)(fdef.span)

  def transformLocalPatDef(pdef: PatDef)(using Context): Word =
    recurPatDef(pdef)

  private def recurPatDef(pdef: PatDef)(using Context): PatDef =
    pdef.copy(body = this(pdef.body))(pdef.span)

  def transformLocalTypeDef(tdef: TypeDef)(using Context): TypeDef =
    recurTypeDef(tdef)

  private def recurTypeDef(tdef: TypeDef)(using Context): TypeDef = tdef

  def transformIf(ifElse: If)(using Context): Word =
    recurIf(ifElse)

  private def recurIf(ifElse: If)(using Context): Word =
    val If(cond, thenp, elsep) = ifElse
    If(this(cond), this(thenp), this(elsep))(ifElse.tpe, ifElse.span)

  def transformWhile(whileDo: While)(using Context): Word =
    recurWhile(whileDo)

  private def recurWhile(whileDo: While)(using Context): Word =
    val While(cond, body) = whileDo
    While(this(cond), this(body))(whileDo.span)

  def transformMatch(patmat: Match)(using Context): Word =
    recurMatch(patmat)

  private def recurMatch(patmat: Match)(using Context): Word =
    val Match(scrutinee, cases) = patmat
    val cases2 =
      for branch <- cases
      yield branch.copy(this(branch.pattern), this(branch.body))(branch.span)

    Match(this(scrutinee), cases2)(patmat.tpe, patmat.span)

  def transformBlock(block: Block)(using Context): Word =
    recurBlock(block)

  private def recurBlock(block: Block)(using Context): Word =
    val Block(words) = block
    Block(words.map(this.apply))(block.tpe, block.span)

  def transformObject(obj: Object)(using Context): Word =
    recurObject(obj)

  private def recurObject(obj: Object)(using Context): Word =
    val Object(self, vals, funs) = obj
    val vals2: List[ValDef] = vals.map(recurValDef)
    val funs2: List[FunDef] = funs.map(recurFunDef)
    Object(self, vals2, funs2)(obj.tpe, obj.span)

  def transformAliasPattern(pat: AliasPattern)(using Context): Pattern =
    recurAliasPattern(pat)

  private def recurAliasPattern(pat: AliasPattern)(using Context): Pattern =
    val AliasPattern(id, nested) = pat
    AliasPattern(id, this(nested))

  def transformTypePattern(pat: TypePattern)(using Context): Pattern =
    recurTypePattern(pat)

  private def recurTypePattern(pat: TypePattern)(using Context): Pattern = pat

  def transformTagPattern(pat: TagPattern)(using Context): Pattern =
    recurTagPattern(pat)

  private def recurTagPattern(pat: TagPattern)(using Context): Pattern =
    val TagPattern(tag, nested) = pat
    TagPattern(tag, nested.map(this.apply))(pat.tpe)

  def transformApplyPattern(pat: ApplyPattern)(using Context): Pattern =
    recurApplyPattern(pat)

  private def recurApplyPattern(pat: ApplyPattern)(using Context): Pattern =
    val ApplyPattern(fun, nested) = pat
    ApplyPattern(fun, nested.map(this.apply))(pat.tpe)

  def transformOrPattern(pat: OrPattern)(using Context): Pattern =
    recurOrPattern(pat)

  private def recurOrPattern(pat: OrPattern)(using Context): Pattern =
    val OrPattern(lhs, rhs) = pat
    OrPattern(this(lhs), this(rhs))

  def transformValuePattern(pat: ValuePattern)(using Context): Pattern =
    recurValuePattern(pat)

  private def recurValuePattern(pat: ValuePattern)(using Context): Pattern =
    ValuePattern(this(pat.value))(pat.scrutineeType)

  def transformGuardPattern(pat: GuardPattern)(using Context): Pattern =
    recurGuardPattern(pat)

  private def recurGuardPattern(pat: GuardPattern)(using Context): Pattern =
    val GuardPattern(pattern, guard) = pat
    GuardPattern(this(pattern), this(guard))

  def transformSeqPattern(pat: SeqPattern)(using Context): Pattern =
    recurSeqPattern(pat)

  private def recurSeqPattern(pat: SeqPattern)(using Context): Pattern =
    val SeqPattern(patterns) = pat
    val patterns2 =
      for regPat <- patterns yield
        regPat match
          case AtomPattern(pattern) =>
            AtomPattern(this(pattern))

          case SkipToPattern(pattern) =>
            SkipToPattern(this(pattern))(regPat.span)

          case RestPattern(pattern) =>
            RestPattern(this(pattern))(regPat.span)

          case starPat @ StarPattern(pattern) =>
            StarPattern(this(pattern))(regPat.span, starPat.bindings)
        end match
      end for

    SeqPattern(patterns2)(pat.tpe, pat.span)

  def transformBindPattern(pat: BindPattern)(using Context): Pattern =
    recurBindPattern(pat)

  private def recurBindPattern(pat: BindPattern)(using Context): Pattern =
    val BindPattern(pattern, bindings) = pat
    val bindings2 =
      for ass @ Assign(id, rhs) <- bindings
      yield Assign(id, this(rhs))

    BindPattern(this(pattern), bindings2)

  def transformWildcardPattern(pat: WildcardPattern)(using Context): Pattern =
    recurWildcardPattern(pat)

  private def recurWildcardPattern(pat: WildcardPattern)(using Context) = pat
end TreeMap
