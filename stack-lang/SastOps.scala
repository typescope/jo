import Sast.*

object SastOps:
  trait TreeMap:
    type Context

    def apply(word: Word)(using Context): Word

    def recur(word: Word)(using Context): Word =
      word match
        case _: IntLit | _: BoolLit | _: Ident | _: FunRef =>
          word

        case Select(qual, name) =>
          Select(this(qual), name)(word.tpe, word.span)

        case RecordLit(fields) =>
          val fields2 = fields.map:
            case (f, rhs) => f -> this(rhs)

          RecordLit(fields2)(word.tpe, word.span)

        case Encoded(repr) =>
          Encoded(this(repr))(word.tpe)

        case Call(fun) =>
          Call(this(fun))(word.span)

        case Assign(sym, rhs) =>
          Assign(sym, this(rhs))(word.span)

        case ValDef(sym, rhs) =>
          Assign(sym, this(rhs))(word.span)

        case fdef: FunDef =>
          val body = this(fdef.body)
          fdef.copy(body = body)(fdef.locals, fdef.captures, fdef.span)

        case If(cond, thenp, elsep) =>
          If(this(cond), this(thenp), this(elsep))(word.tpe, word.span)

        case While(cond, body) =>
          While(this(cond), this(body))(word.span)

        case Phrase(words) =>
          Phrase(words.map(this.recur))(word.tpe, word.span)
