/***********************************************************************
 *
 * Semantic Abstract Syntax Trees
 *
 * All names are resolved to symbols according to scoping rules.
 *
 ***********************************************************************/

import scala.collection.mutable

object Sast:
  class Symbol private[Sast](val name: String):
    override def toString() = name

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

  def createSymbol(name: String): Symbol = new Symbol(name)

  object predefs:
    private val symbols: mutable.ArrayBuffer[Symbol] = new mutable.ArrayBuffer

    private def createPredefSymbol(name: String): Symbol =
      val sym = new Symbol(name)
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
  end predefs
