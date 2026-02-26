package sast

import Trees.*

/** A tree traversal for non-toplevel code */
abstract class TreeTraverser:
  type Context

  def apply(word: Word)(using Context): Unit

  def apply(pattern: Pattern)(using Context): Unit = recur(pattern)

  def recurValDef(vdef: ValDef)(using Context): Unit = this(vdef.rhs)

  def recurLocalFunDef(fdef: FunDef)(using Context): Unit = this(fdef.body)

  def recurLocalTypeDef(tdef: TypeDef)(using Context): Unit = ()

  def recurLocalPatDef(pdef: PatDef)(using Context): Unit = this(pdef.body)

  def recur(pattern: Pattern)(using Context): Unit =
    pattern match
      case BindPattern(id, nested) =>
        this(nested)

      case TypePattern(tpt, nested) =>
        this(nested)

      case ApplyPattern(_, nested) =>
        for pat <- nested do this(pat)

      case OrPattern(lhs, rhs) =>
        this(lhs)
        this(rhs)

      case AndPattern(lhs, rhs) =>
        this(lhs)
        this(rhs)

      case NotPattern(nested) =>
        this(nested)

      case ValuePattern(value) =>
        this(value)

      case GuardPattern(guard) =>
        this(guard)

      case AssignPattern(assignments) =>
        assignments.foreach(this(_))

      case SeqPattern(pats) =>
        pats.foreach:
          case AtomPattern(pattern) => this(pattern)

          case RepeatPattern(_, guard) => guard.foreach(this.apply)

      case WildcardPattern() =>

  def recur(word: Word)(using Context): Unit =
    word match
      case _: Literal | _: Ident | _: New =>

      case Select(qual, name) =>
        this(qual)

      case RecordLit(fields) =>
        fields.foreach:
          case (_, rhs) => this(rhs)

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

      case vdef: ValDef => recurValDef(vdef)

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

      case Labeled(_, _, body) =>
        this(body)

      case Return(_, value) =>
        this(value)

      case IsExpr(scrutinee, pattern) =>
        this(scrutinee)
        this(pattern)

      case ClassTest(value, _) =>
        this(value)

      case Block(words) =>
        words.foreach(this.apply)

      case Match(scrutinee, cases) =>
        this(scrutinee)
        for Case(pat, body) <- cases do
          this(pat)
          this(body)

      case PatValDef(pattern, rhs) =>
        this(pattern)
        this(rhs)

      case Lambda(symbol, params, receives, body) =>
        this(body)
  end recur
end TreeTraverser
