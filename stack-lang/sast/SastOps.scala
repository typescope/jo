package sast

import Sast.*
import Symbols.Symbol

import scala.collection.mutable

object SastOps:
  abstract class TreeMap:
    type Context

    final def apply(word: Word)(using Context): Word =
      transform(word)

    def transform(word: Word)(using Context): Word =
      word match
        case lit: Literal => transformLiteral(lit)

        case ident: Ident => transformIdent(ident)

        case select: Select => transformSelect(select)

        case rc: RecordLit => transformRecord(rc)

        case encoding: Encoded => transformEncoded(encoding)

        case apply: Apply => transformApply(apply)

        case tapply: TypeApply => transformTypeApply(tapply)

        case withExpr: With => transformWith(withExpr)

        case defaultParam: DefaultParam => transformDefaultParam(defaultParam)

        case assign: Assign => transformAssign(assign)

        case fieldAssign: FieldAssign => transformFieldAssign(fieldAssign)

        case vdef: ValDef => transformValDef(vdef)

        case fdef: FunDef => transformFunDef(fdef)

        case tdef: TypeDef => transformTypeDef(tdef)

        case ifElse: If => transformIf(ifElse)

        case whileDo: While => transformWhile(whileDo)

        case block: Block => transformBlock(block)

        case obj: Object => transformObject(obj)
    end transform

    def transformLiteral(lit: Literal)(using Context): Word = recur(lit)

    def transformIdent(ident: Ident)(using Context): Word = recur(ident)

    def transformSelect(select: Select)(using Context): Word = recur(select)

    def transformRecord(rc: RecordLit)(using Context): Word = recur(rc)

    def transformEncoded(encoding: Encoded)(using Context): Word = recur(encoding)

    def transformApply(apply: Apply)(using Context): Word = recur(apply)

    def transformTypeApply(tapply: TypeApply)(using Context): Word = recur(tapply)

    def transformWith(withExpr: With)(using Context): Word = recur(withExpr)

    def transformDefaultParam(defaultParam: DefaultParam)(using Context): Word = recur(defaultParam)

    def transformAssign(assign: Assign)(using Context): Word = recur(assign)

    def transformFieldAssign(fieldAssign: FieldAssign)(using Context): Word = recur(fieldAssign)

    def transformValDef(vdef: ValDef)(using Context): Word = recur(vdef)

    def transformFunDef(fdef: FunDef)(using Context): Word = recur(fdef)

    def transformTypeDef(tdef: TypeDef)(using Context): Word = recur(tdef)

    def transformIf(ifElse: If)(using Context): Word = recur(ifElse)

    def transformWhile(whileDo: While)(using Context): Word = recur(whileDo)

    def transformBlock(block: Block)(using Context): Word = recur(block)

    def transformObject(obj: Object)(using Context): Word = recur(obj)


    def recurValDef(vdef: ValDef)(using Context): ValDef =
      ValDef(vdef.symbol, this(vdef.rhs))(vdef.span)

    def recurFunDef(fdef: FunDef)(using Context): FunDef =
      val body = this(fdef.body)
      fdef.copy(body = body)(fdef.span)

    def recurTypeDef(tdef: TypeDef)(using Context): TypeDef = tdef

    def recurParamDef(pdef: ParamDef)(using Context): ParamDef = pdef

    def recurDef(defn: Def)(using Context): Def =
      defn match
        case vdef: ValDef   => recurValDef(vdef)
        case fdef: FunDef   => recurFunDef(fdef)
        case tdef: TypeDef  => recurTypeDef(tdef)
        case pdef: ParamDef => recurParamDef(pdef)

    def recur(word: Word)(using Context): Word =
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

        case Encoded(repr) =>
          Encoded(this(repr))(word.tpe)

        case Apply(fun, args) =>
          Apply(this(fun), args.map(this.apply))(word.tpe, word.span)

        case TypeApply(fun, targs) =>
          TypeApply(this(fun), targs)(word.tpe, word.span)

        case With(expr, args, only) =>
          // Don't map paramRef --- the client code should match DefaultParam
          val args2 = args.map: arg =>
            arg.copy(arg.paramRef, this(arg.rhs))(arg.span)

          With(this(expr), args2, only)(word.tpe, word.span)

        case DefaultParam(paramRef, default) =>
          // Don't map paramRef --- the client code should match DefaultParam
          DefaultParam(paramRef, this(default))(word.tpe, word.span)

        case Assign(id, rhs) =>
          // Don't map id --- the client code should match Assign
          Assign(id, this(rhs))(word.span)

        case FieldAssign(qual, name, rhs) =>
          FieldAssign(this(qual), name, this(rhs))(word.span)

        case vdef: ValDef => recurValDef(vdef)

        case fdef: FunDef => recurFunDef(fdef)

        case tdef: TypeDef => recurTypeDef(tdef)

        case If(cond, thenp, elsep) =>
          If(this(cond), this(thenp), this(elsep))(word.tpe, word.span)

        case While(cond, body) =>
          While(this(cond), this(body))(word.span)

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

    def recurValDef(vdef: ValDef)(using Context): Unit = this(vdef.rhs)

    def recurFunDef(fdef: FunDef)(using Context): Unit = this(fdef.body)

    def recurTypeDef(tdef: TypeDef)(using Context): Unit = ()

    def recurParamDef(pdef: ParamDef)(using Context): Unit = ()

    def recurDef(defn: Def)(using Context): Unit =
      defn match
        case vdef: ValDef   => recurValDef(vdef)
        case fdef: FunDef   => recurFunDef(fdef)
        case tdef: TypeDef  => recurTypeDef(tdef)
        case pdef: ParamDef => recurParamDef(pdef)

    def recur(word: Word)(using Context): Unit =
      word match
        case _: Literal | _: Ident =>

        case Select(qual, name) =>
          this(qual)

        case RecordLit(fields) =>
          fields.foreach:
            case (f, rhs) => this(rhs)

        case Encoded(repr) =>
          this(repr)

        case Apply(fun, args) =>
          this(fun)
          args.foreach(this.apply)

        case TypeApply(fun, targs) =>
          this(fun)

        case With(expr, args, only) =>
          args.foreach: arg =>
            this(arg.paramRef)
            this(arg.rhs)

          this(expr)

        case DefaultParam(paramRef, default) =>
          this(paramRef)
          this(default)

        case Assign(ident, rhs) =>
          this(ident)
          this(rhs)

        case FieldAssign(qual, name, rhs) =>
          this(qual)
          this(rhs)

        case vdef: ValDef => recurValDef(vdef)

        case fdef: FunDef => recurFunDef(fdef)

        case tdef: TypeDef => recurTypeDef(tdef)

        case If(cond, thenp, elsep) =>
          this(cond)
          this(thenp)
          this(elsep)

        case While(cond, body) =>
          this(cond)
          this(body)

        case Block(words) =>
          words.foreach(this.apply)

        case Object(self, vals, defs) =>
          vals.map(recurValDef)
          defs.map(recurFunDef)
    end recur
  end TreeTraverser

  /** Returns (locals, free) */
  def variableCensus(fdef: FunDef): (List[Symbol], List[Symbol]) =
    val census = new VariableCensus
    census(fdef.body)(using fdef.symbol)
    val locals = census.locals.distinct.toList
    val masked = fdef.params ++ locals
    val free = census.free.filter(sym => !masked.contains(sym)).distinct.toList
    (locals.filter(_.info.isValueType), free)

  class VariableCensus extends TreeTraverser:
    val locals = new mutable.ArrayBuffer[Symbol]
    val free = new mutable.ArrayBuffer[Symbol]

    // Use owner as context
    type Context = Symbol

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
