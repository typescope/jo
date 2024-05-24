import scala.collection.mutable

import Sast.Flag.*
import Reporter.Positioned

/***********************************************************************
 *
 * Semantic Abstract Syntax Trees
 *
 * All names are resolved to symbols according to scoping rules.
 *
 ***********************************************************************/
object Sast:

  final class Symbol(val name: String, val info: StackInfo, val flags: Flags):
    def isPrimitive: Boolean = flags.is(Flag.Prim)
    def isFunction : Boolean = flags.is(Flag.Fun)
    def isValue    : Boolean = flags.is(Flag.Val)
    def isParameter: Boolean = flags.is(Flag.Param)
    def isLocal    : Boolean = flags.is(Flag.Local)
    def isMutable  : Boolean = flags.is(Flag.Mutable)

    override def toString() = name

  object Symbol:
    private val valueInfo = new StackInfo(0, 1)
    def createValueSymbol(name: String) =
      new Symbol(name, valueInfo, Flag.Val)

    def createValueSymbol(name: String, flags: Flags) =
      new Symbol(name, valueInfo, Flag.Val | flags)

    def createFunSymbol(name: String, info: StackInfo) =
      new Symbol(name, info, Flag.Fun)

    def createParamSymbol(name: String) =
      new Symbol(name, valueInfo, Flag.Param | Flag.Val | Flag.Local)

  type Flag  = Flag.Flag
  type Flags = Flag.Flags

  object Flag:
    opaque type Flag <: Flags = Long
    opaque type Flags = Long

    val Prim    : Flag = 1
    val Fun     : Flag = 1 << 1
    val Val     : Flag = 1 << 2
    val Param   : Flag = 1 << 3
    val Local   : Flag = 1 << 4
    val Mutable : Flag = 1 << 5

    val empty : Flags = 0

    extension (fs: Flags)
      def is(flag: Flag) = (fs & flag) > 0

      def isOneOf(flag: Flag, flags: Flag*) =
        (fs & flag) > 0 || flags.exists(flag => (flag & fs) > 0)

      def isAllOf(flag: Flag, flags: Flag*) =
        (fs & flag) > 0 && flags.forall(flag => (flag & fs) > 0)

      def |(fs2: Flags): Flags = fs | fs2

  case class StackInfo(paramCount: Byte, resCount: Byte)

  enum Word extends Positioned:
    case IntLit(value: Int)
    case BoolLit(value: Boolean)
    case Ident(symbol: Symbol)
    case Assign(symbol: Symbol, rhs: List[Word])
    case If(cond: List[Word], thenp: List[Word], elsep: List[Word])

    lazy val info: StackInfo =
      this match
        case _: IntLit | _: BoolLit => StackInfo(0, 1)
        case ident: Ident => ident.symbol.info
        case _: Assign => StackInfo(0, 0)
        case If(_, thenp, _) =>
          // It's already checked that cond is StackInfo(0, 1) and the two
          // branches have the same stack info
          val resCount = thenp.foldLeft(0): (acc, word) =>
            val added = word.info.resCount - word.info.paramCount
            acc + added
          StackInfo(0, resCount.toByte)

  case class Fun(
    symbol: Symbol,
    params: List[Symbol],
    locals: List[Symbol],
    body  : List[Word])
  extends Positioned:
    def name: String = symbol.name

  case class Prog(funs: List[Fun], vals: List[Symbol], main: Symbol):
    Positioned.checkComponentPos(this)

  object predef:
    private val symbols: mutable.ArrayBuffer[Symbol] = new mutable.ArrayBuffer

    private def createPrimSymbol(name: String, paramCount: Byte, resCount: Byte): Symbol =
      val sym = new Symbol(name, StackInfo(paramCount, resCount), Flag.Prim)
      symbols += sym
      sym

    val add    =  createPrimSymbol("+",   2, 1)
    val sub    =  createPrimSymbol("-",   2, 1)
    val mul    =  createPrimSymbol("*",   2, 1)
    val div    =  createPrimSymbol("/",   2, 1)
    val mod    =  createPrimSymbol("%",   2, 1)
    val gt     =  createPrimSymbol(">",   2, 1)
    val lt     =  createPrimSymbol("<",   2, 1)
    val ge     =  createPrimSymbol(">=",  2, 1)
    val le     =  createPrimSymbol("<=",  2, 1)
    val srl    =  createPrimSymbol(">>",  2, 1)
    val sll    =  createPrimSymbol("<<",  2, 1)
    val land   =  createPrimSymbol("&",   2, 1)
    val lor    =  createPrimSymbol("|",   2, 1)
    val lxor   =  createPrimSymbol("^",   2, 1)
    val band   =  createPrimSymbol("and", 2, 1)
    val bor    =  createPrimSymbol("or",  2, 1)
    val bnot   =  createPrimSymbol("not", 1, 1)
    val eql    =  createPrimSymbol("==",  2, 1)
    val p      =  createPrimSymbol("p",   1, 0)

    val allSymbols: List[Symbol] = symbols.toList
  end predef
