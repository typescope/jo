/***********************************************************************
 *
 * Semantic Abstract Syntax Trees
 *
 * All names are resolved to symbols according to scoping rules.
 *
 ***********************************************************************/

import scala.collection.mutable

object Sast:
  enum Symbol:
    val name: String

    def isPrimitive: Boolean = this.isInstanceOf[PrimSymbol]
    def isFun: Boolean = this.isInstanceOf[FunSymbol]
    def isVal: Boolean = this.isInstanceOf[ValSymbol]

    private[Sast] case PrimSymbol(name: String)
    private[Sast] case FunSymbol(name: String)
    private[Sast] case ValSymbol(name: String)

  enum Word:
    case IntLit(value: Int)
    case BoolLit(value: Boolean)
    case Ident(symbol: Symbol)
    case Proc(words: List[Word])

  enum Def:
    val symbol: Symbol
    def name: String = symbol.name

    case FunDef(symbol: Symbol, words: List[Word])
    case ValDef(symbol: Symbol, words: List[Word])

  case class Prog(defs: List[Def], main: List[Word])

  def createFunSymbol(name: String): Symbol = new Symbol.FunSymbol(name)
  def createValSymbol(name: String): Symbol = new Symbol.ValSymbol(name)

  object predef:
    private val symbols: mutable.ArrayBuffer[Symbol] = new mutable.ArrayBuffer

    private def createPredefSymbol(name: String): Symbol =
      val sym = new Symbol.PrimSymbol(name)
      symbols += sym
      sym

    val add    =  createPredefSymbol("+")
    val sub    =  createPredefSymbol("-")
    val mul    =  createPredefSymbol("*")
    val div    =  createPredefSymbol("/")
    val mod    =  createPredefSymbol("%")
    val gt     =  createPredefSymbol(">")
    val lt     =  createPredefSymbol("<")
    val ge     =  createPredefSymbol(">=")
    val le     =  createPredefSymbol("<=")
    val srl    =  createPredefSymbol(">>")
    val sll    =  createPredefSymbol("<<")
    val land   =  createPredefSymbol("&")
    val lor    =  createPredefSymbol("|")
    val lxor   =  createPredefSymbol("^")
    val band   =  createPredefSymbol("and")
    val bor    =  createPredefSymbol("or")
    val bnot   =  createPredefSymbol("not")
    val run    =  createPredefSymbol("!")
    val eql    =  createPredefSymbol("==")
    val dup    =  createPredefSymbol("dup")
    val swap   =  createPredefSymbol("swap")
    val peek   =  createPredefSymbol("peek")
    val pop    =  createPredefSymbol("pop")
    val choose =  createPredefSymbol("choose")
    val p      =  createPredefSymbol("p")

    val allSymbols: List[Symbol] = symbols.toList
  end predef
