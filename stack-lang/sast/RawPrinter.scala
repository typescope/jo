package sast

import ast.Positions.*

import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import common.Text
import common.Text.*

import scala.collection.mutable

/** Print raw sast */
object RawPrinter:
  val LINE_SEP = "," ~ Text.BreakLine

  private class State(val root: Symbol):
    /** Map a symbol to a unique ID
      *
      * The mapping is defined for all internally defined symbols (top-level and
      * local).
      */
    private val internalSymIds = mutable.Map.empty[Symbol, Int]

    private var internalSymbolCount = 0

    def getInternalSymbolId(sym: Symbol): Int =
      internalSymIds.get(sym) match
        case Some(id) => id
        case None =>
          val id = internalSymbolCount
          internalSymIds(sym) = id
          internalSymbolCount += 1
          id
      end match
  end State

  /** De Bruijn encoding of bound type parameters in types */
  private class TypeParamScope:
    private val scope = new mutable.ArrayBuffer[Symbol]

    def withParams[T](params: List[Symbol])(fn: => T): T =
      scope ++= params
      val res = fn
      scope.dropRight(params.size)
      res

    def paramIndex(param: Symbol): Int =
      val size = scope.size

      var i = 0
      var found = false
      while !found && i < size do
        val sym = scope(size - i - 1)
        found = sym == param
        i += 1

      if found then i - 1 else -1

  //----------------------------------------------------------------------------
  //
  // Implicits to cut down boilerplate in encoding
  //
  // We do not want too many implicits here --- it is better to be explicit
  // except for the most common and obvious usage.

  private given (using Definitions, State, Source): Text.Maker[Word] =
    v => printWord(v)

  private given (using Definitions, State, Source): Text.Maker[Pattern] =
    v => printPattern(v)

  private given (using Definitions, State, Source): Text.Maker[Type] =
    v => printType(v)

  private given (using Definitions, State, Source): Text.Maker[TypeTree] =
    v => printTypeTree(v)

  private given (using State): Text.Maker[Symbol] =
    v => printSymbolRef(v)

  private given Text.Maker[Span] =
    v => printSpan(v)

  private given Text.Maker[Flags] =
    v => printFlags(v)

  private given (using State): Text.Maker[Visibility] =
    v => v match
      case Visibility.Default => Text("default")
      case Visibility.Private(within) => "private[" ~ within ~ "]"

  private given (using Definitions, State, Source): Text.Maker[ParamAdapter] =
    v => v match
      case ParamAdapter.Function(symbol) => printSymbolRef(symbol)
      case ParamAdapter.Member(name) => "." ~ name

  //----------------------------------------------------------------------------

  def print(ns: Namespace)(using Definitions): Text =
    val Namespace(symbol, imports, defs) = ns

    given state: State = new State(symbol)
    given src: Source = symbol.sourcePos.source

    val importData = "[" ~ indent:
      imports.map(printSymbol).join("," ~ Text.BreakLine)
    ~ "]"

    val defsData = "[" ~ indent:
        defs.map(printDef).join(LINE_SEP)
      ~ "]"

    val source = printSource(symbol.sourcePos.source)

    "[" ~ indent:
        List(source, importData, defsData).join("," ~ Text.BreakLine)
    ~ "]"

  //----------------------------------------------------------------------------

  /** Definition of a symbol */
  private def printSymbol(symbol: Symbol)(using defn: Definitions, state: State, src: Source): Text =
    assert(symbol.containedIn(state.root), "Non-internal symbol: " + symbol)

    val id = state.getInternalSymbolId(symbol)

    // TODO: attributes, comments

    val ownerText =
      if symbol.owner == null then Text("null") else Text(symbol.owner)

    val span = symbol.span

    symbol match
      case tsym: TypeSymbol =>
        "[" ~ id ~ "," ~ tsym.name ~ "," ~ symbol.flags ~ "," ~ printKind(tsym.kind) ~ "," ~ ownerText ~ "," ~ printType(tsym.info) ~ "," ~ tsym.visibility ~ "]@" ~ span

      case _ =>
        "[" ~ id ~ "," ~ symbol.name ~ "," ~ symbol.flags ~ "," ~ ownerText ~ "," ~ printType(symbol.info) ~ "," ~ symbol.visibility ~ "]@" ~ span

  /** Reference to a symbol
    *
    *     #3  ==> refers the symbol whose id is 3
    *
    *     @5  ==> refers the name table entry whose index is 5
    */
  private def printSymbolRef(symbol: Symbol)(using state: State): Text =
    if symbol.isLocal || symbol.isTypeParameter then
      symbol.name ~ "#" ~ state.getInternalSymbolId(symbol)

    else
      Text(symbol.fullName)

  private def printFlags(flags: Flags): Text =
    "[" ~ Flags.flagStrings(flags &~ Flags.Loaded).join(",") ~ "]"

  private def printKind(kind: Kind): Text =
    kind match
      case Kind.Simple =>
        Text("*")

      case Kind.Arrow(args, to) =>
        // right-associative
        "[" ~ args.map(printKind).join(",") ~ "] -> " ~ printKind(to)


  private def printDef(defn: Def)(using definitions: Definitions, state: State, src: Source): Text =
    val res = defn match
      case pdef: ParamDef =>
        "ParamDef [" ~ printSymbol(pdef.symbol) ~ "," ~ pdef.tpt ~ "]"

      case vdef: ValDef =>
        "ValDef [" ~ printSymbol(vdef.symbol) ~ "," ~ vdef.rhs ~ "]"

      case cdef: ClassDef =>
        "ClassDef [" ~ indent:
            printSymbol(cdef.symbol) ~ LINE_SEP ~
            printSymbol(cdef.self) ~ LINE_SEP ~
            "[" ~ cdef.tparams.map(printSymbol).join(",") ~ "]" ~ LINE_SEP ~
            "[" ~ cdef.vals.map(printSymbol).join(",") ~ "]" ~ LINE_SEP ~
            "[" ~ indent:
                cdef.funs.map(printDef).join(LINE_SEP)
            ~ "]"
        ~ "]"

      case idef: InterfaceDef =>
        "InterfaceDef [" ~ indent:
            printSymbol(idef.symbol) ~ LINE_SEP ~
            printSymbol(idef.self) ~ LINE_SEP ~
            "[" ~ idef.tparams.map(printSymbol).join(",") ~ "]" ~ LINE_SEP ~
            "[" ~ indent:
                idef.methods.map(printDef).join(LINE_SEP)
            ~ "]"
        ~ "]"

      case fdef: FunDef =>
        val adaptersText = "[" ~ indent:
            fdef.adapters.map: adapterList =>
              "[" ~ adapterList.join(",") ~ "]"
            .join(LINE_SEP)
        ~ "]"

        "FunDef [" ~ indent:
            printSymbol(fdef.symbol) ~ LINE_SEP ~
            "[" ~ fdef.tparams.map(printSymbol).join(",") ~ "]" ~ LINE_SEP ~
            "[" ~ fdef.params.map(printSymbol).join(",") ~ "]" ~ LINE_SEP ~
            adaptersText ~ LINE_SEP ~
            "[" ~ fdef.autos.map(printSymbol).join(",") ~ "]" ~ LINE_SEP ~
            fdef.resultType ~ LINE_SEP ~
            fdef.body
        ~ "]"


      case pdef: PatDef =>
        "PatDef [" ~ indent:
            printSymbol(pdef.symbol) ~ LINE_SEP ~
            "[" ~ pdef.tparams.map(printSymbol).join(",") ~ "]" ~ LINE_SEP ~
            "[" ~ pdef.params.map(printSymbol).join(",") ~ "]" ~ LINE_SEP ~
            pdef.resultType ~ LINE_SEP ~
            pdef.body
        ~ "]"

      case tdef: TypeDef =>
        "TypeDef [" ~ printSymbol(tdef.symbol) ~ "]"

      case adef: AliasDef =>
        "AliasDef [" ~ printSymbol(adef.symbol) ~ adef.target ~ "]"

      case sec: Section =>
        "Section [" ~ indent:
            printSymbol(sec.symbol) ~ LINE_SEP ~
            "[" ~ indent:
                sec.defs.map(printDef).join(LINE_SEP)
            ~ "]"
        ~ "]"

    res ~ "@" ~ defn.span

  private def printTypeTree(tpt: TypeTree)(using defn: Definitions, state: State, src: Source): Text =
    "[" ~ tpt.tpe ~ "]@" ~ tpt.span

  private def printType
      (tpe: Type, tparamScope: TypeParamScope = new TypeParamScope)
      (using Definitions, State, Source)
  : Text =

    tpe match
      case VoidType => Text("VoidType")
      case ErrorType => Text("ErrorType")
      case AnyType => Text("AnyType")
      case BottomType => Text("BottomType")

      case StaticRef(sym) =>
        // It can be either TypeParamRef or StaticRef
        val index = tparamScope.paramIndex(sym)
        if index == -1 then
          printSymbolRef(sym)
        else
          "&" ~ index

      case MemberRef(prefix, sym) =>
        "MemberRef [" ~ printType(prefix, tparamScope) ~ "," ~ sym ~ "]"

      case tvar: TypeVar =>
        assert(tvar.isInstantiated, "uninstantiated type variable: " + tvar)
        printType(tvar.instantiated, tparamScope)

      case ConstantType(const) =>
        "ConstantType [" ~ printConstant(const) ~ "]"

      case RecordType(fields) =>
        "RecordType [" ~ indent:
          fields.map(f => f.name ~ ": " ~ printType(f.info, tparamScope)).join("," ~ Text.BreakLine)
        ~ "]"

      case UnionType(branches) =>
        "UnionType [" ~ branches.map(b => printType(b, tparamScope)).join(",") ~ "]"

      case ObjectType(members, muts) =>
        val membersText = "[" ~ members.map(n => n.name ~ ": " ~ printType(n.info, tparamScope)).join(",") ~ "]"
        val mutableText = "[" ~ muts.join(",") ~ "]"

        "ObjectType [" ~ membersText ~ "," ~ mutableText ~ "]"

      case AppliedType(tctor, targs) =>
        "AppliedType [" ~ printSymbolRef(tctor) ~ ",[" ~ targs.map(t => printType(t, tparamScope)).join(",") ~ "]]"

      case procType @ ProcType(tparams, params, adapters, autos, candidates, resType, _, preParamCount) =>
        tparamScope.withParams(tparams):
          val tparamText = "[" ~ indent:
              val items = tparams.map: tparam =>
                "[" ~ tparamScope.paramIndex(tparam) ~ "," ~ tparam.name ~ "," ~ printType(tparam.info, tparamScope)  ~ "]"
              items.join(LINE_SEP)
          ~ "]"

          val paramText = "[" ~ indent:
              val items = params.map: param =>
                "[" ~ param.name ~ "," ~ printType(param.info, tparamScope) ~ "]"
              items.join(LINE_SEP)
          ~ "]"

          val adaptersText = "[" ~ indent:
              val items = adapters.map: adapterList =>
                val printed = adapterList.map:
                  case sym: Symbol => printSymbolRef(sym)
                  case name: String => "." ~ name
                "[" ~ printed.join(",") ~ "]"
              items.join(LINE_SEP)
          ~ "]"

          val autoText = "[" ~ indent:
              val items = autos.map: auto =>
                "[" ~ auto.name ~ "," ~ printType(auto.info, tparamScope) ~ "]"
              items.join(LINE_SEP)
           ~ "]"

          val candidatesText = "[" ~ indent:
              val items = candidates.map: candidateList =>
                val printed = candidateList.map:
                  case sym: Symbol => printSymbolRef(sym)
                  case MemberCandidate(tp, name) => "[" ~ printType(tp, tparamScope) ~ "]." ~ name
                "[" ~ printed.join(",") ~ "]"
              items.join(LINE_SEP)
          ~ "]"

          val receiveText = "[" ~ procType.receives.join(",") ~ "]"

          "ProcType [" ~ indent:
            List(tparamText, paramText, adaptersText, autoText, candidatesText, printType(resType, tparamScope), receiveText, Text(preParamCount)).join("," ~ Text.BreakLine)
          ~ "]"

      case TypeLambda(tparams, resType, preParamCount) =>
        tparamScope.withParams(tparams):
          val tparamText = "[" ~ indent:
              val items = tparams.map: tparam =>
                "[" ~ tparamScope.paramIndex(tparam) ~ "," ~ tparam.name ~ "," ~ printType(tparam.info, tparamScope)  ~ "]"
              items.join(LINE_SEP)
          ~ "]"

          "TypeLambda [" ~ tparamText ~ "," ~ printType(resType, tparamScope) ~ "," ~ preParamCount ~ "]"

      case cinfo: ContainerInfo =>
        "ContainerInfo [" ~ cinfo.nameTable.members.join(",") ~ "]"

      case ClassInfo(classSymbol, tparams, targs, self, fields, methods) =>
        targs.zip(tparams).map: (targ, tparam) =>
          targ match
            case StaticRef(sym) => assert(sym == tparam, "Unexpected class info")
            case tp => throw new Exception("Unexpected targ for classInfo: " + tp)

        "ClassInfo [" ~ indent:
            classSymbol ~ "," ~
            "[" ~ tparams.join(",") ~ "]," ~
            self ~ "," ~
            "[" ~ fields.join(",") ~ "]," ~
            "[" ~ methods.join(",") ~ "],"
        ~ "]"


      case TypeBound(lo, hi) =>
        "TypeBound [" ~ lo ~ "," ~ hi ~ "]"

  private def printWord(word: Word)(using defn: Definitions, state: State, src: Source): Text =
    val res = word match
      case Literal(const) =>
        "Literal [" ~ printConstant(const) ~ "," ~ word.tpe ~ "]"

      case Ident(sym) =>
        "Ident [" ~ sym ~ "]"

      case New(classRef, targs) =>
        "New [" ~ classRef ~ ",[" ~ targs.join(",") ~ "]]"

      case Select(qual, name) =>
        "Select [" ~ qual ~ "," ~ name ~ "]"

      case RecordLit(fields) =>
        val content = fields.map:
          case (f, rhs) => "[" ~ f ~ "," ~ rhs ~ "]"

        "RecordLit [" ~ content.join(",") ~ "," ~ word.tpe ~ "]"

      case Encoded(repr) =>
        "Encoded [" ~ repr ~ "," ~ word.tpe ~ "]"

      case Apply(fun, args, autos) =>
        "Apply [" ~ indent:
          fun ~ LINE_SEP ~
          "[" ~ args.join(",") ~ "]" ~ LINE_SEP ~
          "[" ~ autos.join(",") ~ "]" ~ LINE_SEP ~
          word.tpe
        ~ "]"

      case TypeApply(fun, targs) =>
        "TypeApply [" ~ indent:
          fun ~ LINE_SEP ~
          "[" ~ targs.join(",") ~ "]" ~ LINE_SEP ~
          word.tpe
        ~ "]"

      case With(expr, args) =>
        val bindings = args.map:
          case Assign(ident, rhs) =>
            "[" ~ ident ~ "," ~ rhs ~ "]"

        "With [" ~ expr ~ ",[" ~ indent:
           bindings.join(LINE_SEP)
        ~ "]]"

      case Allow(expr, params) =>
        "Allow [" ~ expr ~ ",[" ~ params.join(",") ~ "]]"

      case Assign(ident, rhs) =>
        "Assign [" ~ ident ~ "," ~ rhs ~ "]"

      case FieldAssign(lhs, rhs) =>
        "FieldAssign [" ~ lhs ~ "," ~ rhs ~ "]"

      case vdef: ValDef => printDef(vdef)

      case fdef: FunDef => printDef(fdef)

      case tdef: TypeDef => printDef(tdef)

      case pdef: PatDef => printDef(pdef)

      case If(cond, thenp, elsep) =>
        "If [" ~ indent:
          cond ~ LINE_SEP ~
          thenp ~ LINE_SEP ~
          elsep ~ LINE_SEP ~
          word.tpe
        ~ "]"

      case While(cond, body) =>
        "While [" ~ indent:
          cond ~ LINE_SEP ~
          body
        ~ "]"

      case Block(words) =>
        "Block [" ~ indent:
          words.join(LINE_SEP) ~ LINE_SEP ~
          word.tpe
        ~ "]"

      case Match(scrutinee, cases) =>
        "Match [" ~ scrutinee ~ ", [" ~ indent:
           val pairs = cases.map:
             case Case(pat, body) => "[" ~ pat ~ "," ~ body ~ "]"

           pairs.join(LINE_SEP) ~ LINE_SEP ~
           word.tpe
        ~ "]]"

      case Object(self, members) =>
        "Object [" ~ indent:
            printSymbol(self) ~ LINE_SEP ~
            "[" ~ members.join(",") ~ "]" ~ LINE_SEP ~
            word.tpe
        ~ "]"

    res ~ "@" ~ word.span

  private def printPattern(pattern: Pattern)(using defn: Definitions, state: State, src: Source): Text =
    val res = pattern match
      case AliasPattern(id, nested) =>
        "AliasPattern [" ~ id ~ "," ~ nested ~ "]"

      case TypePattern(tpt) =>
        "TypePattern [" ~ tpt ~ "]"

      case ApplyPattern(fun, nested) =>
        "ApplyPattern [" ~ indent:
          fun ~ LINE_SEP ~
          "[" ~ indent:
              nested.join(LINE_SEP)
          ~ "]"
        ~ "]"

      case OrPattern(lhs, rhs) =>
        "OrPattern [" ~ lhs ~ "," ~ rhs ~ "]"

      case ValuePattern(value) =>
        "ValuePattern [" ~ value ~ "]"

      case GuardPattern(pattern, guard) =>
        "GuardPattern [" ~ pattern ~ "," ~ guard ~ "]"

      case BindPattern(pattern, bindings) =>
        "BindPattern [" ~ pattern ~ ", [" ~ indent:
          bindings.join(LINE_SEP)
        ~ "]"

      case SeqPattern(pats) =>
        val nested =
          pats.map:
            case AtomPattern(pattern) =>
              "AtomPattern [" ~ pattern ~ "]"

            case SkipToPattern(pattern) =>
              "SkipToPattern [" ~ pattern ~ "]"

            case star @ StarPattern(pattern) =>
              val bindings = star.bindings.map: (sym1, sym2) =>
                "[" ~ sym1 ~ "," ~ sym2 ~ "]"
              "StarPattern [" ~ pattern ~ ", [" ~ bindings.join(",")  ~ "]]"

            case RestPattern(pattern) =>
              "RestPattern [" ~ pattern ~ "]"

        "SeqPattern [" ~ nested.join(",") ~ "]"

      case WildcardPattern() =>
        Text("WildcardPattern")

    res ~ "@" ~ pattern.span

  private def printConstant(const: Constant): Text =
    const match
      case Constant.Bool(value) =>
        "Bool [" ~ value.toString ~ "]"

      case Constant.Int(value) =>
        "Int [" ~ value.toString ~ "]"

      case Constant.String(value) =>
        "String ["  ~ value ~ "]"

  /** Print line lengths as comma-separated hexadecimal */
  private def printSource(source: Source): Text =
    "[" ~ source.file ~ "," ~ source.lineLengths.join("|") ~ "]"

  private def printSpan(span: Span): Text =
    span.start ~ "," ~ span.length
