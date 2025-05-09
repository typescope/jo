package sast

import Sast.*
import Types.*
import Symbols.Symbol

import common.StringUtil
import common.Text
import common.Text.*

object Printing:

  def show(word: Word)(using Definitions): String =
    showWord(word).toString

  def show(pattern: Pattern)(using Definitions): String =
    showPattern(pattern).toString

  def show(pattern: RegexPattern)(using Definitions): String =
    showRegexPattern(pattern).toString

  def show(ns: Namespace)(using Definitions): String =
    showNamespace(ns).toString

  inline def peek(enable: Boolean)(nss: List[Namespace])
    (using Definitions)
  : List[Namespace] =

    inline if enable then
      for ns <- nss do println(show(ns))
    nss

  //----------------------------------------------------------------------------

  given (using Definitions): Text.Maker[Word] =
    v => showWord(v)

  given (using Definitions): Text.Maker[Pattern] =
    v => showPattern(v)

  given (using Definitions): Text.Maker[RegexPattern] =
    v => showRegexPattern(v)

  given (using Definitions): Text.Maker[Case] =
    v => "case " ~ v.pattern ~ " =>" ~ indent(v.body)

  given (using Definitions): Text.Maker[Def] =
    v => showDef(v)

  given (using Definitions): Text.Maker[ValDef | FunDef] =
    v => showDef(v)

  given (using Definitions): Text.Maker[Type] =
    v => Text(v.show)

  given (using Definitions): Text.Maker[TypeTree] =
    v => Text(v.tpe.show)

  given (using Definitions): Text.Maker[Symbol] =
    v => Text(v.name)

  given (using Definitions): Text.Maker[WithArg] =
    v => v.paramRef ~ " = " ~ v.rhs

  //----------------------------------------------------------------------------

  // implementation

  def showNamespace(ns: Namespace)(using Definitions): Text =
    "namespace "  ~ ns.symbol ~ Text.BlankLine ~
    showImports(ns.imports) ~ Text.BlankLine ~
    rep(ns.defs, Text.BlankLine)

  def showImports(imports: List[Symbol])(using Definitions): Text =
    imports match
      case item :: items =>
        "import " ~ item.fullName ~ Text.BreakLine ~ showImports(items)

      case Nil =>
        Text.Empty

  def showDef(defn: Def)(using Definitions): Text =
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

        // Print top-level or patterns as cases
        def printBody(pattern: Pattern): Text =
          pattern match
            case OrPattern(lhs, rhs) =>
              "case " ~ lhs ~ Text.BreakLine ~ printBody(rhs)

            case _ =>
              "case " ~ showPattern(pattern)

        "pattern " ~ pdef.name ~ tparams ~ params ~ resType ~ " =" ~ indent:
            printBody(pdef.body)


      case tdef: TypeDef =>
        "type " ~ tdef.name ~ " = " ~ tdef.symbol.info.show

      case pdef: ParamDef =>
        "param " ~ pdef.name ~ ": " ~ pdef.tpt

      case Section(sym, defs) =>
        "section " ~ sym ~ indent:
            rep(defs, Text.BlankLine)

  def showWord(word: Word)(using defn: Definitions): Text =
    word match
      case Literal(c) =>
        c match
          case Constant.Bool(b) => Text(b.toString)

          case Constant.String(s) =>
            "\"" ~ StringUtil.escape(s) ~ "\""

          case Constant.Int(n) =>
            val isChar = word.tpe.refers(defn.Predef_Char)
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

  def showPattern(pat: Pattern)(using Definitions): Text =
    pat match
      case TypePattern(tpe) => ": " ~ tpe

      case WildcardPattern() => Text("_")

      case AscribePattern(id, inner) =>
        "(" ~ id ~ " @ " ~ inner ~ ")"

      case ApplyPattern(pred, nested) =>
        pred ~ "(" ~ rep(nested, Text(", ")) ~ ")"

      case OrPattern(lhs, rhs) =>
        lhs ~ " | " ~ rhs

      case tagged @ TagPattern(_, nested) =>
        "#" ~ tagged.tag ~ " " ~ rep(nested, Text(" "))

      case ValuePattern(value) =>
        showWord(value)

      case GuardPattern(pattern, guard) =>
        pattern ~ " if " ~ guard

      case TermBindingPattern(pattern, bindings) =>
        pattern ~ " then " ~ rep(bindings, Text(", "))

      case SeqPattern(patterns) =>
        "[" ~ rep(patterns, Text(", ")) ~ "]"

  def showRegexPattern(pat: RegexPattern)(using Definitions): Text =
    pat match
      case AtomPattern(pattern) => showPattern(pattern)

      case SkipToPattern(pattern) => ">" ~ pattern

      case StarPattern(pattern) => pattern ~ "*"
