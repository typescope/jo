import Types.*
import Reporter.SourcePosition

import scala.collection.mutable

/** Symbols refer to definitions (names) in the program.
  *
  * Symbols are stable in the compilation process, while the types of a symbol
  * might change, e.g., due to erasure or encoding of types.
  */
object Symbols:
  /** Provides type to a symbol
    *
    * The provider needs to perform cache. The symbol does not cache.
    */
  abstract class InfoProvider extends (Symbol => Type)

  final class Symbol(
    val name: String,
    infoProvider: Type | InfoProvider,
    flags: Flags,
    val sourcePos: SourcePosition):

    /** Do not cache the result from provider
      *
      * The result may change due to
      *
      * - fixed point computation
      * - type inference
      * - erasure
      *
      * The cache is done by the provider
      */
    def info: Type =
      infoProvider match
        case tp: Type => tp
        case provider: InfoProvider => provider(this)

    def isPrimitive: Boolean = flags.is(Flag.Prim)
    def isFunction : Boolean = flags.is(Flag.Fun)
    def isValue    : Boolean = flags.is(Flag.Val)
    def isParameter: Boolean = flags.is(Flag.Param)
    def isLocal    : Boolean = flags.is(Flag.Local)
    def isMutable  : Boolean = flags.is(Flag.Mutable)
    def isType     : Boolean = flags.is(Flag.Type)

    override def toString() = name

  object Symbol:
    def createValueSymbol(name: String, tp: Type | InfoProvider, pos: SourcePosition) =
      new Symbol(name, tp, Flag.Val, pos)

    def createValueSymbol(name: String, tp: Type | InfoProvider, flags: Flags, pos: SourcePosition) =
      new Symbol(name, tp, Flag.Val | flags, pos)

    def createFunSymbol(name: String, info: Type | InfoProvider, pos: SourcePosition) =
      new Symbol(name, info, Flag.Fun, pos)

    def createTypeSymbol(name: String, info: Type, pos: SourcePosition) =
      new Symbol(name, info, Flag.Type, pos)

    def createParamSymbol(name: String, tp: Type, pos: SourcePosition) =
      new Symbol(name, tp, Flag.Param | Flag.Val | Flag.Local, pos)

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
    val Type    : Flag = 1 << 6

    val empty : Flags = 0

    extension (fs: Flags)
      def is(flag: Flag) = (fs & flag) > 0

      def isOneOf(flag: Flag, flags: Flag*) =
        (fs & flag) > 0 || flags.exists(flag => (flag & fs) > 0)

      def isAllOf(flag: Flag, flags: Flag*) =
        (fs & flag) > 0 && flags.forall(flag => (flag & fs) > 0)

      def |(fs2: Flags): Flags = fs | fs2

  object predef:
    private val symbols: mutable.ArrayBuffer[Symbol] = new mutable.ArrayBuffer

    private def createPrimSymbol(name: String, tp: Type): Symbol =
      val sym = new Symbol(name, tp, Flag.Prim, sourcePos = null)
      symbols += sym
      sym

    private val oneBoolType = BoolType :: Nil
    private val oneIntType = IntType :: Nil
    private val twoIntTypes = IntType :: IntType :: Nil
    private val twoBoolTypes = BoolType :: BoolType :: Nil

    private val typeArith = ProcType("m" :: "n" :: Nil, twoIntTypes, IntType)
    private val typeComp = ProcType("m" :: "n" :: Nil, twoIntTypes, BoolType)
    private val typeBits = ProcType("m" :: "n" :: Nil, twoIntTypes, IntType)

    private val typeAnd = ProcType("a" :: "b" :: Nil, twoBoolTypes, BoolType)
    private val typeOr  = ProcType("a" :: "b" :: Nil, twoBoolTypes, BoolType)
    private val typeNot  = ProcType("a" :: Nil, oneBoolType, BoolType)

    private val typePrint  = ProcType("n" :: Nil, oneIntType, VoidType)

    val add    =  createPrimSymbol("+",   typeArith)
    val sub    =  createPrimSymbol("-",   typeArith)
    val mul    =  createPrimSymbol("*",   typeArith)
    val div    =  createPrimSymbol("/",   typeArith)
    val mod    =  createPrimSymbol("%",   typeArith)
    val gt     =  createPrimSymbol(">",   typeComp)
    val lt     =  createPrimSymbol("<",   typeComp)
    val ge     =  createPrimSymbol(">=",  typeComp)
    val le     =  createPrimSymbol("<=",  typeComp)
    val eql    =  createPrimSymbol("==",  typeComp)
    val srl    =  createPrimSymbol(">>",  typeBits)
    val sll    =  createPrimSymbol("<<",  typeBits)
    val land   =  createPrimSymbol("&",   typeBits)
    val lor    =  createPrimSymbol("|",   typeBits)
    val lxor   =  createPrimSymbol("^",   typeBits)
    val band   =  createPrimSymbol("and", typeAnd)
    val bor    =  createPrimSymbol("or",  typeOr)
    val bnot   =  createPrimSymbol("not", typeNot)
    val p      =  createPrimSymbol("p",   typePrint)

    val Int    =  new Symbol("Int",  IntType,  Flag.Prim | Flag.Type, sourcePos = null)
    val Bool   =  new Symbol("Bool", BoolType, Flag.Prim | Flag.Type, sourcePos = null)
    val Void   =  new Symbol("Void", VoidType, Flag.Prim | Flag.Type, sourcePos = null)

    val allSymbols: List[Symbol] = symbols.toList
  end predef

  object runtime:
    private val abortType = ProcType("n" :: Nil, IntType :: Nil, BottomType)
    val abort = new Symbol("abort", abortType, Flag.Prim, sourcePos = null)
