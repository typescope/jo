import Sast.*

object SastOps:
  def show(prog: Prog): String =
    val sb = new StringBuilder
    for defn <- prog.defs do
      defn match
        case funDef: FunDef  =>
          sb.append(show(funDef))

        case vdef: ValDef =>
          sb.append(show(vdef))

        case tdef: TypeDef =>
          sb.append("type " + tdef.symbol.name + " = " + tdef.symbol.info.show)

    sb.append(show(prog.main))
    sb.toString

  def show(word: Word): String =
    word match
      case IntLit(n) => n.toString

      case BoolLit(b) => b.toString

      case Ident(sym) => sym.name

      case FunRef(sym) => sym.name

      case Select(qual, name) =>
        show(qual) + "." + name

      case RecordLit(fields) =>
        val fields2 = fields.map:
          case (f, rhs) => f -> show(rhs)

        fields2.map { (f, rhs) => f + " = " + rhs }.mkString("{", ", ", "}")

      case Encoded(repr) =>
        "(" + show(repr) + ": " + word.tpe.show + ")"

      case Call(fun) =>
        "=>" + show(fun)

      case Assign(sym, rhs) =>
        sym.name + " = " + show(rhs) + "\n"

      case ValDef(sym, rhs) =>
        val mod = if sym.isMutable then "var" else "val"
        mod + " " + sym.name + ":" + sym.info.show + " = " + show(rhs) + "\n"

      case fdef: FunDef =>
        val tparams = fdef.tparams.map(sym => sym.name + " " + sym.info.show)
        val tparamStr = if tparams.isEmpty then "" else tparams.mkString("[", ", ", "]")
        val params = fdef.params.map(sym => sym.name + ": " + sym.info.show)
        val resType = TypeOps.finalResultType(fdef.symbol.info)
        "fun " + fdef.name + " " + tparamStr + params.mkString("(", ", ", "): ") + resType.show + " = \n"
        + show(fdef.body) + "\n"

      case If(cond, thenp, elsep) =>
        "if " + show(cond) + " then\n" +
          show(thenp) + "\n" +
        "else" +
          show(elsep) + "\n"

      case While(cond, body) =>
        "while " + show(cond) + " do\n" +
          show(body) + "\n"

      case Phrase(words) =>
        if words.size == 1 then
          show(words.head)
        else if words.size > 1 then
          words.map(show).mkString("{", " ", "}")
        else
          ""

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
