package sast

import Sast.*
import Symbols.Symbol
import Types.*


import scala.collection.mutable

object SastOps:
  class AdaptionFailure(word: Word, targetType: Type) extends Exception:
    override def toString(): String =
      "Unable to adapt " + word + " of type " + word.tpe + " to " + targetType

  /** Adapt the word to the target type.
    *
    * It makes drop of values in if/match expressions explicit.
    */
  def adapt(word: Word, targetType: Type)
    (using defn: Definitions)
  : Word =

    val unitType = defn.UnitType

    val curType = word.tpe
    if Subtyping.conforms(curType, targetType) then
      word

    else if targetType.isVoidType && curType.isValueType then
      word.dropValue

    else

      val isNumeric = defn.isNumericType(word.tpe) && defn.isNumericType(targetType)

      if isNumeric && !Subtyping.conforms(word.tpe, targetType) then
        // Numeric coercion
        word match
          case Literal(Constant.Int(n)) =>
            val tp2 = coerceIntLiteral(n, word.tpe, targetType)
            val word2 = Literal(Constant.Int(n))(tp2, word.span)
            word2

          case _ =>
            // Only widening coercion is allowed for non-literals
            coerceNumeric(word, targetType)

      else if Subtyping.conforms(unitType, targetType) then
        val unit = RecordLit(args = Nil)(unitType, word.span.endPoint)
        Block(word.ensureDropValue :: unit :: Nil)(unitType, word.span)

      else
        throw new AdaptionFailure(word, targetType)

  private def coerceIntLiteral(n: Int, origType: Type, targetType: Type)
    (using defn: Definitions)
  : Type =

    if
      targetType.refers(defn.Predef_Byte) && n < 128 && n >= -128
      || targetType.refers(defn.Predef_Char) && n < 65536 && n >= 0
      || targetType.refers(defn.Int_Int)
    then
      targetType

    else
      origType

  /** Adapt the word to the target type
    *
    *     Byte ==> Int
    *     Char ==> Int
    *
    * Assumption: The tye of the word does not conform to the target type.
    */
  private def coerceNumeric(word: Word, targetType: Type)(using defn: Definitions): Word =
    def fail() = throw new AdaptionFailure(word, targetType)

    val origType = word.tpe
    if origType.refers(defn.Predef_Byte) then
      if targetType.refers(defn.Int_Int) then
        val byteToInt = Ident(defn.Predef_byteToInt)(word.span)
        Apply(byteToInt, word :: Nil, autos = Nil)(targetType, word.span)

      else
        fail()

    else if origType.refers(defn.Predef_Char) then
      if targetType.refers(defn.Int_Int) then
        val charToInt = Ident(defn.Predef_charToInt)(word.span)
        Apply(charToInt, word :: Nil, autos = Nil)(targetType, word.span)

      else
        fail()

    else
      fail()

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
      Apply(this(fun), args.map(this.apply), autos.map(this.apply))(apply.tpe, apply.span)

    def transformTypeApply(tapply: TypeApply)(using Context): Word =
      recurTypeApply(tapply)

    private def recurTypeApply(tapply: TypeApply)(using Context): Word =
      val TypeApply(fun, targs) = tapply
      TypeApply(this(fun), targs)(tapply.tpe, tapply.span)

    def transformNew(newExpr: New)(using Context): Word =
      recurNew(newExpr)

    private def recurNew(newExpr: New)(using Context): Word = newExpr

    def transformWith(withExpr: With)(using Context): Word =
      recurWith(withExpr)

    private def recurWith(withExpr: With)(using Context): Word =
      val With(expr, args) = withExpr
      // Don't map paramRef --- the client code should match this tree
      val args2 = args.map: arg =>
        arg.copy(rhs = this(arg.rhs))(arg.span)

      With(this(expr), args2)(withExpr.tpe, withExpr.span)

    def transformAllow(allowExpr: Allow)(using Context): Word =
      recurAllow(allowExpr)

    private def recurAllow(allowExpr: Allow)(using Context): Word =
      val Allow(expr, params) = allowExpr
      Allow(this(expr), params)(allowExpr.tpe, allowExpr.span)

    def transformAssign(assign: Assign)(using Context): Word =
      recurAssign(assign)

    private def recurAssign(assign: Assign)(using Context): Word =
      val Assign(id, rhs) = assign
      // Don't map id --- the client code should match Assign
      Assign(id, this(rhs))(assign.span)

    def transformFieldAssign(fieldAssign: FieldAssign)(using Context): Word =
      recurFieldAssign(fieldAssign)

    private def recurFieldAssign(fieldAssign: FieldAssign)(using Context): Word =
      val FieldAssign(lhs, rhs) = fieldAssign
      val lhs2 = lhs.copy(this(lhs.qual))(lhs.tpe, lhs.span)
      FieldAssign(lhs2, this(rhs))(fieldAssign.span)

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
      val Object(self, inits, funs) = obj
      val inits2: List[FieldAssign] =
        for init <- inits yield init.copy(rhs = this(init.rhs))(init.span)
      val funs2: List[FunDef] = funs.map(recurFunDef)
      Object(self, inits2, funs2)(obj.tpe, obj.span)

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
      ApplyPattern(fun, nested.map(this.apply))(pat.tpe, pat.span)

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
        yield Assign(id, this(rhs))(ass.span)

      BindPattern(this(pattern), bindings2)

    def transformWildcardPattern(pat: WildcardPattern)(using Context): Pattern =
      recurWildcardPattern(pat)

    private def recurWildcardPattern(pat: WildcardPattern)(using Context) = pat
  end TreeMap

  /** A tree traversal for non-toplevel code */
  trait TreeTraverser:
    type Context

    def apply(word: Word)(using Context): Unit

    def apply(pattern: Pattern)(using Context): Unit = recur(pattern)

    def recurLocalFunDef(fdef: FunDef)(using Context): Unit = this(fdef.body)

    def recurLocalTypeDef(tdef: TypeDef)(using Context): Unit = ()

    def recurLocalPatDef(pdef: PatDef)(using Context): Unit = this(pdef.body)

    def recur(pattern: Pattern)(using Context): Unit =
      pattern match
        case AliasPattern(id, nested) =>
          this(nested)

        case TypePattern(tpt) =>

        case TagPattern(_, nested) =>
          for pat <- nested do this(pat)

        case ApplyPattern(_, nested) =>
          for pat <- nested do this(pat)

        case OrPattern(lhs, rhs) =>
          this(lhs)
          this(rhs)

        case ValuePattern(value) =>
          this(value)

        case GuardPattern(pattern, guard) =>
          this(pattern)
          this(guard)

        case BindPattern(pattern, bindings) =>
          this(pattern)
          for Assign(id, rhs) <- bindings do this(rhs)

        case SeqPattern(pats) =>
          pats.foreach:
            case AtomPattern(pattern) => this(pattern)

            case SkipToPattern(pattern) => this(pattern)

            case StarPattern(pattern) => this(pattern)

            case RestPattern(pattern) => this(pattern)

        case WildcardPattern() =>

    def recur(word: Word)(using Context): Unit =
      word match
        case _: Literal | _: Ident | _: New =>

        case Select(qual, name) =>
          this(qual)

        case RecordLit(fields) =>
          fields.foreach:
            case (f, rhs) => this(rhs)

        case TaggedLit(tag, args) =>
          args.foreach(this.apply)

        case Encoded(repr) =>
          this(repr)

        case Apply(fun, args, autos) =>
          this(fun)
          args.foreach(this.apply)
          autos.foreach(this.apply)

        case TypeApply(fun, targs) =>
          this(fun)

        case With(expr, args) =>
          args.foreach: arg =>
            this(arg.rhs)

          this(expr)

        case Allow(expr, params) =>
          this(expr)

        case Assign(ident, rhs) =>
          this(ident)
          this(rhs)

        case FieldAssign(lhs, rhs) =>
          this(lhs.qual)
          this(rhs)

        case fdef: FunDef => recurLocalFunDef(fdef)

        case tdef: TypeDef => recurLocalTypeDef(tdef)

        case pdef: PatDef => recurLocalPatDef(pdef)

        case If(cond, thenp, elsep) =>
          this(cond)
          this(thenp)
          this(elsep)

        case While(cond, body) =>
          this(cond)
          this(body)

        case Block(words) =>
          words.foreach(this.apply)

        case Match(scrutinee, cases) =>
          this(scrutinee)
          for Case(pat, body) <- cases do
            this(pat)
            this(body)

        case Object(self, inits, defs) =>
          inits.map(this.apply)
          defs.map(this.apply)
    end recur
  end TreeTraverser

  /** Returns (locals, free) */
  def variableCensus(fdef: FunDef)(using Definitions): (List[Symbol], List[Symbol]) =
    val census = new VariableCensus
    census(fdef.body)(using ())
    val locals = census.locals.distinct.toList
    val masked = fdef.allParams ++ locals
    val free = census.free.filter(sym => !masked.contains(sym)).distinct.toList
    (locals.filter(_.info.isValueType), free)

  class VariableCensus(using Definitions) extends TreeTraverser:
    val locals = new mutable.ArrayBuffer[Symbol]
    val free = new mutable.ArrayBuffer[Symbol]

    type Context = Unit

    override def apply(pat: Pattern)(using Context): Unit =
      pat match
        case AliasPattern(id, nested) =>
          locals += id.symbol
          this(nested)

        case SeqPattern(pats) =>
          pats.foreach:
            case AtomPattern(pattern) => this(pattern)

            case SkipToPattern(pattern) => this(pattern)

            case RestPattern(pattern) => this(pattern)

            case star @ StarPattern(pattern) =>
              locals ++= star.bindings.map(_._1)
              this(pattern)

        case _ =>
          recur(pat)

    def apply(word: Word)(using Context): Unit =
      word match
        case Ident(sym) =>
          // can be a global name
          free += sym

        case Assign(Ident(sym), rhs) =>
          locals += sym
          this(rhs)

        case obj: Object =>
          locals += obj.self
          recur(obj)

        case fdef: FunDef =>
          locals += fdef.symbol
          free ++= fdef.freeVariables

        case _ => recur(word)
