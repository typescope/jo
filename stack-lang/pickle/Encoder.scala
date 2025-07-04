package pickle

import ast.Positions.*

import sast.*
import sast.Sast.*
import sast.Types.*
import sast.Symbols.*

import common.Text
import common.Text.*
import common.StringUtil

import scala.collection.mutable

/** Encode trees, symbols and types
  *
  *
  * Internal symbols (local/top-level symbols) are uniquely identified by IDs
  * between definition site and usage site. The IDs are only valid within the
  * namespace during encoding and decoding.
  *
  * External symbols are uniquely identified by the full path to the symbols.
  *
  * A sample encoding looks like the following:
  *
  *       Namespace [
  *           refs [stk.Predef.+, stk.Predef.assert],
  *
  *           Symbol [
  *              4, List, [NSpace], NoOwner,
  *              SourcePos [List.stk, 23, 43],
  *              NameTable [...]
  *           ],
  *
  *           imports [
  *             NameRef [...],
  *             NameRef [...],
  *             ...
  *           ],
  *
  *           defs [
  *             FunDef [
  *               SymRef [...],
  *               tparams [...],
  *               params [...],
  *               autos [...],
  *               receives [...],
  *               locals [...],
  *               If [
  *                   Apply [...],
  *                   Block [...],
  *                   Block [...],
  *                   StaticRef [...],
  *                   Span [...]
  *               ],
  *             ],
  *             Section [...]
  *           ]
  *       ]
  */
