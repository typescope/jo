package lib

import sast.*
import sast.Sast.*

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
class Encoder(using Definitions):
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

  def getInternalSymbolId(sym: Symbol): Int =
    internalSymIds.get(sym) match
      case Some(id) => id
      case None =>
        val id = internalSymbolCount
        internalSymIds(sym) = id
        internalSymbolCount += 1
        id

  def write(ns: Namespace): Text =
    val Namespace(symbol, imports, defs) = ns

    val symbolData = defineSymbol(symbol)

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
  def defineSymbol(symbol: Symbol): Text =
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


  def encodeFlags(flags: Flags): Text = ???

  def encodeKind(kind: Kind): Text = ???

  /** Reference to a symbol
    *
    *     InternalRef [3]
    *
    *     ExternalRef [5]
    */
  def refSymbol(symbol: Symbol): Text = ???

  def encodeDef(defn: Def): Text = ???

  def encodeType(tpe: Type): Text = ???

  def encodePosition(tpe: Type): Text = ???
