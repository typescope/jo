package lib

import ast.Positions.*

import sast.*
import sast.Sast.*
import sast.Types.*
import sast.Symbols.*

import common.Text
import common.Text.*

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

  def encode(ns: Namespace)(using Definitions): Text =
    val Namespace(symbol, imports, defs) = ns

    given state: State = new State(symbol)

    val symbolData = encodeSymbol(symbol)

    val importsData = "Imports [" ~ imports.map(refSymbol).join(", ") ~ "]"
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

  /** Definition of a symbol */
  private def encodeSymbol(symbol: Symbol)(using Definitions, State): Text =
    // TODO: attributes, comments

    val id = getInternalSymbolId(symbol)

    val ownerText =
      if symbol.owner == null then "NoOwner" else refSymbol(symbol.owner)

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
            encodeType(sym.info)
        ~ "]"

      case _ =>
        // Symbol [id, name, flags, owner ref, source pos, info]
        "Symbol [" ~ indent:
            id ~ ", " ~
            symbol.name ~ ", " ~
            encodeFlags(symbol.flags) ~ ", " ~
            ownerText ~ ", " ~
            encodePosition(tsym.sourcePos) ~ ", " ~
            encodeType(sym.info)
        ~ "]"


  /** Reference to a symbol
    *
    *     InternalRef [3]
    *
    *     ExternalRef [5]
    */
  def refSymbol(symbol: Symbol)(using defn: Definitions, state: State): Text =
    if symbol.containedIn(state.root) then
      "InternalRef [" ~ state.getInternalSymbolId(symbol) ~ "]"

    else
      "ExternalRef [" ~ state.getExternalSymbolIndex(symbol) ~ "]"

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

  def encodeType(tpe: Type)(using Definitions, State): Text = ???

  def encodePosition(pos: SourcePos)(using Definitions, State): Text =
    "SourcePosition [" ~ indent:
        pos.source.file ~ "," ~
        "Start [" ~ pos.startLine ~ ", " ~ pos.startLineColumn ~ "]," ~
        "End [" ~ pos.endLine ~ ", " ~ pos.endLineColumn ~ "]," ~
    ~ "]"
