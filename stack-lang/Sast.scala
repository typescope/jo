/***********************************************************************
 *
 * Semantic Abstract Syntax Trees
 *
 * All names are resolved to symbols according to scoping rules.
 *
 ***********************************************************************/

import scala.collection.mutable

import Sast.Symbol.*

object Sast:
  abstract class Symbol:
    val name: String
    def isPrim: Boolean = this.isInstanceOf[PrimSymbol]
    def isFun: Boolean = this.isInstanceOf[FunSymbol]
    def isVal: Boolean = this.isInstanceOf[ValSymbol]

    override def toString() = name

  object Symbol:
    class PrimSymbol(val name: String, val info: FunInfo) extends Symbol
    class FunSymbol(val name: String, val info: FunInfo) extends Symbol
    class ValSymbol(val name: String) extends Symbol

  case class FunInfo(paramCount: Byte, resCount: Byte)

  enum Word:
    case IntLit(value: Int)
    case BoolLit(value: Boolean)
    case Ident(symbol: Symbol)
    case IfStat(cond: List[Word], thenp: List[Word], elsep: List[Word])

  enum Def:
    val symbol: Symbol
    def name: String = symbol.name

    case FunDef(symbol: FunSymbol, params: List[Symbol], words: List[Word])
    case ValDef(symbol: Symbol, words: List[Word])

  case class Prog(defs: List[Def], main: List[Word])

  object predef:
    private val symbols: mutable.ArrayBuffer[Symbol] = new mutable.ArrayBuffer

    private def createPredefSymbol(name: String, paramCount: Byte, resCount: Byte): Symbol =
      val sym = new Symbol.PrimSymbol(name, FunInfo(paramCount, resCount))
      symbols += sym
      sym

    val add    =  createPredefSymbol("+",   2, 1)
    val sub    =  createPredefSymbol("-",   2, 1)
    val mul    =  createPredefSymbol("*",   2, 1)
    val div    =  createPredefSymbol("/",   2, 1)
    val mod    =  createPredefSymbol("%",   2, 1)
    val gt     =  createPredefSymbol(">",   2, 1)
    val lt     =  createPredefSymbol("<",   2, 1)
    val ge     =  createPredefSymbol(">=",  2, 1)
    val le     =  createPredefSymbol("<=",  2, 1)
    val srl    =  createPredefSymbol(">>",  2, 1)
    val sll    =  createPredefSymbol("<<",  2, 1)
    val land   =  createPredefSymbol("&",   2, 1)
    val lor    =  createPredefSymbol("|",   2, 1)
    val lxor   =  createPredefSymbol("^",   2, 1)
    val band   =  createPredefSymbol("and", 2, 1)
    val bor    =  createPredefSymbol("or",  2, 1)
    val bnot   =  createPredefSymbol("not", 1, 1)
    val eql    =  createPredefSymbol("==",  2, 1)
    val p      =  createPredefSymbol("p",   1, 0)

    val allSymbols: List[Symbol] = symbols.toList
  end predef
