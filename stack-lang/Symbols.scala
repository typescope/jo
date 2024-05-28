import Types.*

import scala.collection.mutable

/** Symbols refer to definitions (names) in the program.
  *
  * Symbols are stable in the compilation process, while the types of a symbol
  * might change, e.g., due to erasure or encoding of types.
  */
object Symbols:

  final class Symbol(val name: String, val info: Type, val flags: Flags):
    def isPrimitive: Boolean = flags.is(Flag.Prim)
    def isFunction : Boolean = flags.is(Flag.Fun)
    def isValue    : Boolean = flags.is(Flag.Val)
    def isParameter: Boolean = flags.is(Flag.Param)
    def isLocal    : Boolean = flags.is(Flag.Local)
    def isMutable  : Boolean = flags.is(Flag.Mutable)

    override def toString() = name

  object Symbol:
    def createValueSymbol(name: String, tp: Type) =
      new Symbol(name, tp, Flag.Val)

    def createValueSymbol(name: String, tp: Type, flags: Flags) =
      new Symbol(name, tp, Flag.Val | flags)

    def createFunSymbol(name: String, info: Type) =
      new Symbol(name, info, Flag.Fun)

    def createParamSymbol(name: String, tp: Type) =
      new Symbol(name, tp, Flag.Param | Flag.Val | Flag.Local)

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

  object predef:
    private val symbols: mutable.ArrayBuffer[Symbol] = new mutable.ArrayBuffer

    private def createPrimSymbol(name: String, tp: Type): Symbol =
      val sym = new Symbol(name, tp, Flag.Prim)
      symbols += sym
      sym

    val oneBoolType = Type.Bool :: Nil
    val oneIntType = Type.Int :: Nil
    val twoIntTypes = Type.Int :: Type.Int :: Nil
    val twoBoolTypes = Type.Bool :: Type.Bool :: Nil

    val typeArith = Type.Proc("m" :: "n" :: Nil, twoIntTypes, Type.Int)
    val typeComp = Type.Proc("m" :: "n" :: Nil, twoIntTypes, Type.Bool)
    val typeBits = Type.Proc("m" :: "n" :: Nil, twoIntTypes, Type.Int)

    val typeAnd = Type.Proc("a" :: "b" :: Nil, twoBoolTypes, Type.Bool)
    val typeOr  = Type.Proc("a" :: "b" :: Nil, twoBoolTypes, Type.Bool)
    val typeNot  = Type.Proc("a" :: Nil, oneBoolType, Type.Bool)

    val typePrint  = Type.Proc("n" :: Nil, oneIntType, Type.Void)

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

    val allSymbols: List[Symbol] = symbols.toList
  end predef
