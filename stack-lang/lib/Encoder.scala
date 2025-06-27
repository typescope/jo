package lib

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
  *             Symbol [...],
  *             Symbol [...],
  *             ...
  *           ],
  *
  *           defs [
  *             FunDef [
  *               InternalRef [...],
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

    def externalNameTable(using Definitions): List[String] = externalSymbols.map(_.fullName).toList
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
    val refsData = "refs [" ~ indent:
        state.externalNameTable.join(", ")
      ~ "]"

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
    *     InternalRef [3]
    *
    *     ExternalRef [5]
    */
  private def encodeSymbolRef(symbol: Symbol)(using defn: Definitions, state: State): Text =
    if symbol.containedIn(state.root) then
      "InternalRef [" ~ state.getInternalSymbolId(symbol) ~ "]"

    else
      assert(!symbol.isLocal, "Cannot reference external local symbol: " + symbol)
      "ExternalRef [" ~ state.getExternalSymbolIndex(symbol) ~ "]"

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


  private def encodeDef(defn: Def)(using Definitions, State): Text = ???

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
        assert(receives.isInstanceOf[Effects.Policy.CheckBound], "Expect Policy.CheckBounds, found = " + receives)

        val effects = receives.asInstanceOf[Effects.Policy.CheckBound].effects

        val tparamText = "tparams [" ~ tparams.map(encodeSymbolRef).join(", ") ~ "]"
        val paramText = "params [" ~ params.map(param => "[" ~ param.name ~ ", " ~ param.info ~ "]").join(", ") ~ "]"
        val autoText = "autos [" ~ autos.map(auto => "[" ~ auto.name ~ ", " ~ auto.info ~ "]").join(", ") ~ "]"
        val receiveText = "receives [" ~ effects.map(eff => encodeSymbolRef(eff)).join(", ") ~ "]"

        "ProcType [" ~ indent:
            List(tparamText, paramText, autoText, encodeType(resType), receiveText, Text(preParamCount)).join(Text.BreakLine ~ ",")
        ~ "]"

      case TypeLambda(tparams, resType, preParamCount) =>
        val tparamText = "[" ~ tparams.map(encodeSymbolRef).join(", ") ~ "]"
        "TypeLambda [" ~ tparamText ~ ", " ~ resType ~ ", " ~ preParamCount ~ "]"

      case cinfo: ContainerInfo =>
        ???

      case classInfo: ClassInfo =>
        ???

      case TypeBound(lo, hi) =>
        "TypeBound [" ~ lo ~ ", " ~ hi ~ "]"

  private def encodeWord(word: Word)(using Definitions, State): Text = ???

  private def encodePattern(pat: Pattern)(using Definitions, State): Text = ???

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
