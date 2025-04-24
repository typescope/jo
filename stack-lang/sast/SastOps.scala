package sast

import Sast.*
import Symbols.Symbol
import Types.*


import reporting.Reporter

import scala.collection.mutable

object SastOps:
  class AdaptionFailure(word: Word, targetType: Type) extends Exception:
    override def toString(): String =
      "Unable to adapt " + word + " of type " + word.tpe.show + " to " + targetType.show

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
            val tp2 = adaptIntLiteral(n, word.tpe, targetType)
            val word2 = Literal(Constant.Int(n))(tp2, word.span)
            word2

          case _ =>
            // TODO: only widening coercion is allowed for non-literals
            autoCoerceNumeric(word, targetType)

      else if Subtyping.conforms(unitType, targetType) then
        val unit = RecordLit(args = Nil)(unitType, word.span)
        Block(word.ensureDropValue :: unit :: Nil)(unitType, word.span)

      else
        throw new AdaptionFailure(word, targetType)

  private def adaptIntLiteral(n: Int, origType: Type, targetType: Type)
    (using defn: Definitions)
  : Type =

    if
      targetType.refersTo(defn.Predef_Byte) && n < 128 && n >= -128
      || targetType.refersTo(defn.Predef_Char) && n < 65536 && n >= 0
      || targetType.refersTo(defn.Predef_Int)
    then
      targetType

    else
      origType

  def autoCoerceNumeric(word: Word, targetType: Type)
    (using defn: Definitions)
  : Word =

    val origType = word.tpe
    if origType.refersTo(defn.Predef_Byte) then
      if targetType.refersTo(defn.Predef_Char) then
        val byteToChar = Ident(defn.Predef_byteToChar)(word.span)
        Apply(byteToChar, word :: Nil)(targetType, word.span)

      else if targetType.refersTo(defn.Predef_Int) then
        val byteToInt = Ident(defn.Predef_byteToInt)(word.span)
        Apply(byteToInt, word :: Nil)(targetType, word.span)

      else
        Reporter.abortInternal("Unexpected numeric type " + targetType.show)

    else if origType.refersTo(defn.Predef_Char) then
      if targetType.refersTo(defn.Predef_Byte) then
        word

      else if targetType.refersTo(defn.Predef_Int) then
        val charToInt = Ident(defn.Predef_charToInt)(word.span)
        Apply(charToInt, word :: Nil)(targetType, word.span)

      else
        Reporter.abortInternal("Unexpected numeric type " + targetType.show)

    else if origType.refersTo(defn.Predef_Int) then
      word

    else
      Reporter.abortInternal("Unexpected numeric type " + origType.show)

  abstract class TreeMap:
    type Context

    final def apply(word: Word)(using Context): Word =
      transform(word)

    final def apply(pattern: Pattern)(using Context): Pattern =
      recur(pattern)

    final def transform(word: Word)(using Context): Word =
      word match
        case lit: Literal => transformLiteral(lit)

        case ident: Ident => transformIdent(ident)

        case select: Select => transformSelect(select)

        case rc: RecordLit => transformRecord(rc)

        case tag: TaggedLit => transformTagged(tag)

        case encoding: Encoded => transformEncoded(encoding)

        case apply: Apply => transformApply(apply)

        case tapply: TypeApply => transformTypeApply(tapply)

        case withExpr: With => transformWith(withExpr)

        case allowExpr: Allow => transformAllow(allowExpr)

        case assign: Assign => transformAssign(assign)

        case fieldAssign: FieldAssign => transformFieldAssign(fieldAssign)

        case vdef: ValDef => transformValDef(vdef)

        case fdef: FunDef => transformFunDef(fdef)

        case pdef: PatDef => transformPatDef(pdef)

        case tdef: TypeDef => transformTypeDef(tdef)

        case ifElse: If => transformIf(ifElse)

        case whileDo: While => transformWhile(whileDo)

        case block: Block => transformBlock(block)

        case patmat: Match => transformMatch(patmat)

        case obj: Object => transformObject(obj)
    end transform

    def transformLiteral(lit: Literal)(using Context): Word = recur(lit)

    def transformIdent(ident: Ident)(using Context): Word = recur(ident)

    def transformSelect(select: Select)(using Context): Word = recur(select)

    def transformRecord(rc: RecordLit)(using Context): Word = recur(rc)

    def transformTagged(tagged: TaggedLit)(using Context): Word = recur(tagged)

    def transformEncoded(encoding: Encoded)(using Context): Word = recur(encoding)

    def transformApply(apply: Apply)(using Context): Word = recur(apply)

    def transformTypeApply(tapply: TypeApply)(using Context): Word = recur(tapply)

    def transformWith(withExpr: With)(using Context): Word = recur(withExpr)

    def transformAllow(allowExpr: Allow)(using Context): Word = recur(allowExpr)

    def transformAssign(assign: Assign)(using Context): Word = recur(assign)

    def transformFieldAssign(fieldAssign: FieldAssign)(using Context): Word = recur(fieldAssign)

    def transformValDef(vdef: ValDef)(using Context): Word = recurValDef(vdef)

    def transformFunDef(fdef: FunDef)(using Context): Word = recurFunDef(fdef)

    def transformPatDef(pdef: PatDef)(using Context): Word = recurPatDef(pdef)

    def transformTypeDef(tdef: TypeDef)(using Context): TypeDef = recurTypeDef(tdef)

    def transformIf(ifElse: If)(using Context): Word = recur(ifElse)

    def transformWhile(whileDo: While)(using Context): Word = recur(whileDo)

    def transformMatch(patmat: Match)(using Context): Word = recur(patmat)

    def transformBlock(block: Block)(using Context): Word = recur(block)

    def transformObject(obj: Object)(using Context): Word = recur(obj)

    private def recurValDef(vdef: ValDef)(using Context): ValDef =
      ValDef(vdef.symbol, this(vdef.rhs))(vdef.span)

    private def recurFunDef(fdef: FunDef)(using ctx: Context): FunDef =
      val body = this(fdef.body)
      fdef.copy(body = body)(fdef.span)

    private def recurTypeDef(tdef: TypeDef)(using Context): TypeDef = tdef

    private def recurPatDef(pdef: PatDef)(using Context): PatDef = pdef

    final def recur(pattern: Pattern)(using Context): Pattern =
      pattern match
        case AscribePattern(id, nested) =>
          AscribePattern(id, this(nested))

        case _: TypePattern => pattern

        case TagPattern(tag, nested) =>
          TagPattern(tag, nested.map(this.apply))(pattern.tpe)

        case ApplyPattern(fun, nested) =>
          ApplyPattern(fun, nested.map(this.apply))(pattern.tpe, pattern.span)

        case OrPattern(lhs, rhs) =>
          OrPattern(this(lhs), this(rhs))

        case ValuePattern(value) =>
          ValuePattern(this(value))

        case GuardPattern(pattern, guard) =>
          GuardPattern(this(pattern), this(guard))

        case TermBindingPattern(pattern, bindings) =>
          val bindings2 =
            for ass @ Assign(id, rhs) <- bindings
            yield Assign(id, this(rhs))(ass.span)

          TermBindingPattern(this(pattern), bindings2)

        case _: WildcardPattern => pattern

    final def recur(word: Word)(using Context): Word =
      word match
        case _: Literal | _: Ident =>
          word

        case Select(qual, name) =>
          val qual2 = this(qual)
          val memberType = qual2.tpe.termMember(name)
          Select(qual2, name)(memberType, word.span)

        case RecordLit(fields) =>
          val fields2 = fields.map:
            case (f, rhs) => f -> this(rhs)

          RecordLit(fields2)(word.tpe, word.span)

        case TaggedLit(tag, args) =>
          val args2 = args.map(this.apply)
          TaggedLit(tag, args2)(word.tpe, word.span)

        case Encoded(repr) =>
          Encoded(this(repr))(word.tpe)

        case Apply(fun, args) =>
          Apply(this(fun), args.map(this.apply))(word.tpe, word.span)

        case TypeApply(fun, targs) =>
          TypeApply(this(fun), targs)(word.tpe, word.span)

        case With(expr, args) =>
          // Don't map paramRef --- the client code should match this tree
          val args2 = args.map: arg =>
            arg.copy(arg.paramRef, this(arg.rhs))(arg.span)

          With(this(expr), args2)(word.tpe, word.span)

        case Allow(expr, params) =>
          Allow(this(expr), params)(word.tpe, word.span)

        case Assign(id, rhs) =>
          // Don't map id --- the client code should match Assign
          Assign(id, this(rhs))(word.span)

        case FieldAssign(qual, name, rhs) =>
          FieldAssign(this(qual), name, this(rhs))(word.span)

        case vdef: ValDef => recurValDef(vdef)

        case fdef: FunDef => recurFunDef(fdef)

        case pdef: PatDef => recurPatDef(pdef)

        case tdef: TypeDef => recurTypeDef(tdef)

        case If(cond, thenp, elsep) =>
          If(this(cond), this(thenp), this(elsep))(word.tpe, word.span)

        case While(cond, body) =>
          While(this(cond), this(body))(word.span)

        case Match(scrutinee, cases) =>
          val cases2 =
            for branch <- cases
            yield branch.copy(branch.pattern, this(branch.body))(branch.span)

          Match(this(scrutinee), cases2)(word.tpe, word.span)

        case Block(words) =>
          Block(words.map(this.apply))(word.tpe, word.span)

        case Object(self, vals, defs) =>
          val vals2: List[ValDef] = vals.map(recurValDef)
          val defs2: List[FunDef] = defs.map(recurFunDef)
          Object(self, vals2, defs2)(word.tpe, word.span)
    end recur
  end TreeMap

  trait TreeTraverser:
    type Context

    def apply(word: Word)(using Context): Unit

    def apply(pattern: Pattern)(using Context): Unit = recur(pattern)

    def recurValDef(vdef: ValDef)(using Context): Unit = this(vdef.rhs)

    def recurFunDef(fdef: FunDef)(using Context): Unit = this(fdef.body)

    def recurTypeDef(tdef: TypeDef)(using Context): Unit = ()

    def recurParamDef(pdef: ParamDef)(using Context): Unit = ()

    def recurPatDef(pdef: PatDef)(using Context): Unit = this(pdef.body)

    def recurDef(defn: Def)(using Context): Unit =
      defn match
        case vdef: ValDef   => recurValDef(vdef)
        case fdef: FunDef   => recurFunDef(fdef)
        case pdef: PatDef   => recurPatDef(pdef)
        case tdef: TypeDef  => recurTypeDef(tdef)
        case pdef: ParamDef => recurParamDef(pdef)

    def recur(pattern: Pattern)(using Context): Unit =
      pattern match
        case AscribePattern(id, nested) =>
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

        case TermBindingPattern(pattern, bindings) =>

        case WildcardPattern() =>

    def recur(word: Word)(using Context): Unit =
      word match
        case _: Literal | _: Ident =>

        case Select(qual, name) =>
          this(qual)

        case RecordLit(fields) =>
          fields.foreach:
            case (f, rhs) => this(rhs)

        case TaggedLit(tag, args) =>
          args.foreach(this.apply)

        case Encoded(repr) =>
          this(repr)

        case Apply(fun, args) =>
          this(fun)
          args.foreach(this.apply)

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

        case FieldAssign(qual, name, rhs) =>
          this(qual)
          this(rhs)

        case vdef: ValDef => recurValDef(vdef)

        case fdef: FunDef => recurFunDef(fdef)

        case tdef: TypeDef => recurTypeDef(tdef)

        case pdef: PatDef => recurPatDef(pdef)

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

        case Object(self, vals, defs) =>
          vals.map(recurValDef)
          defs.map(recurFunDef)
    end recur
  end TreeTraverser

  /** Returns (locals, free) */
  def variableCensus(fdef: FunDef): (List[Symbol], List[Symbol]) =
    val census = new VariableCensus
    census(fdef.body)(using ())
    val locals = census.locals.distinct.toList
    val masked = fdef.params ++ locals
    val free = census.free.filter(sym => !masked.contains(sym)).distinct.toList
    (locals.filter(_.info.isValueType), free)

  class VariableCensus extends TreeTraverser:
    val locals = new mutable.ArrayBuffer[Symbol]
    val free = new mutable.ArrayBuffer[Symbol]

    type Context = Unit

    override def recurFunDef(fdef: FunDef)(using Context): Unit =
      locals += fdef.symbol
      free ++= variableCensus(fdef)._2

    def apply(word: Word)(using Context): Unit =
      word match
        case Ident(sym) =>
          // can be a global name
          free += sym

        case ValDef(sym, rhs) =>
          if !sym.isField then locals += sym
          recur(rhs)

        case Assign(Ident(sym), rhs) =>
          locals += sym
          recur(rhs)

        case obj: Object =>
          locals += obj.self
          recur(obj)

        case _ => recur(word)
