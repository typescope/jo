package ast

import Ast.*

import common.StringUtil
import common.Text
import common.Text.*

object Printing:

  def show(word: Word): String = showWord(word).toString

  def show(ns: Namespace): String = showNamespace(ns).toString

  //----------------------------------------------------------------------------

  // Use most-specific instance to break ambiguity between Word and TypeTree
  given Text.Maker[Ident] = v => showWord(v)

  given Text.Maker[RefTree] = v => showWord(v)

  given Text.Maker[Word] = v => showWord(v)

  given Text.Maker[Phrase] = v => showPhrase(v)

  given Text.Maker[Def] = v => showDef(v)

  given Text.Maker[TypeTree] = v => showType(v)

  given Text.Maker[Param] = v => v.ident ~ showTypeAnnot(v.typ)

  given Text.Maker[TypeParam] = v => v.ident ~ showTypeBound(v.bound)

  given Text.Maker[Pattern] = v => showPattern(v)

  given Text.Maker[WithArg] = v => v.paramRef ~ " = " ~ v.rhs

  given Text.Maker[Case] = v => "case " ~ v.pat ~ " =>" ~ indent(v.body)

  given Text.Maker[Import] = v => "import " ~ v.qualid


  //----------------------------------------------------------------------------

  // implementation

  def showNamespace(ns: Namespace): Text =
    "namespace "  ~ ns.qualid ~ Text.BlankLine ~
    rep(ns.imports, Text.BreakLine) ~ Text.BlankLine ~
    rep(ns.defs, Text.BlankLine)

  def showTypeAnnot(typ: TypeTree): Text =
    if typ.isEmpty then Text.Empty else ": " ~ typ

  def showTypeBound(typ: TypeTree): Text =
    if typ.isEmpty then Text.Empty else " <: " ~ typ

  def showDef(defn: Def): Text =
    defn match
      case Param(id, tpt) => "param " ~ id ~ ": " ~ tpt

      case ValDef(id, tpt, rhs, mutable) =>
        val mod = if mutable then "var" else "val"
        mod ~ " " ~ id ~ showTypeAnnot(tpt) ~ " = " ~ rhs

      case fdef: FunDef =>
        val tparams =
          if fdef.tparams.isEmpty then Text.Empty
          else "[" ~ rep(fdef.tparams, Text(", "))  ~ "]"

        val params =
          if fdef.params.isEmpty then Text.Empty
          else "(" ~ rep(fdef.params, Text(", "))  ~ ")"

        val resType = showTypeAnnot(fdef.resType)

        "fun " ~ fdef.name ~ tparams ~ params ~ resType ~ " ="
        ~ indent(Text(fdef.body))

      case tdef: TypeDef =>
        val tparams =
          if tdef.tparams.isEmpty then Text.Empty
          else "[" ~ rep(tdef.tparams, Text(", "))  ~ "]"

        "type " ~ tdef.ident ~ tparams ~ " = " ~ tdef.rhs


  def showWord(word: Word): Text =
    word match
      case IntLit(n) => Text(n.toString)

      case BoolLit(b) => Text(b.toString)

      case StringLit(s) => "\"" ~ StringUtil.escape(s) ~ "\""

      case Ident(name) => Text(name)

      case Select(qual, name) =>
        qual ~ "." ~ name

      case RecordLit(fields) =>
        "{" ~ indent:
            rep(
              fields.map { f => f.name ~ " = " ~ f.arg },
              Text(", ")
            )
        ~ "}"

      case Variant(tag, values, typ) =>
        val args =
          if values.isEmpty then Text.Empty
          else "(" ~ rep(values, Text(", ")) ~ ")"
        typ ~ "#" ~ tag ~ args

      case Lambda(params, body) =>
        "(" ~ rep(params, Text(", ")) ~ ") =>" ~ indent(body)

      case Fence(phrase) =>
        "(" ~ phrase ~ ")"

      case With(expr, args) =>
        "(" ~ expr ~ " with " ~ rep(args, Text(", ")) ~ ")"

      case DefaultParam(paramRef, default) =>
        paramRef ~ " default " ~ default

      case TypeApply(fun, targs) =>
        fun ~ "[" ~ rep(targs, Text(", ")) ~ "]"

      case Expr(words) =>
        if words.size == 1 then
          showWord(words.head)
        else if words.size > 1 then
          "(" ~ rep(words, Text(" ")) ~ ")"
        else
          Text.Empty

      case Block(phrases) =>
        rep(phrases, Text.BreakLine)

    end match
  end showWord

  def showPhrase(phrase: Phrase): Text =
    phrase match
      case word: Word =>
        showWord(word)

      case If(cond, thenp, elsep) =>
        "if " ~ cond ~ " then" ~ indent:
            thenp
        ~ "else" ~ indent:
           elsep

      case While(cond, body) =>
        "while " ~ cond ~ " do" ~ indent:
            body

      case Assign(id, rhs) =>
        id ~ " = " ~ rhs

      case patmat: Ast.Match =>
        "match " ~ patmat.scrutinee ~ indent:
          rep(patmat.cases, Text.BlankLine)

      case defn: Def =>
        showDef(defn)

  def showPattern(pat: Pattern): Text =
    pat match
      case _: Wildcard => Text("_")

      case TagPat(tag, bindings) =>
        val params =
          if bindings.isEmpty then Text.Empty
          else "(" ~ rep(bindings, Text(", ")) ~ ")"
        "#" ~ tag ~ params

  def showType(tpt: TypeTree): Text =
    tpt match
      case _: EmptyTypeTree => Text.Empty

      case ref: RefTree => showWord(ref)

      case RecordType(fields) =>
        "{" ~ rep(fields, Text(", ")) ~ "}"

      case UnionType(branches) =>
         val branchesText = branches.map: b =>
           val params =
             if b.params.isEmpty then Text.Empty
             else "(" ~ rep(b.params, Text(", ")) ~ ")"
           b.tag ~ params
         "<" ~ rep(branchesText, Text(", ")) ~ ">"

      case AppliedType(tpeCtor, targs) =>
        tpeCtor ~ "[" ~ rep(targs, Text(", ")) ~ "]"

      case FunctionType(paramTypes, resultType) =>
        rep(paramTypes, Text(", ")) ~ " => " ~ resultType
