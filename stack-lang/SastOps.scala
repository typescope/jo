import Sast.*

object SastOps:
  trait TreeMap:
    type Context

    def apply(word: Word)(using Context): Word

    def recurValDef(vdef: ValDef)(using Context): ValDef =
      ValDef(vdef.symbol, this(vdef.rhs))(vdef.span)

    def recurFunDef(fdef: FunDef)(using Context): FunDef =
      val body = this(fdef.body)
      fdef.copy(body = body)(fdef.locals, fdef.captures, fdef.span)

    def recurTypeDef(tdef: TypeDef)(using Context): TypeDef = tdef

    def recur(defn: Def)(using Context): Def =
      defn match
        case vdef: ValDef => recurValDef(vdef)
        case fdef: FunDef => recurFunDef(fdef)
        case tdef: TypeDef => recurTypeDef(tdef)
        case cparam: ContextParam => cparam

    def recur(word: Word)(using Context): Word =
      word match
        case _: IntLit | _: BoolLit | _: Ident =>
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

        case With(expr, bindings) =>
          With(this(expr), bindings.map { case (param, e) => param -> this(e) })

        case Assign(sym, rhs) =>
          Assign(sym, this(rhs))(word.span)

        case vdef: ValDef =>
          recurValDef(vdef)

        case fdef: FunDef =>
          recurFunDef(fdef)

        case tdef: TypeDef =>
          recurTypeDef(tdef)

        case If(cond, thenp, elsep) =>
          If(this(cond), this(thenp), this(elsep))(word.tpe, word.span)

        case While(cond, body) =>
          While(this(cond), this(body))(word.span)

        case Phrase(words) =>
          Phrase(words.map(this.apply))(word.tpe, word.span)
