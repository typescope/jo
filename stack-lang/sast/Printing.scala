package sast

import Sast.*
import Types.*
import Symbols.Symbol

import common.StringUtil
import common.Text
import common.Text.*

object Printing:

  def show(word: Word): String = showWord(word).toString

  def show(ns: Namespace): String = showNamespace(ns).toString

  inline def peek(enable: Boolean)(nss: List[Namespace]): List[Namespace] =
    inline if enable then
      for ns <- nss do println(show(ns))
    nss

  //----------------------------------------------------------------------------

  given Text.Maker[Word] = v => showWord(v)

  given Text.Maker[Pattern] = v => showPattern(v)

  given Text.Maker[Case] = v => "case " ~ v.pattern ~ " =>" ~ indent(v.body)

  given Text.Maker[Def] = v => showDef(v)

  given Text.Maker[ValDef | FunDef] = v => showDef(v)

  given Text.Maker[Type] = v => Text(v.show)

  given Text.Maker[TypeTree] = v => Text(v.tpe.show)

  given Text.Maker[Symbol] = v => Text(v.name)

  given Text.Maker[WithArg] = v => v.paramRef ~ " = " ~ v.rhs

  //----------------------------------------------------------------------------

  // implementation

  def showNamespace(ns: Namespace): Text =
    "namespace "  ~ ns.symbol ~ Text.BlankLine ~
    showImports(ns.imports) ~ Text.BlankLine ~
    rep(ns.defs, Text.BlankLine)

  def showImports(imports: List[Symbol]): Text =
    imports match
      case item :: items =>
        "import " ~ item.fullName ~ Text.BreakLine ~ showImports(items)

      case Nil =>
        Text.Empty

  def showDef(defn: Def): Text =
    defn match
      case ValDef(sym, rhs) =>
        val mod = if sym.isMutable then "var" else "val"
        mod ~ " " ~ sym.name ~ ": " ~ sym.info ~ " = " ~ rhs ~ Text.BreakLine

      case fdef: FunDef =>
        val tparams = fdef.tparams.map(sym => sym.name + " <: " + sym.info.show)
        val tparamStr = if tparams.isEmpty then "" else tparams.mkString("[", ", ", "]")
        val params = fdef.params.map(sym => sym.name + ": " + sym.info.show)
        val resType = fdef.resultType
        val locals = rep(fdef.locals.map(sym => sym ~ ": " ~ sym.info), Text(", "))
        val keyword = if fdef.symbol.isMethod then "def " else "fun "

        val receives =
          fdef.receives match
            case Some(Nil) =>
              Text(" receives none ")

            case Some(params) =>
              " receives " ~ rep(params, Text(", ")) ~ " "

            case _ =>
              Text.Empty

        "@locals(" ~ locals ~ ")" ~ Text.BreakLine ~
        keyword ~ fdef.name ~ tparamStr ~ params.mkString("(", ", ", "): ") ~ resType ~ receives ~ " =" ~ indent:
            fdef.body

      case pdef: PatDef =>
        val tparams =
          if pdef.tparams.isEmpty then Text.Empty
          else "[" ~ rep(pdef.tparams, Text(", "))  ~ "]"

        val params =
          if pdef.params.isEmpty then Text.Empty
          else "(" ~ rep(pdef.params, Text(", "))  ~ ")"

        val resType = ": " ~ pdef.resultType

        "pattern " ~ pdef.name ~ tparams ~ params ~ resType ~ " =" ~ indent(pdef.body)

      case tdef: TypeDef =>
        "type " ~ tdef.name ~ " = " ~ tdef.symbol.info.show

      case pdef: ParamDef =>
        "param " ~ pdef.name ~ ": " ~ pdef.tpt

  def showWord(word: Word): Text =
    word match
      case Literal(c) =>
        c match
          case Constant.Bool(b) => Text(b.toString)

          case Constant.String(s) =>
            "\"" ~ StringUtil.escape(s) ~ "\""

          case Constant.Int(n) =>
            val isChar = word.tpe.refersTo(Definitions.instance.Predef_Char)
            if isChar then
              "'" ~ StringUtil.escapeChar(n.toChar) ~ "'"
            else
              Text(n.toString)

      case Ident(sym) => Text(sym.name)

      case Select(qual, name) =>
        qual ~ "." ~ name

      case RecordLit(fields) =>
        "{" ~ indent:
            rep(
              fields.map { (f, rhs) => f ~ " = " ~ rhs },
              Text(", ")
            )
        ~ "}"

      case tagged: TaggedLit =>
        "#" ~ tagged.tag ~ "(" ~ rep(tagged.args, Text(", ")) ~ ")"

      case Encoded(repr) =>
        "(" ~ repr ~ ": " ~ word.tpe ~ ")"

      case Apply(fun, args) =>
        fun ~ indent:
          rep(args, Text.BreakLine)

      case TypeApply(fun, targs) =>
        fun ~ "[" ~ rep(targs, Text(", ")) ~ "]"

      case With(expr, args) =>
        val withText =
          if args.isEmpty then
            Text.Empty
          else
            " with " ~ indent(rep(args, Text.BreakLine))

        "(" ~ expr ~ withText ~ ")"

      case Allow(expr, params) =>
        val paramText =
          if params.isEmpty then Text("none")
          else rep(params, Text(", "))

        "(" ~ expr ~ " allow " ~ paramText ~ ")"

      case Assign(id, rhs) =>
        id ~ " = " ~ rhs

      case FieldAssign(qual, name, rhs) =>
        qual ~ "." ~ name ~ " <- " ~ rhs

      case If(cond, thenp, elsep) =>
        "if " ~ cond ~ " then" ~ indent:
            thenp
        ~ "else" ~ indent:
           elsep

      case While(cond, body) =>
        "while " ~ cond ~ " do" ~ indent:
            body

      case Match(scrutinee, cases) =>
        "match " ~ scrutinee ~ indent:
          rep(cases, Text.BlankLine)

      case Block(words) =>
        if words.size == 1 then
          showWord(words.head)
        else if words.size > 1 then
          Text.BreakLine ~ rep(words, Text.BreakLine)
        else
          Text.Empty

      case Object(self, vals, defs) =>
        "object {" ~ indent:
           rep(vals, Text.BreakLine)
           ~ Text.BlankLine
           ~ rep(defs, Text.BreakLine)
        ~ "}"

      case vdef: ValDef => showDef(vdef)

      case fdef: FunDef => showDef(fdef)

      case pdef: PatDef => showDef(pdef)

      case tdef: TypeDef => showDef(tdef)

  def showPattern(pat: Pattern): Text =
    pat match
      case TypePattern(tpe) => ": " ~ tpe

      case WildcardPattern() => Text("_")

      case AscribePattern(id, inner) => "(" ~ id ~ " @ " ~ inner ~ ")"

      case ApplyPattern(id, nested) =>
        id ~ "(" ~ rep(nested, Text(", ")) ~ ")"

      case tagged @ TagPattern(_, nested) =>
        "#" ~ tagged.tag ~ " " ~ rep(nested, Text(" "))
