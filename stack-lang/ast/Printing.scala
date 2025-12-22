package ast

import Trees.*

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

  given Text.Maker[ParamAdapter] = v =>
    v match
      case ParamAdapter.Function(ref) => showWord(ref)
      case ParamAdapter.Member(name) => "." ~ name

  given Text.Maker[ViewSpec] = v =>
    v.adapter match
      case Some(adapter) => v.tpe ~ " with " ~ showWord(adapter)
      case None => showType(v.tpe)

  given Text.Maker[Param] = v =>
    v.ident ~ showTypeAnnot(v.tpt)

  given Text.Maker[AutoCandidate] = v =>
    v match
      case AutoCandidate.Value(ref) => showWord(ref)
      case AutoCandidate.Member(tpe, name) => "[" ~ tpe ~ "]." ~ name

  given Text.Maker[Auto] = v =>
    val candidatesText =
      if v.candidates.isEmpty then Text.Empty
      else " in [" ~ v.candidates.join(", ") ~ "]"
    v.ident ~ showTypeAnnot(v.tpt) ~ candidatesText

  given Text.Maker[TypeParam] = v => v.ident ~ showTypeBound(v.bound)

  given Text.Maker[WithArg] = v => v.paramRef ~ " = " ~ v.rhs

  given Text.Maker[HavingBinding] = v => v.tpe ~ " = " ~ v.value

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

  def showView(view: ViewDecl): Text =
    val rhs = view.rhs match
      case None => Text.Empty
      case Some(expr) => " = " ~ expr
    "view " ~ view.tpe ~ rhs

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

        mods ~ "def " ~ fdef.name ~ tparams ~ params ~ autos ~ resType ~ receives ~ body

      case cdef: ClassDef =>
        val mods =
          if cdef.modifiers.isEmpty then Text.Empty
          else cdef.modifiers.join(" ") ~ " "

        val tparams =
          if cdef.tparams.isEmpty then Text.Empty
          else "[" ~ cdef.tparams.join(", ")  ~ "]"

        val params =
          if cdef.params.isEmpty then Text.Empty
          else "(" ~ cdef.params.join(", ")  ~ ")"

        val viewsAndMembers = cdef.views.map(showView) ++ cdef.vals.map(showDef) ++ cdef.funs.map(showDef)

        mods ~ "class " ~ cdef.name ~ tparams ~ params ~ indent:
          viewsAndMembers.join(Text.BlankLine)

      case idef: InterfaceDef =>
        val mods =
          if idef.modifiers.isEmpty then Text.Empty
          else idef.modifiers.join(" ") ~ " "

        val tparams =
          if idef.tparams.isEmpty then Text.Empty
          else "[" ~ idef.tparams.join(", ")  ~ "]"

        mods ~ "interface " ~ idef.name ~ tparams ~ indent:
          idef.members.join(Text.BlankLine)

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

      case edef: UnionDef =>
        val tparams =
          if edef.tparams.isEmpty then Text.Empty
          else "[" ~ edef.tparams.join(", ") ~ "]"

        val branches =
          edef.branches.map: branch =>
            val params =
              if branch.params.isEmpty then Text.Empty
              else "(" ~ branch.params.join(", ") ~ ")"
            branch.ident ~ params

        "union " ~ edef.ident ~ tparams ~ " = " ~ branches.join(" | ")

      case Section(name, defs) =>
        "section " ~ name ~ indent:
            defs.join(Text.BlankLine)

      case AliasDef(ident, kind, qualid) =>
        val mods =
          if defn.modifiers.isEmpty then Text.Empty
          else defn.modifiers.join(" ") ~ " "

        mods ~ "alias " ~ kind.toString ~ " " ~ ident ~ " = " ~ qualid

  def showWord(word: Word): Text =
    word match
      case IntLit(n) => Text(n.toString)

      case CharLit(c) => Text("'" ~ StringUtil.escapeChar(c) ~ "'")

      case BoolLit(b) => Text(b.toString)

      case StringLit(s) => "\"" ~ StringUtil.escape(s) ~ "\""

      case InterpolatedString(parts) =>
        var result: Text = Text("\"")
        for (part <- parts) do
          part match
            case StringLit(value) => result = result ~ StringUtil.escape(value)
            case expr => result = result ~ "\\{" ~ showWord(expr) ~ "}"
        result ~ "\""

      case ListLit(words) => "[" ~ words.join(", ") ~ "]"

      case Ident(name) => Text(name)

      case Apply(fun, args, havingBindings) =>
        val argsText = args.join(", ")
        val havingText =
          if havingBindings.isEmpty then Text.Empty
          else " having " ~ havingBindings.join(", ")
        fun ~ "(" ~ argsText ~ ")" ~ havingText

      case BracketApply(subject, args) =>
        subject ~ "[" ~ args.join(", ") ~ "]"

      case New(tpt, args) =>
        "new " ~ tpt ~ "(" ~ args.join(", ") ~ ")"

      case InfixOperatorCall(lhs, op, rhs) =>
        "(" ~ lhs ~ " " ~ op ~ " " ~ rhs ~ ")"

      case PrefixOperatorCall(op, rhs) =>
        "(" ~ op ~ " " ~ rhs ~ ")"

      case InfixCall(preArgs, fun, postArgs) =>
        "(" ~ preArgs.join(" ") ~ " " ~ fun ~ " " ~ postArgs.join(" ") ~ ")"

      case Select(qual, name) =>
        qual ~ "." ~ name

      case IsExpr(scrutinee, pattern) =>
        scrutinee ~ " is " ~ showPattern(pattern)

      case RecordLit(fields) =>
        "{" ~ indent:
              fields.map { f => f.name ~ " = " ~ f.arg }.join(", ")
        ~ "}"

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

      case ViewAccess(value, viewType) =>
        value ~ ".view[" ~ viewType ~ "]"

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
        "{" ~ indent:
           members.join(Text.BreakLine)
        ~ "}"

      case defn: Def =>
        showDef(defn)

  def showPattern(pat: Pattern): Text =
    pat match
      case ref: RefTree =>
        showWord(ref)

      case LiteralPattern(value) =>
        showWord(value)

      case TypePattern(id, tpt) =>
        id ~ ": " ~ tpt

      case BindPattern(id, pattern) =>
        id ~ " @ " ~ showPattern(pattern)

      case ApplyPattern(fun, args) =>
        val argText = args.map(showPattern).join(", ")
        showWord(fun) ~ "(" ~ argText ~ ")"

      case SequencePattern(patterns) =>
        "[" ~ patterns.map(showPattern).join(", ") ~ "]"

      case GuardPattern(pattern, guard) =>
        showPattern(pattern) ~ " if " ~ showWord(guard)

      case AssignPattern(pattern, assignments) =>
        val assigns = assignments.map { case (id, value) => id.name ~ " = " ~ showWord(value) }.join(", ")
        showPattern(pattern) ~ " with " ~ assigns

      case ExprPattern(patterns) =>
        patterns.map(showPattern).join(" ")

      case NestedMatchPattern(expr, pattern) =>
        "match " ~ showWord(expr) ~ " with " ~ showPattern(pattern)

  def showModifier(mod: Modifier): Text =
    Text(mod.show)

  def showType(tpt: TypeTree): Text =
    tpt match
      case _: EmptyTypeTree => Text.Empty

      case ref: RefTree => showWord(ref)

      case RecordType(fields) =>
        "{" ~ fields.join(", ") ~ "}"

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
        "{" ~ indent:
           members.join(Text.BreakLine)
        ~ "}"

      case DuckType(tpe, adapters) =>
        "like " ~ tpe ~ " with [" ~ adapters.join(", ") ~ "]"

      case ViewType(tpe, views) =>
        "view " ~ tpe ~ " as " ~ views.join(", ")
