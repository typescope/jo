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

  given Text.Maker[Def] = v => showDef(v)

  given Text.Maker[ValDef | FunDef] = v => showDef(v)

  given Text.Maker[TypeTree] = v => showType(v)

  given Text.Maker[Param] = v => v.ident ~ showTypeAnnot(v.tpt)

  given Text.Maker[TypeParam] = v => v.ident ~ showTypeBound(v.bound)

  given Text.Maker[WithArg] = v => v.paramRef ~ " = " ~ v.rhs

  given Text.Maker[Case] = v => "case " ~ showPattern(v.pat) ~ " =>" ~ indent(v.body)

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
      case ParamDef(id, tpt, default) =>
        val rhs = default match
          case None => Text.Empty
          case Some(word) => " = " ~ word

        "param " ~ id ~ ": " ~ tpt ~ rhs

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

        val resType = showTypeAnnot(fdef.resultType)

        val receives =
          fdef.receives match
            case Some(Nil) =>
              Text(" receives none ")

            case Some(params) =>
              " receives " ~ rep(params, Text(", ")) ~ " "

            case _ =>
              Text.Empty

        val body =
          if fdef.body.isEmptyBlock then Text.Empty
          else " =" ~ indent(fdef.body)

        "fun " ~ fdef.name ~ tparams ~ params ~ resType ~ receives ~ body

      case pdef: PatDef =>
        val tparams =
          if pdef.tparams.isEmpty then Text.Empty
          else "[" ~ rep(pdef.tparams, Text(", "))  ~ "]"

        val params =
          if pdef.params.isEmpty then Text.Empty
          else "(" ~ rep(pdef.params, Text(", "))  ~ ")"

        val resType = showTypeAnnot(pdef.resultType)

        "pattern " ~ pdef.name ~ tparams ~ params ~ resType ~ " =" ~ indent:
          val caseText = pdef.cases.map(caseDef => "case " ~ showPattern(caseDef.pat))
          rep(caseText, Text.BreakLine)

      case tdef: TypeDef =>
        val tparams =
          if tdef.tparams.isEmpty then Text.Empty
          else "[" ~ rep(tdef.tparams, Text(", "))  ~ "]"

        val token = if tdef.isBound then " <: " else " = "

        "type " ~ tdef.ident ~ tparams ~ token ~ tdef.rhs

      case ddef: DataDef =>
        val tparams =
          if ddef.tparams.isEmpty then Text.Empty
          else "[" ~ rep(ddef.tparams, Text(", "))  ~ "]"

        val params =
          if ddef.params.isEmpty then Text.Empty
          else "(" ~ rep(ddef.params, Text(", "))  ~ ")"

        "data " ~ ddef.ident ~ tparams ~ params

      case edef: EnumDef =>
        val tparams =
          if edef.tparams.isEmpty then Text.Empty
          else "[" ~ rep(edef.tparams, Text(", "))  ~ "]"

        val branches =
          edef.branches.map: branch =>
            val params =
              if branch.params.isEmpty then Text.Empty
              else "(" ~ rep(branch.params, Text(", "))  ~ ")"
            branch.tag ~ params

        "data " ~ edef.ident ~ tparams ~ " = " ~ rep(branches, Text(" | "))

      case Section(name, defs) =>
        "section " ~ name ~ indent:
            rep(defs, Text.BlankLine)

  def showWord(word: Word): Text =
    word match
      case IntLit(n) => Text(n.toString)

      case CharLit(c) => Text("'" ~ StringUtil.escapeChar(c) ~ "'")

      case BoolLit(b) => Text(b.toString)

      case StringLit(s) => "\"" ~ StringUtil.escape(s) ~ "\""

      case Ident(name) => Text(name)

      case Apply(fun, args) =>
        val argsText = rep(args, Text(", "))
        fun ~ "(" ~ argsText ~ ")"

      case DotlessCall(obj, meth, arg) =>
        "(" ~ obj ~ " " ~ meth ~ " " ~ arg ~ ")"

      case InfixCall(preArgs, fun, postArgs) =>
        "(" ~ rep(preArgs, Text(" ")) ~ " " ~ fun ~ " " ~ rep(postArgs, Text(" "))  ~ ")"

      case Select(qual, name) =>
        qual ~ "." ~ name

      case RecordLit(fields) =>
        "{" ~ indent:
            rep(
              fields.map { f => f.name ~ " = " ~ f.arg },
              Text(", ")
            )
        ~ "}"

      case Tag(name) =>
        "#" ~ name

      case TypeAscribe(expr, tpt) =>
        expr ~ "as" ~ tpt

      case Lambda(params, body) =>
        "(" ~ rep(params, Text(", ")) ~ ") =>" ~ indent(body)

      case Fence(phrase) =>
        "(" ~ phrase ~ ")"

      case With(expr, args) =>
        val withText = " with " ~ indent(rep(args, Text.BreakLine))

        "(" ~ expr ~ withText ~ ")"

      case Allow(expr, params) =>
        val paramText =
          if params.isEmpty then Text("none")
          else rep(params, Text(", "))

        "(" ~ expr ~ " allow " ~ paramText ~ ")"

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

      case If(cond, thenp, elsep) =>
        "if " ~ cond ~ " then" ~ indent:
            thenp
        ~ "else" ~ indent:
           elsep

      case While(cond, body) =>
        "while " ~ cond ~ " do" ~ indent:
            body

      case Assign(lhs, rhs) =>
        lhs ~ " = " ~ rhs

      case Match(scrutinee, cases) =>
        "match " ~ scrutinee ~ indent:
          rep(cases, Text.BlankLine)

      case Object(members) =>
        "object {" ~ indent:
           rep(members, Text.BreakLine)
        ~ "}"

      case defn: Def =>
        showDef(defn)

  def showPattern(pat: Word): Text =
    (pat: @unchecked) match
      case _: Tag | _: Ident | _: StringLit | _: IntLit | _: CharLit | _: BoolLit =>
        showWord(pat)

      case Apply(fun, args) if args.nonEmpty =>
        val argText = rep(args.map(showPattern), Text(", "))
        showPattern(fun) ~ "(" ~ argText  ~ ")"

      case Expr(words) if words.nonEmpty =>
        rep(words.map(showPattern), Text(", "))

      case TypeAscribe(id: Ident, tpt) =>
        id ~ ": " ~ tpt

      case If(cond, thenp, Block(Nil)) =>
        showPattern(thenp) ~ " if " ~ thenp

      case With(expr, args) =>
        val withText = " then " ~ rep(args, Text(", "))
        expr ~ withText

      case Assign(id: Ident, rhs) =>
        id ~ "@" ~ rhs


  def showType(tpt: TypeTree): Text =
    tpt match
      case _: EmptyTypeTree => Text.Empty

      case ref: RefTree => showWord(ref)

      case RecordType(fields) =>
        "{" ~ rep(fields, Text(", ")) ~ "}"

      case TagType(tag, params) =>
        val paramsStr =
          if params.isEmpty then Text.Empty
          else "(" ~ rep(params, Text(", ")) ~ ")"
        "#" ~ tag ~ paramsStr

      case UnionType(branches) =>
        rep(branches, Text(" | "))

      case AppliedType(tpeCtor, targs) =>
        tpeCtor ~ "[" ~ rep(targs, Text(", ")) ~ "]"

      case FunctionType(paramTypes, resultType, receives) =>
        val tp = rep(paramTypes, Text(", ")) ~ " => " ~ resultType
        if receives.isEmpty then
          tp
        else
          tp ~ " receives " ~ rep(receives, Text(", "))

      case ObjectType(members) =>
        "object {" ~ indent:
           rep(members, Text.BreakLine)
        ~ "}"
