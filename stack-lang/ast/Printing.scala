package ast

import Trees.*

import ast.Naming
import common.StringUtil
import common.Text
import common.Text.*

object Printing:

  def show(word: Word): String = showWord(word).toString

  def show(unit: FileUnit): String = showFileUnit(unit).toString

  def print(units: List[FileUnit]): Unit =
    for unit <- units do
      println(show(unit))
      println()

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

  given Text.Maker[Param] = v =>
    v.ident ~ showTypeAnnot(v.tpt)

  given Text.Maker[AutoCandidate] = v =>
    v match
      case AutoCandidate.Value(ref) => showWord(ref)
      case AutoCandidate.Member(tpe, name) => "[" ~ tpe ~ "]." ~ name

  given Text.Maker[Auto] = v =>
    val candidatesText =
      if v.candidates.isEmpty then Text.Empty
      else " with [" ~ v.candidates.join(", ") ~ "]"
    v.ident ~ showTypeAnnot(v.tpt) ~ candidatesText

  given Text.Maker[TypeParam] = v => Text(v.ident)

  given Text.Maker[WithArg] = v => v.paramRef ~ " = " ~ v.rhs

  given Text.Maker[Case] = v => "case " ~ showPattern(v.pat) ~ " =>" ~ indent(v.body)

  given Text.Maker[Import] = v =>
    val base = "import " ~ v.qualid
    v.alias match
      case Some(id) => base ~ " as " ~ id
      case None => base

  given Text.Maker[Modifier] = v => showModifier(v)


  //----------------------------------------------------------------------------

  // implementation

  def showFileUnit(unit: FileUnit): Text =
    "namespace "  ~ unit.qualid ~ Text.BlankLine ~
    unit.imports.join(Text.BreakLine) ~ Text.BlankLine ~
    unit.defs.join(Text.BlankLine)

  def showTypeAnnot(typ: TypeTree): Text =
    if typ.isEmpty then Text.Empty else ": " ~ typ

  def showCallArg(arg: CallArg): Text =
    arg match
      case word: Word => showWord(word)
      case NamedArg(id, value) => id.name ~ " = " ~ showWord(value)

  /** True for argument forms that are legal only as indented colon arguments,
    * never inline inside `fun(...)`: `with … in …` and `allow … in …` (possibly
    * wrapped in a single-element `Expr`/`Block`).
    */
  def isOpenForm(arg: CallArg): Boolean =
    def wordIsOpen(w: Word): Boolean = w match
      case _: With | _: Allow => true
      case Expr(x :: Nil)     => wordIsOpen(x)
      case Block(x :: Nil)    => wordIsOpen(x)
      case _                  => false

    arg match
      case NamedArg(_, value) => wordIsOpen(value)
      case w: Word            => wordIsOpen(w)

  def showView(view: ViewDecl): Text =
    val rhs = view.rhs match
      case None => Text.Empty
      case Some(expr) => " = " ~ expr
    "view " ~ view.tpt ~ rhs

  def showDocComment(doc: List[String]): Text =
    doc match
      case Nil => Text.Empty
      case title :: content =>
        if content.isEmpty then
          "// " ~ title ~ Text.BreakLine

        else
          val titleLine = "//[ " ~ title
          val endLine = "//]" ~ Text.BreakLine

          titleLine ~ Text.BreakLine
          ~ content.map(line => "  ! " ~ line).join(Text.BreakLine) ~ Text.BreakLine
          ~ endLine

  def showAnnotation(annot: Annotation): Text =
    val args =
      if annot.args.isEmpty then Text.Empty
      else "(" ~ annot.args.map(showCallArg).join(", ") ~ ")"
    "@" ~ annot.name ~ args

  def showAnnotations(annots: List[Annotation]): Text =
    if annots.isEmpty then Text.Empty
    else annots.map(showAnnotation).join(Text.BreakLine) ~ Text.BreakLine

  def showDef(defn: Def): Text =
    val docText = showDocComment(defn.docComment)
    val annotText = showAnnotations(defn.annotations)
    docText ~ annotText ~ showDefBody(defn)

  def showDefBody(defn: Def): Text =
    defn match
      case AnnotationDef(id, params) =>
        val ps =
          if params.isEmpty then Text.Empty
          else "(" ~ params.join(", ") ~ ")"
        "annotation " ~ id.name ~ ps

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
        // A field declaration without an initializer (e.g. `val name: String`)
        // carries an empty block as rhs; `=` is optional there
        val rhsText = if rhs.isEmptyBlock then Text.Empty else " =" ~ indent(rhs)
        mods ~ kind ~ " " ~ id ~ showTypeAnnot(tpt) ~ rhsText

      case AutoDef(id, tpt, rhs) =>
        "auto " ~ id ~ ": " ~ tpt ~ " =" ~ indent(rhs)

      case fdef: FunDef =>
        val mods =
          if fdef.modifiers.isEmpty then Text.Empty
          else fdef.modifiers.join(" ") ~ " "

        val preTparams  = fdef.tparams.take(fdef.preTypeParamCount)
        val postTparams = fdef.tparams.drop(fdef.preTypeParamCount)
        val preParams   = fdef.params.take(fdef.preParamCount)
        val postParams  = fdef.params.drop(fdef.preParamCount)

        def tparamList(ts: List[TypeParam]): Text =
          if ts.isEmpty then Text.Empty
          else "[" ~ ts.join(", ") ~ "]"

        // Add space for operator methods with no post-params so that the
        // scanner cannot fuse the name into the trailing colon/operator:
        // e.g. printing `def ~-: C` would scan as one operator token `~-:`.
        val needSpace = Naming.isOperator(fdef.name) && postParams.isEmpty

        def paramList(ps: List[Param], needSpace: Boolean = false): Text =
          if ps.isEmpty then
            if needSpace then Text(" ") else Text.Empty
          else "(" ~ ps.join(", ") ~ ")"

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

        val header =
          if fdef.preParamCount > 0 then
            // section method: def [preT](preP) name[postT](postP)
            "def " ~ tparamList(preTparams) ~ paramList(preParams) ~ " " ~
              fdef.name ~ tparamList(postTparams) ~ paramList(postParams, needSpace)
          else
            // plain method: def name[preT][postT](params)
            "def " ~ fdef.name ~ tparamList(preTparams) ~ tparamList(postTparams) ~ paramList(postParams, needSpace)

        mods ~ header ~ autos ~ resType ~ receives ~ body

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

      case edef: ExtensionDef =>
        val mods =
          if edef.modifiers.isEmpty then Text.Empty
          else edef.modifiers.join(" ") ~ " "

        val tparams =
          if edef.tparams.isEmpty then Text.Empty
          else "[" ~ edef.tparams.join(", ") ~ "]"

        mods ~ "extension " ~ edef.name ~ tparams ~ " for " ~ edef.baseTpt ~ indent:
          edef.funs.join(Text.BlankLine)

      case odef: ObjectDef =>
        val mods =
          if odef.modifiers.isEmpty then Text.Empty
          else odef.modifiers.join(" ") ~ " "

        val viewsAndMembers = odef.views.map(showView) ++ odef.funs.map(showDef)

        mods ~ "object " ~ odef.name ~ indent:
          viewsAndMembers.join(Text.BlankLine)

      case pdef: PatDef =>
        val tparams =
          if pdef.tparams.isEmpty then Text.Empty
          else "[" ~ pdef.tparams.join(", ")  ~ "]"

        val preParams = pdef.params.take(pdef.preParamCount)
        val postParams = pdef.params.drop(pdef.preParamCount)

        val preParamsText =
          if preParams.isEmpty then Text.Empty
          else "(" ~ preParams.join(", ")  ~ ") "

        val postParamsText =
          if postParams.isEmpty then Text.Empty
          else "(" ~ postParams.join(", ")  ~ ")"

        val resType = showTypeAnnot(pdef.resultType)

        "pattern " ~ preParamsText ~ pdef.name ~ tparams ~ postParamsText ~ resType ~ " =" ~ indent:
          val caseText = pdef.cases.map(patValDef => "case " ~ showPattern(patValDef.pat))
          caseText.join(Text.BreakLine)

      case tdef: TypeDef =>
        val preTparams  = tdef.tparams.take(tdef.preParamCount)
        val postTparams = tdef.tparams.drop(tdef.preParamCount)

        val preTparamsText =
          if preTparams.isEmpty then Text.Empty
          else "[" ~ preTparams.join(", ")  ~ "] "

        val postTparamsText =
          if postTparams.isEmpty then Text.Empty
          else "[" ~ postTparams.join(", ")  ~ "]"

        // An abstract type declaration has no rhs.
        val rhsText = if tdef.rhs.isEmpty then Text.Empty else " = " ~ tdef.rhs
        "type " ~ preTparamsText ~ tdef.ident ~ postTparamsText ~ rhsText

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

        val header = "union " ~ edef.ident ~ tparams ~ " = " ~ branches.join(" | ")
        if edef.funs.isEmpty then header
        else header ~ indent:
          edef.funs.map(showDef).join(Text.BlankLine)

      case Section(name, defs) =>
        "section " ~ name ~ indent:
            defs.join(Text.BlankLine)

  def showWord(word: Word): Text =
    word match
      case IntLit(n, _) => Text(n.toString)

      case FloatLit(d) => Text(d.toString)

      case CharLit(c) => Text("'" ~ StringUtil.escapeChar(c) ~ "'")

      case BoolLit(b) => Text(b.toString)

      case StringLit(s) => "\"" ~ StringUtil.escape(s) ~ "\""

      case RegexLit(pattern, flags, _) =>
        // Regex literals are delimited by backticks.
        val body        = pattern.replace("`", "\\`")
        val flagsPrefix = if flags.isEmpty then Text.Empty else "(?" ~ flags ~ ")"
        "`" ~ flagsPrefix ~ body ~ "`"

      case _: This => Text("this")

      case InterpolatedString(parts) =>
        var result: Text = Text("\"")
        for (part <- parts) do
          part match
            case StringLit(value) => result = result ~ StringUtil.escape(value)
            case expr => result = result ~ "\\{" ~ showWord(expr) ~ "}"
        result ~ "\""

      case ListLit(words) => "[" ~ words.join(", ") ~ "]"

      case Ident(name) => Text(name)

      case Apply(fun, args) =>
        // An open-expression argument (`with … in …`, `allow … in …`) cannot be
        // written inline as `fun(arg)` — those forms are only legal as indented
        // colon arguments. Switch the whole call to the colon-call form then.
        if args.exists(isOpenForm) then
          fun ~ ":" ~ indent(args.map(showCallArg).join(Text.BreakLine))
        else
          fun ~ "(" ~ args.map(showCallArg).join(", ") ~ ")"

      case BracketApply(subject, args) =>
        subject ~ "[" ~ args.join(", ") ~ "]"

      case New(tpt, args) =>
        "new " ~ tpt ~ "(" ~ args.map(showCallArg).join(", ") ~ ")"

      case InfixOperatorCall(lhs, op, rhs) =>
        "(" ~ lhs ~ " " ~ op ~ " " ~ rhs ~ ")"

      case PrefixOperatorCall(op, rhs) =>
        "(" ~ op ~ " " ~ rhs ~ ")"

      case InfixCall(preArgs, fun, postArgs) =>
        "(" ~ preArgs.join(" ") ~ " " ~ fun ~ " " ~ postArgs.join(" ") ~ ")"

      case Select(qual, name) =>
        qual ~ "." ~ name

      case IsExpr(scrutinee, pattern) =>
        scrutinee ~ " is (" ~ showPattern(pattern) ~ ")"

      case RescueExpr(scrutinee, pattern, handler) =>
        scrutinee ~ " rescue " ~ showPattern(pattern) ~ " => " ~ handler

      case TypeAscribe(expr, tpt) =>
        expr ~ "as" ~ tpt

      case Lambda(params, body) =>
        // Always wrap the whole lambda in parens. A bare "(params) => body"
        // inside an Expr/InfixCall/Apply context is ambiguous — the parser sees
        // "(params)" as an argument list and "=> body" as a stray arrow.
        "((" ~ params.join(", ") ~ ") =>" ~ indent(body) ~ ")"

      case Fence(phrase) =>
        "(" ~ phrase ~ ")"

      case With(expr, args) =>
        "with " ~ args.join(", ") ~ " in" ~ indent(expr)

      case Allow(expr, params) =>
        val paramText =
          if params.isEmpty then Text("none")
          else params.join(", ")

        "allow " ~ paramText ~ " in" ~ indent(expr)

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
        val elseText =
          if elsep.isEmptyBlock then Text.Empty
          else Text("else") ~ indent(elsep)

        "if " ~ cond ~ " then" ~ indent(thenp) ~ elseText

      case While(cond, body) =>
        "while " ~ cond ~ " do" ~ indent:
            body

      case Return(value) =>
        value match
          case Some(expr) => "return " ~ expr
          case None => Text("return")

      case Break(_) =>
        Text("break")

      case Continue(_) =>
        Text("continue")

      case For(pattern, iter, condOpt, body) =>
        val condPart = condOpt.map(c => " if " ~ c).getOrElse(Text.Empty)
        "for " ~ showPattern(pattern) ~ " in " ~ iter ~ condPart ~ " do" ~ indent:
            body

      case Assign(lhs, rhs) =>
        lhs ~ " = " ~ rhs

      case Match(scrutinee, cases) =>
        "match " ~ scrutinee ~ indent:
          cases.join(Text.BlankLine)

      case PatValDef(pat, rhs) =>
        "val " ~ showPattern(pat) ~ " = " ~ rhs

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

      case RegexPattern(binder, regex) =>
        binder match
          case Some(id) => id ~ " @ " ~ showWord(regex)
          case None => showWord(regex)

      case SequencePattern(items) =>
        "[" ~ items.map(showSequenceItem).join(", ") ~ "]"

      case GuardPattern(pattern, guard) =>
        showPattern(pattern) ~ " if " ~ showWord(guard)

      case AssignPattern(pattern, assignments) =>
        val assigns = assignments.map { case (id, value) => id.name ~ " = " ~ showWord(value) }.join(", ")
        showPattern(pattern) ~ " then " ~ assigns

      case ExprPattern(patterns) =>
        patterns.map(showPattern).join(" ")

  def showSequenceItem(item: SequenceItem): Text =
    item match
      case AtomItem(pattern) =>
        showPattern(pattern)

      case RepeatPattern(name, guard) =>
        val nameText = name match
          case None => Text("..")
          case Some(id) => Text("..") ~ id

        guard match
          case None => nameText
          case Some(g) => nameText ~ Text(" while (") ~ showPattern(g) ~ ")"

  def showModifier(mod: Modifier): Text =
    Text(mod.show)

  def showType(tpt: TypeTree): Text =
    tpt match
      case _: EmptyTypeTree => Text.Empty

      case ref: RefTree => showWord(ref)

      case UnionType(branches) =>
        // A function-type branch must be parenthesized: `=>` binds looser than
        // `|`, so `Simple | Int => Unit` would parse as `(Simple | Int) => Unit`.
        def branchText(b: TypeTree): Text = b match
          case _: FunctionType => "(" ~ showType(b) ~ ")"
          case _               => showType(b)

        "(" ~ branches.map(branchText).join(" | ") ~ ")"

      case ExprType(types) =>
        types.join(" ")

      case AppliedType(Ident(".."), targ :: Nil) =>
        // Vararg type: surface syntax is the prefix `..T`, not `..[T]`.
        ".." ~ targ

      case AppliedType(tpeCtor, targs) =>
        tpeCtor ~ "[" ~ targs.join(", ") ~ "]"

      case FunctionType(paramTypes, resultType, receives) =>
        val params = paramTypes match
          case one :: Nil => showType(one)
          case many       => "(" ~ many.join(", ") ~ ")"

        val tp = params ~ " => " ~ resultType

        if receives.isEmpty then
          tp
        else
          tp ~ " receives " ~ receives.join(", ")

      case DuckType(tpe, adapters) =>
        tpe ~ " :- [" ~ adapters.join(", ") ~ "]"

      case ExtensionType(base, methods) =>
        val methodStrs = methods.map:
          case (ref, isOverride) => if isOverride then ref ~ "!" else ref ~ ""
          case ref: RefTree => ref ~ ""
        base ~ " :+ [" ~ methodStrs.join(", ") ~ "]"

      case AnnotType(tpe, annot) =>
        tpe ~ " " ~ showAnnotation(annot)
