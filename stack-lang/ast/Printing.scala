package ast

import Ast.*

import common.StringUtil
import common.Text
import common.Text.*

object Printing:

  def show(word: Word): String = showWord(word).toString

  def show(ns: Namespace): String = showNamespace(ns).toString

  def print(nss: List[Namespace]): Unit =
    for ns <- nss do
      println(show(ns))
      println

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

  given Text.Maker[Modifier] = v => showModifier(v)


  //----------------------------------------------------------------------------

  // implementation

  def showNamespace(ns: Namespace): Text =
    "namespace "  ~ ns.qualid ~ Text.BlankLine ~
    ns.imports.join(Text.BreakLine) ~ Text.BlankLine ~
    ns.defs.join(Text.BlankLine)

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
        val mods =
          if defn.modifiers.isEmpty then Text.Empty
          else defn.modifiers.join(" ") ~ " "

        val kind = if mutable then "var" else "val"
        mods ~ kind ~ " " ~ id ~ showTypeAnnot(tpt) ~ " = " ~ rhs

      case fdef: FunDef =>
        val mods =
          if fdef.modifiers.isEmpty then Text.Empty
          else fdef.modifiers.join(" ") ~ " "

        val tparams =
          if fdef.tparams.isEmpty then Text.Empty
          else "[" ~ fdef.tparams.join(", ")  ~ "]"

        val params =
          if fdef.params.isEmpty then Text.Empty
          else "(" ~ fdef.params.join(", ")  ~ ")"

        val autos =
          if fdef.autos.isEmpty then Text.Empty
          else "(auto " ~ fdef.autos.join(", ")  ~ ")"

        val resType = showTypeAnnot(fdef.resultType)

        val receives =
          fdef.receives match
            case Some(Nil) =>
              Text(" receives none ")

            case Some(params) =>
              " receives " ~ params.join(", ") ~ " "

            case _ =>
              Text.Empty

        val body =
          if fdef.body.isEmptyBlock then Text.Empty
          else " =" ~ indent(fdef.body)

        mods ~ "fun " ~ fdef.name ~ tparams ~ params ~ autos ~ resType ~ receives ~ body

      case pdef: PatDef =>
        val tparams =
          if pdef.tparams.isEmpty then Text.Empty
          else "[" ~ pdef.tparams.join(", ")  ~ "]"

        val params =
          if pdef.params.isEmpty then Text.Empty
          else "(" ~ pdef.params.join(", ")  ~ ")"

        val resType = showTypeAnnot(pdef.resultType)

        "pattern " ~ pdef.name ~ tparams ~ params ~ resType ~ " =" ~ indent:
          val caseText = pdef.cases.map(caseDef => "case " ~ showPattern(caseDef.pat))
          caseText.join(Text.BreakLine)

      case tdef: TypeDef =>
        val tparams =
          if tdef.tparams.isEmpty then Text.Empty
          else "[" ~ tdef.tparams.join(", ")  ~ "]"

        val token = if tdef.isBound then " <: " else " = "

        "type " ~ tdef.ident ~ tparams ~ token ~ tdef.rhs

      case ddef: DataDef =>
        val tparams =
          if ddef.tparams.isEmpty then Text.Empty
          else "[" ~ ddef.tparams.join(", ")  ~ "]"

        val params =
          if ddef.params.isEmpty then Text.Empty
          else "(" ~ ddef.params.join(", ")  ~ ")"

        "data " ~ ddef.ident ~ tparams ~ params

      case edef: EnumDef =>
        val tparams =
          if edef.tparams.isEmpty then Text.Empty
          else "[" ~ edef.tparams.join(", ") ~ "]"

        val branches =
          edef.branches.map: branch =>
            val params =
              if branch.params.isEmpty then Text.Empty
              else "(" ~ branch.params.join(", ") ~ ")"
            branch.tag ~ params

        "data " ~ edef.ident ~ tparams ~ " = " ~ branches.join(" | ")

      case Section(name, defs) =>
        "section " ~ name ~ indent:
            defs.join(Text.BlankLine)

      case AliasDef(qualid) =>
        "alias " ~ qualid

  def showWord(word: Word): Text =
    word match
      case IntLit(n) => Text(n.toString)

      case CharLit(c) => Text("'" ~ StringUtil.escapeChar(c) ~ "'")

      case BoolLit(b) => Text(b.toString)

      case StringLit(s) => "\"" ~ StringUtil.escape(s) ~ "\""

      case SeqLit(words) => "[" ~ words.join(", ") ~ "]"

      case Ident(name) => Text(name)

      case Apply(fun, args) =>
        val argsText = args.join(", ")
        fun ~ "(" ~ argsText ~ ")"

      case DotlessCall(obj, meth, arg) =>
        "(" ~ obj ~ " " ~ meth ~ " " ~ arg ~ ")"

      case InfixCall(preArgs, fun, postArgs) =>
        "(" ~ preArgs.join(" ") ~ " " ~ fun ~ " " ~ postArgs.join(" ") ~ ")"

      case Select(qual, name) =>
        qual ~ "." ~ name

      case RecordLit(fields) =>
        "{" ~ indent:
              fields.map { f => f.name ~ " = " ~ f.arg }.join(", ")
        ~ "}"

      case Tag(name) =>
        "#" ~ name

      case TypeAscribe(expr, tpt) =>
        expr ~ "as" ~ tpt

      case Lambda(params, body) =>
        "(" ~ params.join(", ") ~ ") =>" ~ indent(body)

      case Fence(phrase) =>
        "(" ~ phrase ~ ")"

      case With(expr, args) =>
        val withText = " with " ~ indent:
            args.join(Text.BreakLine)

        "(" ~ expr ~ withText ~ ")"

      case Allow(expr, params) =>
        val paramText =
          if params.isEmpty then Text("none")
          else params.join(", ")

        "(" ~ expr ~ " allow " ~ paramText ~ ")"

      case TypeApply(fun, targs) =>
        fun ~ "[" ~ targs.join(", ") ~ "]"

      case Expr(words) =>
        if words.size == 1 then
          showWord(words.head)
        else if words.size > 1 then
          "(" ~ words.join(" ") ~ ")"
        else
          Text.Empty

      case Block(phrases) =>
        phrases.join(Text.BreakLine)

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
          cases.join(Text.BlankLine)

      case Object(members) =>
        "object {" ~ indent:
           members.join(Text.BreakLine)
        ~ "}"

      case defn: Def =>
        showDef(defn)

  def showPattern(pat: Word): Text =
    (pat: @unchecked) match
      case _: Tag | _: Ident | _: StringLit | _: IntLit | _: CharLit | _: BoolLit =>
        showWord(pat)

      case Apply(fun, args) if args.nonEmpty =>
        val argText = args.map(showPattern).join(", ")
        showPattern(fun) ~ "(" ~ argText  ~ ")"

      case Expr(words) if words.nonEmpty =>
        words.map(showPattern).join(" ")

      case TypeAscribe(id: Ident, tpt) =>
        id ~ ": " ~ tpt

      case If(cond, thenp, Block(Nil)) =>
        showPattern(thenp) ~ " if " ~ thenp

      case With(expr, args) =>
        val withText = " then " ~ args.join(", ")
        expr ~ withText

      case Assign(id: Ident, rhs) =>
        id ~ "@" ~ rhs

      case SeqLit(words) => "[" ~ words.map(showPattern).join(", ") ~ "]"

  def showModifier(mod: Modifier): Text =
    mod match
     case Modifier.Auto() => Text("auto")

     case Modifier.Private() => Text("private")

  def showType(tpt: TypeTree): Text =
    tpt match
      case _: EmptyTypeTree => Text.Empty

      case ref: RefTree => showWord(ref)

      case RecordType(fields) =>
        "{" ~ fields.join(", ") ~ "}"

      case TagType(tag, params) =>
        val paramsStr =
          if params.isEmpty then Text.Empty
          else "(" ~ params.join(", ") ~ ")"
        "#" ~ tag ~ paramsStr

      case UnionType(branches) =>
        branches.join(" | ")

      case ExprType(types) =>
        types.join(" ")

      case AppliedType(tpeCtor, targs) =>
        tpeCtor ~ "[" ~ targs.join(", ") ~ "]"

      case FunctionType(paramTypes, resultType, receives) =>
        val tp = paramTypes.join(", ") ~ " => " ~ resultType
        if receives.isEmpty then
          tp
        else
          tp ~ " receives " ~ receives.join(", ")

      case ObjectType(members) =>
        "object {" ~ indent:
           members.join(Text.BreakLine)
        ~ "}"
