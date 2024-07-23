import Types.*
import Flags.*
import Positions.SourcePosition

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
    *
    * An abstract class is used instead of a type alias to enable runtime
    * type test.
    */
  abstract class InfoProvider extends (Symbol => Type)

  final class Symbol(
    val name: String,
    infoProvider: Type | InfoProvider,
    val flags: Flags,
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

    def isPrimitive: Boolean = flags.is(Flags.Prim)
    def isFunction : Boolean = flags.is(Flags.Fun)
    def isValue    : Boolean = flags.is(Flags.Val)
    def isType     : Boolean = flags.is(Flags.Type)
    def isLocal    : Boolean = flags.is(Flags.Local)
    def isParameter: Boolean = flags.isAllOf(Flags.Val | Flags.Param)
    def isMutable  : Boolean = flags.isAllOf(Flags.Val | Flags.Mutable)
    def isAnon     : Boolean = flags.isAllOf(Flags.Fun | Flags.Anon)

    def isOneOf(testFlags: Flags) = this.flags.isOneOf(testFlags)
    def isAllOf(testFlags: Flags) = this.flags.isAllOf(testFlags)

    override def toString() = name

  object Symbol:
    def createValueSymbol(name: String, tp: Type | InfoProvider, pos: SourcePosition) =
      new Symbol(name, tp, Flags.Val, pos)

    def createValueSymbol(name: String, tp: Type | InfoProvider, flags: Flags, pos: SourcePosition) =
      new Symbol(name, tp, Flags.Val | flags, pos)

    def createFunSymbol(name: String, info: Type | InfoProvider, pos: SourcePosition) =
      new Symbol(name, info, Flags.Fun, pos)

    def createFunSymbol(name: String, info: Type | InfoProvider, flags: Flags, pos: SourcePosition) =
      new Symbol(name, info, Flags.Fun | flags, pos)

    def createTypeSymbol(name: String, info: Type | InfoProvider, pos: SourcePosition) =
      new Symbol(name, info, Flags.Type, pos)

    def createParamSymbol(name: String, tp: Type | InfoProvider, pos: SourcePosition) =
      new Symbol(name, tp, Flags.Param | Flags.Val | Flags.Local, pos)

  object predef:
    private val symbols: mutable.ArrayBuffer[Symbol] = new mutable.ArrayBuffer

    private def createPrimSymbol(name: String, tp: Type): Symbol =
      val sym = new Symbol(name, tp, Flags.Prim, sourcePos = null)
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

    val Int    =  new Symbol("Int",  IntType,  Flags.Prim | Flags.Type, sourcePos = null)
    val Bool   =  new Symbol("Bool", BoolType, Flags.Prim | Flags.Type, sourcePos = null)
    val Void   =  new Symbol("Void", VoidType, Flags.Prim | Flags.Type, sourcePos = null)

    val allSymbols: List[Symbol] = symbols.toList
  end predef

  object runtime:
    private val abortType = ProcType("n" :: Nil, IntType :: Nil, BottomType)
    val abort = new Symbol("abort", abortType, Flags.Prim, sourcePos = null)
