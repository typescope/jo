package sast

import Sast.*
import Types.Type
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

  given Text.Maker[Def] = v => showDef(v)

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
        val resType = TypeOps.finalResultType(fdef.symbol.info)
        val locals = rep(fdef.locals.map(sym => sym ~ ": " ~ sym.info), Text(", "))
        val captures = rep(fdef.captures, Text(", "))
        "@locals(" ~ locals ~ ")" ~ Text.BreakLine ~
        "@captures(" ~ captures ~ ")" ~ Text.BreakLine ~
        "fun " ~ fdef.name ~ tparamStr ~ params.mkString("(", ", ", "): ") ~ resType.show ~ " ="
        ~ indent(Text(fdef.body))

      case tdef: TypeDef =>
        "type " ~ tdef.name ~ " = " ~ tdef.symbol.info.show

      case pdef: ParamDef =>
        "param " ~ pdef.name ~ ": " ~ pdef.tpt

  def showWord(word: Word): Text =
    word match
      case IntLit(n) => Text(n.toString)

      case BoolLit(b) => Text(b.toString)

      case StringLit(s) =>
        "\"" ~ StringUtil.escape(s) ~ "\""

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

      case Encoded(repr) =>
        "(" ~ repr ~ ": " ~ word.tpe ~ ")"

      case Apply(fun, args) =>
        "(" ~ fun ~ " " ~ rep(args, Text(" ")) ~ ")"

      case TypeApply(fun, targs) =>
        fun ~ "[" ~ rep(targs, Text(", ")) ~ "]"

      case With(expr, args, only) =>
        if only && args.isEmpty then expr ~ " with none"
        else
          val onlyText = if only then Text("only ") else Text.Empty
          expr ~ " with " ~ onlyText ~ indent(rep(args, Text.BreakLine))

      case DefaultParam(paramRef, default) =>
        paramRef ~ " default " ~ default

      case Assign(sym, rhs) =>
        sym.name ~ " = " ~ rhs

      case If(cond, thenp, elsep) =>
        "if " ~ cond ~ " then" ~ indent:
            thenp
        ~ "else" ~ indent:
           elsep

      case While(cond, body) =>
        "while " ~ cond ~ " do" ~ indent:
            body

      case Block(words) =>
        if words.size == 1 then
          showWord(words.head)
        else if words.size > 1 then
          rep(words, Text.BreakLine)
        else
          Text.Empty

      case vdef: ValDef => showDef(vdef)

      case fdef: FunDef => showDef(fdef)

      case tdef: TypeDef => showDef(tdef)
