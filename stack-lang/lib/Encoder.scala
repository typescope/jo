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
  *           Refs [stk.Predef.+, stk.Predef.assert],
  *
  *           Symbol [
  *              4, List, Flags [NSpace], NoOwner,
  *              SourcePos [List.stk, 23, 43],
  *              NameTable [...]
  *           ],
  *
  *           Imports [
  *             Symbol [...],
  *             Symbol [...],
  *             ...
  *           ],
  *
  *           Defs [
  *             FunDef [
  *               InternalRef [...],
  *               TypeParams [...],
  *               Params [...],
  *               Autos [...],
  *               Receives [...],
  *               Locals [...],
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
    private val internalSymIds = new mutable.Map[Symbol, Int]

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
  end State

  //----------------------------------------------------------------------------
  //
  // Implicits to cut down boilerplate in encoding
  //
  // We do not want too many implicits here --- it is better to be explicit
  // except for the most common and obvious usage.

  given (using Definitions): Text.Maker[Word] =
    v => encodeWord(v)

  given (using Definitions): Text.Maker[Pattern] =
    v => encodePattern(v)

  given (using Definitions): Text.Maker[Type] =
    v => encodeType(v)

  //----------------------------------------------------------------------------

  def encode(ns: Namespace)(using Definitions): Text =
    val Namespace(symbol, imports, defs) = ns

    given state: State = new State(symbol)

    val symbolData = encodeSymbol(symbol)

    val importsData = "Imports [" ~ imports.map(encodeSymbol).join(", ") ~ "]"
    val defsData = "Defs [" ~ indent:
        defs.map(writeDef).join(", ")
      ~ "]"

    // must comes after imports and defs
    val refsData = "Refs [" ~ indent:
        externalSymbols.map(_.fullName).join(", ")
      ~ "]"

    "Namespace [" ~ indent:
        List(refsData, symbolData, importsData, defsData).join("," ~ Text.BreakLine)
    "]"

  //----------------------------------------------------------------------------

  /** Definition of a symbol */
  private def encodeSymbol(symbol: Symbol)(using Definitions, State): Text =
    // TODO: attributes, comments

    val id = getInternalSymbolId(symbol)

    val ownerText =
      if symbol.owner == null then "NoOwner" else encodeSymbolRef(symbol.owner)

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
            encodeSymbolInfo(sym)
        ~ "]"

      case _ =>
        // Symbol [id, name, flags, owner ref, source pos, info]
        "Symbol [" ~ indent:
            id ~ ", " ~
            symbol.name ~ ", " ~
            encodeFlags(symbol.flags) ~ ", " ~
            ownerText ~ ", " ~
            encodePosition(tsym.sourcePos) ~ ", " ~
            encodeSymbolInfo(sym)
        ~ "]"


  /** Reference to a symbol
    *
    *     InternalRef [3]
    *
    *     ExternalRef [5]
    */
  def encodeSymbolRef(symbol: Symbol)(using defn: Definitions, state: State): Text =
    if symbol.containedIn(state.root) then
      "InternalRef [" ~ state.getInternalSymbolId(symbol) ~ "]"

    else
      assert(!symbol.isLocal, "Cannot reference external local symbol: " + symbol)
      "ExternalRef [" ~ state.getExternalSymbolIndex(symbol) ~ "]"

  def encodeSymbolInfo(symbol: Symbol)(using defn: Definitions, state: State): Text =
    symbol.info match
      case procType: ProcType =>
        // TODO: add effects
        encodeType(procType)

      case info =>
        encodeType(info)

  def encodeFlags(flags: Flags)(using Definitions, State): Text =
    flags.toStrings.join(", ")

  def encodeKind(kind: Kind)(using Definitions, State): Text =
    kind match
      case Kind.Simple =>
        Text("*")

      case Kind.Arrow(args, to) =>
        // right-associative
        "[" ~ args.map(encodeKind).join(", ") ~ "] -> " ~ encodeKind(to)


  def encodeDef(defn: Def)(using Definitions, State): Text = ???

  def encodeType(tpe: Type)(using Definitions, State): Text =
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

      case nt: NameTableInfo => ???

      case RecordType(fields) =>
        "RecordType [" ~ indent:
            fields.map(f => f.name ~ ": " ~ f.info).join(", " ~ Text.BreakLine)
        ~ "]"

      case UnionType(branches) =>
        "UnionType [" ~ branches.map(encodeType).join(

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

      case ProcType(tparams, params, autos, resType, receivesOpt, preParamCount) =>
        val params2 =
          for param <- params
          yield param.copy(info = this(param.info))

        val autos2 =
          for auto <- autos
          yield auto.copy(info = this(auto.info))

        val resType2 = this(resType)
        ProcType(tparams, params2, autos2, resType2, receivesOpt, preParamCount)

      case TypeLambda(tparams, resType, preParamCount) =>
        val tparamText = "[" ~ tparams.map(encodeSymbolRef).join(", ") ~ "]"
        "TypeLambda [" ~ tparamText ~ ", " ~ resType ~ ", " ~ preParamCount ~ "]"

      case classInfo: ClassInfo =>
        ???

      case TypeBound(lo, hi) =>
        "TypeBound [" ~ lo ~ ", " ~ hi ~ "]"

  def encodeConstant(const: Constant): Text =
    const match
      case Constant.Bool(value) =>
        "Bool [" ~ value.toString ~ "]"

      case Constant.Int(value) =>
        "Int [" ~ value.toString ~ "]"

      case Constant.String(value) =>
        val byteSize = StringUtil.utf8Length(value)
        "String [" ~ byteSize.toString ~ ":"  ~ value ~ "]"

  def encodePosition(pos: SourcePos)(using Definitions, State): Text =
    "SourcePosition [" ~ indent:
        pos.source.file ~ "," ~
        "Start [" ~ pos.startLine ~ ", " ~ pos.startLineColumn ~ "]," ~
        "End [" ~ pos.endLine ~ ", " ~ pos.endLineColumn ~ "]," ~
    ~ "]"
