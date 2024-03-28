/***********************************************************************
 *
 * Semantic Abstract Syntax Trees
 *
 * All names are resolved to symbols according to scoping rules.
 *
 ***********************************************************************/

import scala.collection.mutable

import Sast.Flag.*

object Sast:

  final class Symbol(
    val name: String,
    val info: StackInfo,
    val flags: Flags):

    def isPrimitive: Boolean = flags.is(Flag.Prim)
    def isFunction : Boolean = flags.is(Flag.Fun)
    def isValue    : Boolean = flags.is(Flag.Val)
    def isParameter: Boolean = flags.is(Flag.Param)

    override def toString() = name

  object Symbol:
    private val valueInfo = new StackInfo(0, 1)
    def createValueSymbol(name: String) =
      new Symbol(name, valueInfo, Flag.Val)

    def createFunSymbol(name: String, info: StackInfo) =
      new Symbol(name, info, Flag.Fun)

    def createParamSymbol(name: String) =
      new Symbol(name, valueInfo, Flag.Param | Flag.Val)

  object Flag:
    opaque type Flag <: Flags = Long
    opaque type Flags = Long

    val Prim  : Flag = 1
    val Fun   : Flag = 1 << 1
    val Val   : Flag = 1 << 2
    val Param : Flag = 1 << 3

    val empty : Flags = 0

    extension (fs: Flags)
      def is(flag: Flag) = (fs & flag) > 0

      def isOneOf(flag: Flag, flags: Flag*) =
        (fs & flag) > 0 || flags.exists(flag => (flag & fs) > 0)

      def isAllOf(flag: Flag, flags: Flag*) =
        (fs & flag) > 0 && flags.forall(flag => (flag & fs) > 0)

      def |(fs2: Flags): Flags = fs | fs2

  case class StackInfo(paramCount: Byte, resCount: Byte)

  enum Word:
    case IntLit(value: Int)
    case BoolLit(value: Boolean)
    case Ident(symbol: Symbol)
    case If(cond: List[Word], thenp: List[Word], elsep: List[Word])
    lazy val info: StackInfo =
      this match
        case _: IntLit | _: BoolLit => StackInfo(0, 1)
        case ident: Ident => ident.symbol.info
        case If(_, thenp, _) =>
          // It's already checked that cond is StackInfo(0, 1) and the two
          // branches have the same stack info
          val resCount = thenp.foldLeft(0): (acc, word) =>
            val added = word.info.resCount - word.info.paramCount
            acc + added
          StackInfo(0, resCount.toByte)

  enum Def:
    val symbol: Symbol
    def name: String = symbol.name

    case FunDef(symbol: Symbol, params: List[Symbol], words: List[Word])
    case ValDef(symbol: Symbol, words: List[Word])

  case class Prog(defs: List[Def], main: List[Word])

  object predef:
    private val symbols: mutable.ArrayBuffer[Symbol] = new mutable.ArrayBuffer

    private def createPredefSymbol(name: String, paramCount: Byte, resCount: Byte): Symbol =
      val sym = new Symbol(name, StackInfo(paramCount, resCount), Flag.Prim)
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
