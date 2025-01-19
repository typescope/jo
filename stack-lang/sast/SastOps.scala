package sast

import Sast.*
import Symbols.Symbol

import scala.collection.mutable

object SastOps:
  trait TreeMap:
    type Context

    def apply(word: Word)(using Context): Word

    def recurValDef(vdef: ValDef)(using Context): ValDef =
      ValDef(vdef.symbol, this(vdef.rhs))(vdef.span)

    def recurFunDef(fdef: FunDef)(using Context): FunDef =
      val body = this(fdef.body)
      fdef.copy(body = body)(fdef.locals, fdef.span)

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
        case _: IntLit | _: BoolLit | _: StringLit | _: Ident =>
          word

        case Select(qual, name) =>
          Select(this(qual), name)(word.tpe, word.span)

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

  trait TreeAccumulator:
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
        case _: IntLit | _: BoolLit | _: StringLit | _: Ident =>

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
  end TreeAccumulator

  def freeVariables(fdef: FunDef): List[Symbol] =
    val census = new FreeVariables
    census(fdef.body)(using fdef.symbol)
    val locals = census.locals.distinct.toList
    val masked = fdef.params ++ locals
    census.free.filter(sym => !masked.contains(sym)).distinct.toList

  class FreeVariables extends TreeAccumulator:
    val locals = new mutable.ArrayBuffer[Symbol]
    val free = new mutable.ArrayBuffer[Symbol]

    // Use owner as context
    type Context = Symbol

    override def recurFunDef(fdef: FunDef)(using Context): Unit =
      free ++= freeVariables(fdef)

    def apply(word: Word)(using Context): Unit =
      word match
        case Ident(sym) =>
          // can be a global name
          free += sym

        case ValDef(sym, rhs) =>
          if !sym.isField then locals += sym
          recur(rhs)

        case obj: Object =>
          locals += obj.self
          recur(obj)

        case _ => recur(word)
