import Types.*
import Symbols.*

import scala.collection.mutable

object Predef:
  private val symbols: mutable.ArrayBuffer[Symbol] = new mutable.ArrayBuffer

  private def createPrimSymbol(name: String, tp: Type): Symbol =
    val sym = new Symbol(name, tp, Flags.Prim, sourcePos = null)
    symbols += sym
    sym

  //----------------------------------------------------------------------------
  // primitive symbols are available to programmers

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

  //----------------------------------------------------------------------------
  // run-time symbols are only available to the compiler

  private val abortType = ProcType("n" :: Nil, IntType :: Nil, BottomType)
  val abort = new Symbol("abort", abortType, Flags.Prim, sourcePos = null)
