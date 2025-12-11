package sast

import Trees.*
import Types.*
import Symbols.*

import common.StringUtil
import common.Text
import common.Text.*

object Printing:

  def show(word: Word)(using Definitions): String =
    showWord(word).toString

  def show(pattern: Pattern)(using Definitions): String =
    showPattern(pattern).toString

  def show(pattern: SeqPartPattern)(using Definitions): String =
    showSeqPartPattern(pattern).toString

  def show(ns: Namespace)(using Definitions): String =
    showNamespace(ns).toString

  def show(tp: Type)(using Definitions): String =
    showType(tp).toString

  def print(nss: List[Namespace])(using Definitions): Unit =
    for ns <- nss do
      println(show(ns))
      println

  //----------------------------------------------------------------------------

  given (using Definitions): Text.Maker[Word] =
    v => showWord(v)

  given (using Definitions): Text.Maker[Pattern] =
    v => showPattern(v)

  given (using Definitions): Text.Maker[SeqPartPattern] =
    v => showSeqPartPattern(v)

  given (using Definitions): Text.Maker[Case] =
    v => "case " ~ v.pattern ~ " =>" ~ indent(v.body)

  given (using Definitions): Text.Maker[Def] =
    v => showDef(v)

  given (using Definitions): Text.Maker[ValDef | FunDef] =
    v => showDef(v)

  given (using Definitions): Text.Maker[TypeTree] =
    v => Text(v.tpe.show)

  given (using Definitions): Text.Maker[Symbol] =
    v => Text(v.toString)

  given (using Definitions): Text.Maker[Type] =
    v => showType(v)

  //----------------------------------------------------------------------------

  // implementation

  def showNamespace(ns: Namespace)(using Definitions): Text =
    "namespace "  ~ ns.symbol ~ Text.BlankLine ~
    showImports(ns.imports) ~ Text.BlankLine ~
    ns.defs.join(Text.BlankLine)

  def showImports(imports: List[Symbol])(using Definitions): Text =
    imports match
      case item :: items =>
        "import " ~ item.fullName ~ Text.BreakLine ~ showImports(items)

      case Nil =>
        Text.Empty

  def showDef(defn: Def)(using Definitions): Text =
    defn match
      case ValDef(sym, rhs) =>
        val modifiers = showModifiers(sym)
        val kind = if sym.isMutable then "var" else "val"
        modifiers ~ kind ~ " " ~ sym.name ~ ": " ~ sym.info ~ " = " ~ rhs

      case fdef: FunDef =>
        val tparamText =
          if fdef.tparams.isEmpty then Text.Empty
          else "[" ~ fdef.tparams.join(", ") ~ "]"

        def showParamAdapter(adapter: ParamAdapter): Text =
          adapter match
            case ParamAdapter.Function(sym) => Text(sym.name)
            case ParamAdapter.Member(name) => "." ~ name

        def showParamWithAdapters(param: Symbol, adapters: List[ParamAdapter]): Text =
          val adapterText =
            if adapters.isEmpty then Text.Empty
            else " with [" ~ adapters.map(showParamAdapter).join(", ") ~ "]"
          param.name ~ ": " ~ param.info ~ adapterText

        val paramText =
          "(" ~ fdef.params.zip(fdef.adapters.padTo(fdef.params.size, Nil)).map(showParamWithAdapters).join(", ") ~ ")"

        val autoText =
          if fdef.autos.isEmpty then Text.Empty
          else "(auto " ~ fdef.autos.map(sym => sym.name ~ ": " ~ sym.info).join(", ")  ~ ")"

        val resType = fdef.resultType

        val locals = fdef.locals.map(sym => sym ~ ": " ~ sym.info).join(", ")

        val modifiers = showModifiers(fdef.symbol)

        val receives =
          fdef.effectPolicy.bound match
            case Some(Nil) =>
              Text(" receives none ")

            case Some(params) =>
              " receives " ~ params.join(", ") ~ " "

            case _ =>
              Text.Empty

        "@locals(" ~ locals ~ ")" ~ Text.BreakLine ~
        modifiers ~ "def " ~ fdef.name ~ tparamText ~ paramText ~ autoText ~ ": " ~ resType ~ receives ~ " =" ~ indent:
          fdef.body

      case pdef: PatDef =>
        val tparams =
          if pdef.tparams.isEmpty then Text.Empty
          else "[" ~ pdef.tparams.join(", ")  ~ "]"

        val params =
          if pdef.params.isEmpty then Text.Empty
          else "(" ~ pdef.params.join(", ")  ~ ")"

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

      case cdef: ClassDef =>
        val modifiers = showModifiers(cdef.symbol)

        val tparams =
          if cdef.tparams.isEmpty then Text.Empty
          else "[" ~ cdef.tparams.join(", ")  ~ "]"

        modifiers ~ "class " ~ cdef.name ~ tparams ~ indent:
          cdef.vals.map(showField).join(Text.BlankLine)
          ~ Text.BlankLine
          ~ cdef.funs.join(Text.BlankLine)

      case idef: InterfaceDef =>
        val modifiers = showModifiers(idef.symbol)

        val tparams =
          if idef.tparams.isEmpty then Text.Empty
          else "[" ~ idef.tparams.join(", ")  ~ "]"

        modifiers ~ "interface " ~ idef.name ~ tparams ~ indent:
          idef.methods.join(Text.BlankLine)

      case adef: AliasDef =>
        val modifiers = showModifiers(adef.symbol)
        modifiers ~ "alias " ~ adef.name ~ " = " ~ adef.target

      case Section(sym, defs) =>
        "section " ~ sym ~ indent:
            defs.join(Text.BlankLine)

  def showField(sym: Symbol)(using Definitions): Text =
    val modifiers = showModifiers(sym)
    if sym.isMutable then modifiers ~ " var " ~ sym.name ~ ": " ~ sym.info
    else modifiers ~ " val " ~ sym.name ~ ": " ~ sym.info

  def showWord(word: Word)(using defn: Definitions): Text =
    word match
      case Literal(c) =>
        c match
          case Constant.Bool(b) => Text(b.toString)

          case Constant.String(s) =>
            "\"" ~ StringUtil.escape(s) ~ "\""

          case Constant.Int(n) =>
            val isChar = word.tpe.isSubtype(defn.CharType)
            if isChar then
              "'" ~ StringUtil.escapeChar(n.toChar) ~ "'"
            else
              Text(n.toString)

      case Ident(sym) => Text(sym)

      case Select(qual, name) =>
        qual ~ "." ~ name

      case RecordLit(fields) =>
        "{" ~ indent:
              fields.map { (f, rhs) => f ~ " = " ~ rhs }.join(", ")
        ~ "}"

      case Encoded(repr) =>
        "(" ~ repr ~ ": " ~ word.tpe ~ ")"

      case Apply(Select(New(classType), _), args, autos) =>
        val autoText =
          if autos.isEmpty then Text.Empty
          else "(" ~ "auto " ~ autos.join(", ") ~ ")"

        "new " ~ classType ~ "(" ~ args.join(", ") ~ ")" ~ autoText

      case Apply(fun, args, autos) =>
        val autoText =
          if autos.isEmpty then Text.Empty
          else Text.BreakLine ~ "auto" ~ indent:
            autos.join(Text.BreakLine)

        fun ~ indent:
          args.join(Text.BreakLine) ~ autoText

      case New(classType) =>
        "new " ~ classType

      case TypeApply(fun, targs) =>
        fun ~ "[" ~ targs.join(", ") ~ "]"

      case With(expr, args) =>
        val withText = " with " ~ indent:
          args.join(Text.BreakLine)

        "(" ~ expr ~ withText ~ ")"

      case Allow(expr, params) =>
        val paramText =
          if params.isEmpty then Text("none")
          else params.join(", ")

        "(" ~ expr ~ " allow " ~ paramText ~ ")"

      case Assign(id, rhs) =>
        id.symbol ~ " = " ~ indent(rhs)

      case FieldAssign(Select(qual, name), rhs) =>
        qual ~ "." ~ name ~ " <- " ~ rhs

      case If(cond, thenp, elsep) =>
        "if" ~ indent:
            cond
        ~ "then" ~ indent:
            thenp
        ~ "else" ~ indent:
           elsep

      case While(cond, body) =>
        "while" ~ indent:
            cond
        ~ "do" ~ indent:
            body

      case Match(scrutinee, cases) =>
        "match " ~ scrutinee ~ indent:
          cases.join(Text.BlankLine)

      case Block(words) =>
        if words.size == 1 then
          showWord(words.head)
        else if words.size > 1 then
          Text.BreakLine ~ words.join(Text.BreakLine)
        else
          Text.Empty

      case Object(self, members) =>
        "{" ~ indent:
           members.join(Text.BreakLine)
        ~ "}"

      case vdef: ValDef => showDef(vdef)

      case fdef: FunDef => showDef(fdef)

      case pdef: PatDef => showDef(pdef)

      case tdef: TypeDef => showDef(tdef)

  def showPattern(pat: Pattern)(using Definitions): Text =
    pat match
      case TypePattern(tpe) => ": " ~ tpe

      case WildcardPattern() => Text("_")

      case AliasPattern(id, inner) =>
        "(" ~ id ~ " @ " ~ inner ~ ")"

      case ApplyPattern(pred, nested) =>
        pred ~ "(" ~ nested.join(", ") ~ ")"

      case OrPattern(lhs, rhs) =>
        lhs ~ " | " ~ rhs

      case ValuePattern(value) =>
        showWord(value)

      case GuardPattern(pattern, guard) =>
        pattern ~ " if " ~ guard

      case BindPattern(pattern, bindings) =>
        pattern ~ " then " ~ bindings.join(", ")

      case SeqPattern(patterns) =>
        "[" ~ patterns.join(", ") ~ "]"

  def showSeqPartPattern(pat: SeqPartPattern)(using Definitions): Text =
    pat match
      case AtomPattern(pattern) => showPattern(pattern)

      case SkipToPattern(pattern) => ">" ~ pattern

      case StarPattern(pattern) => pattern ~ "*"

      case RestPattern(pattern) => ".." ~ pattern

  def showModifiers(sym: Symbol)(using Definitions): Text =
    val visibility = sym.visibility match
      case Visibility.Default => ""
      case Visibility.Private(sym) => "private[" + sym.name + "] "

    val mask = Flags.Synthetic | Flags.Context | Flags.Default | Flags.Alias | Flags.Defer | Flags.View
    visibility ~ Flags.flagStrings(sym.flags & mask).map("<" + _ + ">").join(" ")

  def showType(tp: Type)(using Definitions): Text =
    tp match
      case VoidType    => Text("void")
      case AnyType     => Text("Any")
      case BottomType  => Text("Bottom")
      case ErrorType   => Text("Error")

      case tvar: TypeVar =>
        if tvar.isInstantiated then
          Text(tvar.instantiated)
        else
          Text(tvar.toString)

      case ConstantType(const) =>
        const match
          case Constant.Bool(value)   => Text(value.toString)
          case Constant.Int(value)    => Text(value.toString)
          case Constant.String(value) => "\"" ~ StringUtil.escape(value) ~ "\""

      case StaticRef(sym) =>
        if sym.isType then Text(sym.name)
        else sym.name ~ ": " ~ sym.info

      case ref @ MemberRef(prefix, sym) =>
        if sym.isType then Text(sym.name)
        else sym.name ~ ": " ~ ref.info

      case RecordType(fields) =>
        val members = fields.map(f => f.name ~ ": " ~ f.info)
        "{ " ~ members.join(", ") ~ " }"

      case ObjectType(members, muts) =>
        val memberList = members.map: n =>
          val NamedInfo(name, info) = n
          if info.isProcType then
            "def " ~ name ~ info.widenTermRef
          else
            val mod = if muts.contains(name) then "var " else " val "
            mod ~ name ~ ": " ~ info

        "{ " ~ indent:
            memberList.join(Text.BreakLine)
        ~ " }"

      case UnionType(branches) =>
        branches.join(" | ")

      case AppliedType(tctor, targs) =>
        tctor ~ "[" ~ targs.join(Text(", ")) ~ "]"

      case TypeLambda(tparams, body, _) =>
        "[" ~ tparams.join(", ") ~ "]" ~ " => " ~ body

      case TypeBound(lo, hi) =>
        lo ~ " .. " ~ hi

      case DuckType(baseType, adapters) =>
        val adapterTexts = adapters.map:
          case ParamAdapter.Function(sym) => Text(sym.name)
          case ParamAdapter.Member(name) => "." ~ name
        "like " ~ baseType ~ " with [" ~ adapterTexts.join(", ") ~ "]"

      case procType @ ProcType(tparams, params, autos, candidates, resType, _, n) =>
        val tparamText =
          if tparams.isEmpty then
            Text.Empty

          else
            "[" ~ tparams.join(Text(", ")) ~ "]"

        def showParam(param: NamedInfo[Type]): Text =
          param.name ~ ": " ~ param.info

        val preText =
          if n > 0 then
            "(" ~ params.take(n).map(showParam).join(", ") ~ ")"
          else
            Text.Empty

        val postText =
          "(" ~ params.drop(n).map(showParam).join(", ") ~ ")"

        val autoText =
          if autos.isEmpty then Text.Empty
          else "(" ~ autos.map(param => param.name ~ ": " ~ param.info).join(", ") ~ ")"

        val receivesText =
          if procType.receives.isEmpty then Text(" receives none")
          else " receives " ~ procType.receives.join(", ")

        tparamText ~ preText ~ postText ~ autoText ~ ": " ~ resType ~ receivesText

      case info: ContainerInfo => Text("Container { ... }")

      case info: ClassInfo => info.classSymbol ~ "{ ... }"

  end showType
