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
    /** Name reference to externally defined symbols */
    private val externalSymbols = new mutable.ArrayBuffer[Symbol]

    /** Map a symbol to a unique ID
      *
      * The mapping is defined for all internally defined symbols (top-level and
      * local).
      */
    private val internalSymIds = mutable.Map.empty[Symbol, Int]

    private var internalSymbolCount = 0

    def getExternalSymbolIndex(sym: Symbol): Int =
      val index = externalSymbols.indexOf(sym)
      if index < 0 then
        val index = externalSymbols.size
        externalSymbols += sym
        index

      else
        index

    def getInternalSymbolId(sym: Symbol): Int =
      internalSymIds.get(sym) match
        case Some(id) => id
        case None =>
          val id = internalSymbolCount
          internalSymIds(sym) = id
          internalSymbolCount += 1
          id
      end match

    def externalNameTable(using Definitions): Text =
      // TODO: store type for checking contracts
      "[" ~ externalSymbols.toSeq.map(_.fullName).join(LINE_SEP) ~ "]"
  end State

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

  private given (using Definitions, State, Source): Text.Maker[Symbol] =
    v => printSymbolRef(v)

  private given Text.Maker[SourcePosition] =
    v => printPosition(v)

  private given Text.Maker[Flags] =
    v => printFlags(v)

  //----------------------------------------------------------------------------

  def print(ns: Namespace)(using Definitions): Text =
    val Namespace(symbol, imports, defs) = ns

    given state: State = new State(symbol)
    given src: Source = symbol.sourcePos.source

    val defsData = "[" ~ indent:
        defs.map(printDef).join(",")
      ~ "]"

    val source = printSource(symbol.sourcePos.source)

    // must comes after symbols
    val refsData = state.externalNameTable

    "[" ~ indent:
        List(source, refsData, defsData).join("," ~ Text.BreakLine)
    ~ "]"

  //----------------------------------------------------------------------------

  /** Definition of a symbol */
  private def printSymbol(symbol: Symbol)(using defn: Definitions, state: State, src: Source): Text =
    val id = state.getInternalSymbolId(symbol)

    // TODO: attributes, comments
    if symbol.isLocal then
      return symbol.name ~ "#" ~ id ~ symbol.flags

    val ownerText =
      if symbol.owner == null then Text("null") else Text(symbol.owner)

    val pos = symbol.sourcePos

    symbol match
      case tsym: TypeSymbol =>
        "[" ~ id ~ "," ~ tsym.name ~ "," ~ symbol.flags ~ "," ~ printKind(tsym.kind) ~ "," ~ ownerText ~ "," ~ printType(tsym.info) ~ "]@" ~ pos

      case _ =>
        "[" ~ id ~ "," ~ symbol.name ~ "," ~ symbol.flags ~ "," ~ ownerText ~ "," ~ printType(symbol.info) ~ "]@" ~ pos

  /** Reference to a symbol
    *
    *     #3  ==> refers the symbol whose id is 3
    *
    *     @5  ==> refers the name table entry whose index is 5
    */
  private def printSymbolRef(symbol: Symbol)(using defn: Definitions, state: State): Text =
    if symbol.containedIn(state.root) then
      symbol.name ~ "#" ~ state.getInternalSymbolId(symbol)

    else
      assert(!symbol.isLocal, "Cannot reference external local symbol: " + symbol)
      symbol.name ~ "@" ~ state.getExternalSymbolIndex(symbol)

  private def printFlags(flags: Flags): Text =
    "[" ~ Flags.flagStrings(flags).join(Text.Empty) ~ "]"

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

      case fdef: FunDef =>
        // TODO: store local symbol definitions locally?
        "FunDef [" ~ indent:
            printSymbol(fdef.symbol) ~ LINE_SEP ~
            "[" ~ fdef.tparams.map(printSymbol).join(",") ~ "]" ~ LINE_SEP ~
            "[" ~ fdef.params.map(printSymbol).join(",") ~ "]" ~ LINE_SEP ~
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

      case sec: Section =>
        "Section [" ~ indent:
            printSymbol(sec.symbol) ~ LINE_SEP ~
            "[" ~ indent:
                sec.defs.map(printDef).join(LINE_SEP)
            ~ "]"
        ~ "]"

    res ~ "@" ~ defn.pos

  private def printTypeTree(tpt: TypeTree)(using defn: Definitions, state: State, src: Source): Text =
    "[" ~ tpt.tpe ~ "]@" ~ tpt.pos

  private def printType(tpe: Type)(using Definitions, State, Source): Text =
    tpe match
      case VoidType => Text("VoidType")
      case ErrorType => Text("ErrorType")
      case AnyType => Text("AnyType")
      case BottomType => Text("BottomType")
      case StaticRef(sym) =>
        printSymbolRef(sym)

      case MemberRef(prefix, sym) =>
        "MemberRef [" ~ prefix ~ "," ~ sym ~ "]"

      case tvar: TypeVar =>
        assert(tvar.isInstantiated, "uninstantiated type variable: " + tvar)
        printType(tvar.instantiated)

      case ConstantType(const) =>
        "ConstantType [" ~ printConstant(const) ~ "]"

      case RecordType(fields) =>
        "RecordType [" ~ indent:
          fields.map(f => f.name ~ ": " ~ f.info).join("," ~ Text.BreakLine)
        ~ "]"

      case UnionType(branches) =>
        "UnionType [" ~ branches.map(printType).join(",") ~ "]"

      case TagType(tag, params) =>
        val paramText =  params.map(f => f.name ~ ": " ~ f.info).join(",")
        "TagType [" ~ tag ~ "," ~ paramText ~ "]"

      case ObjectType(fields, methods, muts) =>
        val fieldText = "[" ~ fields.map(f => f.name ~ ": " ~ f.info).join(",") ~ "]"
        val methodText = "[" ~ methods.map(m => m.name ~ ": " ~ m.info).join(",") ~ "]"
        val mutableText = "[" ~ muts.join(",") ~ "]"

        "ObjectType [" ~ fieldText ~ "," ~ methodText ~ "," ~ mutableText ~ "]"

      case AppliedType(tctor, targs) =>
        "AppliedType [" ~ tctor ~ ",[" ~ targs.join(",") ~ "]]"

      case procType @ ProcType(tparams, params, autos, resType, _, preParamCount) =>
        val tparamText = "[" ~ tparams.join(",") ~ "]"
        val paramText = "[" ~ params.map(param => "[" ~ param.name ~ "," ~ param.info ~ "]").join(",") ~ "]"
        val autoText = "[" ~ autos.map(auto => "[" ~ auto.name ~ "," ~ auto.info ~ "]").join(",") ~ "]"
        val receiveText = "[" ~ procType.receives.join(",") ~ "]"

        "ProcType [" ~ indent:
          List(tparamText, paramText, autoText, printType(resType), receiveText, Text(preParamCount)).join("," ~ Text.BreakLine)
        ~ "]"

      case TypeLambda(tparams, resType, preParamCount) =>
        val tparamText = "[" ~ tparams.join(",") ~ "]"
        "TypeLambda [" ~ tparamText ~ "," ~ resType ~ "," ~ preParamCount ~ "]"

      case cinfo: ContainerInfo =>
        "ContainerInfo [" ~ cinfo.members.join(",") ~ "]"

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
    // TODO: types
    val res = word match
      case Literal(const) =>
        "Literal [" ~ printConstant(const) ~ "]"

      case Ident(sym) =>
        "Ident [" ~ sym ~ "]"

      case New(classRef, targs) =>
        "New [" ~ classRef ~ ",[" ~ targs.join(",") ~ "]]"

      case Select(qual, name) =>
        "Select [" ~ qual ~ "," ~ name ~ "]"

      case RecordLit(fields) =>
        val content = fields.map:
          case (f, rhs) => "[" ~ f ~ "," ~ rhs ~ "]"

        "RecordLit [" ~ content.join(",") ~ "]"

      case TaggedLit(tag, args) =>
        "TaggedLit [" ~ tag ~ ", [" ~ args.join(",") ~ "]]"

      case Encoded(repr) =>
        "Encoded [" ~ repr ~ "," ~ word.tpe ~ "]"

      case Apply(fun, args, autos) =>
        "Apply [" ~ indent:
          fun ~ LINE_SEP ~
          "[" ~ args.join(",") ~ "]" ~ LINE_SEP ~
          "[" ~ autos.join(",") ~ "]"
        ~ "]"

      case TypeApply(fun, targs) =>
        "TypeApply [" ~ indent:
          fun ~ LINE_SEP ~
          "[" ~ targs.join(",") ~ "]"
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
          elsep
        ~ "]"

      case While(cond, body) =>
        "While [" ~ indent:
          cond ~ LINE_SEP ~
          body
        ~ "]"

      case Block(words) =>
        "Block [" ~ indent:
          words.join(LINE_SEP)
        ~ "]"

      case Match(scrutinee, cases) =>
        "Match [" ~ scrutinee ~ ", [" ~ indent:
           val pairs = cases.map:
             case Case(pat, body) => "[" ~ pat ~ "," ~ body ~ "]"

           pairs.join(LINE_SEP)
        ~ "]]"

      case Object(self, inits, defs) =>
        "Object [" ~ indent:
            printSymbol(self) ~ LINE_SEP ~
            "[" ~ inits.join(",") ~ "]" ~ LINE_SEP ~
            "[" ~ indent:
                defs.map(printDef).join(LINE_SEP)
            ~ "]"
        ~ "]"

    res ~ "@" ~ word.pos

  private def printPattern(pattern: Pattern)(using defn: Definitions, state: State, src: Source): Text =
    val res = pattern match
      case AliasPattern(id, nested) =>
        "AliasPattern [" ~ id ~ "," ~ nested ~ "]"

      case TypePattern(tpt) =>
        "TypePattern [" ~ tpt ~ "]"

      case TagPattern(tagLit, nested) =>
        "TagPattern [" ~ tagLit ~ ",[" ~ nested.join(",") ~ "]]"

      case ApplyPattern(fun, nested) =>
        "ApplyPattern [" ~ fun ~ ",[" ~ nested.join(",") ~ "]]"

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

    res ~ "@" ~ pattern.pos

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

  private def printPosition(pos: SourcePosition): Text =
    pos.startLine ~ "," ~ pos.startLineColumn