object Encoder:
  val LINE_SEP = ", " ~ Text.BreakLine

  private class State(val root: Symbol):
    /** Name reference to externally defined symbols */
    private val externalSymbols = new mutable.ArrayBuffer[Symbol]

    /** Map a symbol to a unique ID
      *
      * The mapping is defined for all internally defined symbols (top-level and
      * local).
      *
      * The unique ID is only valid within the scope of the namespace for
      * writing/reading.
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
      "refs [" ~ externalSymbols.toSeq.map(_.fullName).join(LINE_SEP) ~ "]"
  end State

  //----------------------------------------------------------------------------
  //
  // Implicits to cut down boilerplate in encoding
  //
  // We do not want too many implicits here --- it is better to be explicit
  // except for the most common and obvious usage.

  private given (using Definitions, State): Text.Maker[Word] =
    v => encodeWord(v)

  private given (using Definitions, State): Text.Maker[Pattern] =
    v => encodePattern(v)

  private given (using Definitions, State): Text.Maker[Type] =
    v => encodeType(v)

  private given (using Definitions, State): Text.Maker[TypeTree] =
    v => encodeTypeTree(v)

  //----------------------------------------------------------------------------

  def encode(ns: Namespace)(using Definitions): Text =
    val Namespace(symbol, imports, defs) = ns

    given state: State = new State(symbol)

    val symbolData = encodeSymbol(symbol)

    val importsData = "imports [" ~ imports.map(encodeSymbol).join(", ") ~ "]"
    val defsData = "defs [" ~ indent:
        defs.map(encodeDef).join(", ")
      ~ "]"

    // must comes after imports and defs
    val refsData = state.externalNameTable

    "Namespace [" ~ indent:
        List(refsData, symbolData, importsData, defsData).join("," ~ Text.BreakLine)
    ~ "]"

  //----------------------------------------------------------------------------

  /** Definition of a symbol */
  private def encodeSymbol(symbol: Symbol)(using defn: Definitions, state: State): Text =
    // TODO: attributes, comments

    val id = state.getInternalSymbolId(symbol)

    val ownerText =
      if symbol.owner == null then Text("NoOwner") else encodeSymbolRef(symbol.owner)

    symbol match
      case tsym: TypeSymbol =>
        // TypeSymbol [id, name, flags, kind, owner ref, source pos, info]
        "TypeSymbol [" ~ indent:
            id ~ ", " ~
            tsym.name ~ ", " ~
            encodeFlags(tsym.flags) ~ ", " ~
            encodeKind(tsym.kind) ~ ", " ~
            ownerText ~ ", " ~
            encodePosition(tsym.sourcePos) ~ ", " ~
            encodeSymbolInfo(tsym)
        ~ "]"

      case _ =>
        // Symbol [id, name, flags, owner ref, source pos, info]
        "Symbol [" ~ indent:
            id ~ ", " ~
            symbol.name ~ ", " ~
            encodeFlags(symbol.flags) ~ ", " ~
            ownerText ~ ", " ~
            encodePosition(symbol.sourcePos) ~ ", " ~
            encodeSymbolInfo(symbol)
        ~ "]"


  /** Reference to a symbol
    *
    *     SymRef [3]
    *
    *     NameRef [5]
    */
  private def encodeSymbolRef(symbol: Symbol)(using defn: Definitions, state: State): Text =
    if symbol.containedIn(state.root) then
      "NameRef [" ~ state.getInternalSymbolId(symbol) ~ "]"

    else
      assert(!symbol.isLocal, "Cannot reference external local symbol: " + symbol)
      "SymRef [" ~ state.getExternalSymbolIndex(symbol) ~ "]"

  private def encodeSymbolInfo(symbol: Symbol)(using defn: Definitions, state: State): Text =
    symbol.info match
      case procType: ProcType =>
        // TODO: add effects
        encodeType(procType)

      case info =>
        encodeType(info)

  private def encodeFlags(flags: Flags)(using Definitions, State): Text =
    flags.toStrings.join(", ")

  private def encodeKind(kind: Kind)(using Definitions, State): Text =
    kind match
      case Kind.Simple =>
        Text("*")

      case Kind.Arrow(args, to) =>
        // right-associative
        "[" ~ args.map(encodeKind).join(", ") ~ "] -> " ~ encodeKind(to)


  private def encodeDef(defn: Def)(using Definitions, State): Text =
    // TODO: span
    defn match
      case pdef: ParamDef =>
        "ParamDef [" ~ encodeSymbolRef(pdef.symbol) ~ ", " ~ pdef.tpt ~ "]"

      case cdef: ClassDef =>
        // TODO: where to encode method symbol?
        "ClassDef [" ~ indent:
            encodeSymbolRef(cdef.symbol) ~ LINE_SEP ~
            encodeSymbol(cdef.self) ~ LINE_SEP ~
            "[" ~ indent:
                cdef.tparams.map(encodeSymbol).join(LINE_SEP)
            "]" ~ LINE_SEP ~
            "[" ~ indent:
                cdef.vals.map(encodeSymbol).join(LINE_SEP)
            "]" ~ LINE_SEP ~
            "[" ~ indent:
                cdef.funs.map(encodeDef).join(LINE_SEP)
            "]"
        ~ "]"

      case fdef: FunDef =>
        // TODO: local symbol definitions
        "FunDef [" ~ indent:
            encodeSymbolRef(fdef.symbol) ~ LINE_SEP ~
            "[" ~ indent:
                fdef.tparams.map(encodeSymbol).join(LINE_SEP)
            "]" ~ LINE_SEP ~
            "[" ~ indent:
                fdef.params.map(encodeSymbol).join(LINE_SEP)
            "]" ~ LINE_SEP ~
            "[" ~ indent:
                fdef.autos.map(encodeSymbol).join(LINE_SEP)
            "]" ~ LINE_SEP ~
            fdef.resultType ~ LINE_SEP ~
            fdef.body
        ~ "]"


      case pdef: PatDef =>
        "PatDef [" ~ indent:
            encodeSymbolRef(pdef.symbol) ~ LINE_SEP ~
            "[" ~ indent:
                pdef.tparams.map(encodeSymbol).join(LINE_SEP)
            "]" ~ LINE_SEP ~
            "[" ~ indent:
                pdef.params.map(encodeSymbol).join(LINE_SEP)
            "]" ~ LINE_SEP ~
            pdef.resultType ~ LINE_SEP ~
            pdef.body
        ~ "]"

      case tdef: TypeDef =>
        "TypeDef [" ~ encodeSymbol(tdef.symbol) ~ "]"

      case sec: Section =>
        "Section [" ~ indent:
            encodeSymbolRef(sec.symbol) ~ LINE_SEP ~
            "[" ~ indent:
                sec.defs.map(encodeDef).join(LINE_SEP)
            ~ "]"
        ~ "]"

  private def encodeTypeTree(tpt: TypeTree)(using Definitions, State): Text =
    // TODO: span
    "TypeTree [" ~ tpt.tpe ~ "]"


  private def encodeType(tpe: Type)(using Definitions, State): Text =
    tpe match
      case VoidType => Text("Void")

      case ErrorType => Text("Error")

      case AnyType => Text("Any")

      case BottomType => Text("Bottom")

      case StaticRef(sym) =>
        "StaticRef [" ~ encodeSymbolRef(sym) ~ "]"

      case MemberRef(prefix, sym) =>
        "MemberRef [" ~ prefix ~ ", " ~ encodeSymbolRef(sym) ~ "]"

      case tvar: TypeVar =>
        assert(tvar.isInstantiated, "uninstantiated type variable: " + tvar)
        encodeType(tvar.instantiated)

      case ConstantType(const) =>
        "ConstantType [" ~ encodeConstant(const) ~ "]"

      case RecordType(fields) =>
        "RecordType [" ~ indent:
            fields.map(f => f.name ~ ": " ~ f.info).join(", " ~ Text.BreakLine)
        ~ "]"

      case UnionType(branches) =>
        "UnionType [" ~ branches.map(encodeType).join(", ") ~ "]"

      case TagType(tag, params) =>
        val paramText =  params.map(f => f.name ~ ": " ~ f.info).join(", ")
        "TagType [" ~ tag ~ ", " ~ paramText ~ "]"

      case ObjectType(fields, methods, muts) =>
        val fieldText = "[" ~ fields.map(f => f.name ~ ": " ~ f.info).join(", ") ~ "]"
        val methodText = "[" ~ methods.map(m => m.name ~ ": " ~ m.info).join(", ") ~ "]"
        val mutableText = "[" ~ muts.join(", ") ~ "]"

        "ObjectType [" ~ fieldText ~ ", " ~ methodText ~ ", " ~ mutableText ~ "]"

      case AppliedType(tctor, targs) =>
        "AppliedType [" ~ tctor ~ ", [" ~ targs.join(", ") ~ "]]"

      case ProcType(tparams, params, autos, resType, receives, preParamCount) =>
        // assert(receives.isInstanceOf[Effects.Policy.CheckBound], "Expect Policy.CheckBounds, found = " + receives)

        val effects: List[Symbol] = Nil // receives.asInstanceOf[Effects.Policy.CheckBound].effects

        val tparamText = "tparams [" ~ tparams.map(encodeSymbolRef).join(", ") ~ "]"
        val paramText = "params [" ~ params.map(param => "[" ~ param.name ~ ", " ~ param.info ~ "]").join(", ") ~ "]"
        val autoText = "autos [" ~ autos.map(auto => "[" ~ auto.name ~ ", " ~ auto.info ~ "]").join(", ") ~ "]"
        val receiveText = "receives [" ~ effects.map(eff => encodeSymbolRef(eff)).join(", ") ~ "]"

        "ProcType [" ~ indent:
            List(tparamText, paramText, autoText, encodeType(resType), receiveText, Text(preParamCount)).join("," ~ Text.BreakLine)
        ~ "]"

      case TypeLambda(tparams, resType, preParamCount) =>
        val tparamText = "[" ~ tparams.map(encodeSymbolRef).join(", ") ~ "]"
        "TypeLambda [" ~ tparamText ~ ", " ~ resType ~ ", " ~ preParamCount ~ "]"

      case cinfo: ContainerInfo =>
        "Container [" ~ cinfo.members.map(encodeSymbol).join("," ~ Text.BreakLine) ~ "]"

      case ClassInfo(classSymbol, tparams, targs, self, fields, methods) =>
        targs.zip(tparams).map: (targ, tparam) =>
          targ match
            case StaticRef(sym) => assert(sym == tparam, "Unexpected class info")
            case tp => throw new Exception("Unexpected targ for classInfo: " + tp)

        "ClassInfo [" ~ indent:
            encodeSymbolRef(classSymbol) ~ "," ~
            "[" ~ tparams.map(encodeSymbolRef).join(", ") ~ "]," ~
            encodeSymbolRef(self) ~ "," ~
            "[" ~ fields.map(encodeSymbolRef).join(", ") ~ "]," ~
            "[" ~ methods.map(encodeSymbolRef).join(", ") ~ "],"
        ~ "]"


      case TypeBound(lo, hi) =>
        "TypeBound [" ~ lo ~ ", " ~ hi ~ "]"

  private def encodeWord(word: Word)(using Definitions, State): Text =
    // TODO: span and types
    word match
      case Literal(const) =>
        "Lit [" ~ encodeConstant(const) ~ "]"

      case Ident(sym) =>
        "Ident [" ~ encodeSymbolRef(sym) ~ "]"

      case New(classRef, targs) =>
        "New [" ~ classRef ~ ", [" ~ targs.join(", ") ~ "]]"

      case Select(qual, name) =>
        "Select [" ~ qual ~ ", " ~ name ~ "]"

      case RecordLit(fields) =>
        val content = fields.map:
          case (f, rhs) => "[" ~ f ~ ", " ~ rhs ~ "]"

        "Record [" ~ content.join(", ") ~ "]"

      case TaggedLit(tag, args) =>
        "Tag [" ~ tag ~ ", [" ~ args.join(", ") ~ "]]"

      case Encoded(repr) =>
        "Encoded [" ~ repr ~ ", " ~ word.tpe ~ "]"

      case Apply(fun, args, autos) =>
        "Apply [" ~ indent:
          fun ~ LINE_SEP ~
          "[" ~ args.join(", ") ~ "]" ~ LINE_SEP ~
          "[" ~ autos.join(", ") ~ "]"
        ~ "]"

      case TypeApply(fun, targs) =>
        "TypeApply [" ~ indent:
          fun ~ LINE_SEP ~
          "[" ~ targs.join(", ") ~ "]"
        ~ "]"

      case With(expr, args) =>
        val bindings = args.map:
          case Assign(ident, rhs) =>
            "[" ~ ident ~ ", " ~ rhs ~ "]"

        "With [" ~ expr ~ ", [" ~ indent:
           bindings.join(LINE_SEP)
        ~ "]]"

      case Allow(expr, params) =>
        "Allow [" ~ expr ~ ", [" ~ params.join(", ") ~ "]]"

      case Assign(ident, rhs) =>
        "Assign [" ~ ident ~ ", " ~ rhs ~ "]"

      case FieldAssign(lhs, rhs) =>
        "FieldAssign [" ~ lhs ~ ", " ~ rhs ~ "]"

      case fdef: FunDef => encodeDef(fdef)

      case tdef: TypeDef => encodeDef(tdef)

      case pdef: PatDef => encodeDef(pdef)

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
        val pairs = cases.map:
          case Case(pat, body) => "[" ~ pat ~ ", " ~ body ~ "]"

        "Match [" ~ scrutinee ~ ", [" ~ indent:
           pairs.join(LINE_SEP)
        ~ "]]"

      case Object(self, inits, defs) =>
        // TODO: symbols for vals and defs
        "Object [" ~ indent:
            encodeSymbol(self) ~ LINE_SEP ~
            "[" ~ indent:
                inits.join(LINE_SEP)
            ~ "]" ~ LINE_SEP ~
            "[" ~ indent:
                defs.map(encodeDef).join(LINE_SEP)
            ~ "]"
        ~ "]"


  private def encodePattern(pattern: Pattern)(using Definitions, State): Text =
    // TODO: span
    pattern match
      case AliasPattern(id, nested) =>
        // TODO: bound symbol
        "AliasPattern [" ~ id ~ ", " ~ nested ~ "]"

      case TypePattern(tpt) =>
        "TypePattern [" ~ tpt ~ "]"

      case TagPattern(tagLit, nested) =>
        "TagPattern [" ~ tagLit ~ ", [" ~ nested.join(", ") ~ "]]"

      case ApplyPattern(fun, nested) =>
        "ApplyPattern [" ~ fun ~ ", [" ~ nested.join(", ") ~ "]]"

      case OrPattern(lhs, rhs) =>
        "OrPattern [" ~ lhs ~ ", " ~ rhs ~ "]"

      case ValuePattern(value) =>
        "ValuePattern [" ~ value ~ "]"

      case GuardPattern(pattern, guard) =>
        "GuardPattern [" ~ pattern ~ ", " ~ guard ~ "]"

      case BindPattern(pattern, bindings) =>
        "BindPattern [" ~ pattern ~ ", [" ~ indent:
          bindings.join(LINE_SEP)
        ~ "]"

      case SeqPattern(pats) =>
        val nested =
          pats.map:
            case AtomPattern(pattern) => "AtomPattern [" ~ pattern ~ "]"

            case SkipToPattern(pattern) => "SkipToPattern [" ~ pattern ~ "]"

            case StarPattern(pattern) =>
              // TODO: bindings
              "StarPattern [" ~ pattern ~ "]"

            case RestPattern(pattern) => "RestPattern [" ~ pattern ~ "]"

        "SeqPattern [" ~ nested.join(", ") ~ "]"

      case WildcardPattern() =>
        Text("Wildcard")

  private def encodeConstant(const: Constant): Text =
    const match
      case Constant.Bool(value) =>
        "Bool [" ~ value.toString ~ "]"

      case Constant.Int(value) =>
        "Int [" ~ value.toString ~ "]"

      case Constant.String(value) =>
        val byteSize = StringUtil.utf8Length(value)
        "String [" ~ byteSize.toString ~ ":"  ~ value ~ "]"

  private def encodePosition(pos: SourcePosition)(using Definitions, State): Text =
    "SourcePosition [" ~ indent:
        pos.source.file ~ "," ~
        "Start [" ~ pos.startLine ~ ", " ~ pos.startLineColumn ~ "]," ~
        "End [" ~ pos.endLine ~ ", " ~ pos.endLineColumn ~ "],"
    ~ "]"
