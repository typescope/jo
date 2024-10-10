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

  private val aInt = NamedInfo("a", IntType)
  private val bInt = NamedInfo("b", IntType)

  private val aBool = NamedInfo("a", BoolType)
  private val bBool = NamedInfo("b", BoolType)

  private val typeArith = ProcType(aInt :: bInt :: Nil, IntType, preParamCount = 1)
  private val typeComp  = ProcType(aInt :: bInt :: Nil, BoolType, preParamCount = 1)
  private val typeBits  = ProcType(aInt :: bInt :: Nil, IntType, preParamCount = 1)

  private val typeAnd = ProcType(aBool :: bBool :: Nil, BoolType, preParamCount = 1)
  private val typeOr  = ProcType(aBool :: bBool :: Nil, BoolType, preParamCount = 1)
  private val typeNot = ProcType(aBool :: Nil, BoolType, preParamCount = 0)

  private val typePrint  = ProcType(params = aInt :: Nil, VoidType, preParamCount = 1)

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

  private val abortType = ProcType(NamedInfo("n", IntType) :: Nil, BottomType, preParamCount = 0)
  val abort = new Symbol("abort", abortType, Flags.Prim, sourcePos = null)

  //----------------------------------------------------------------------------
  // the memory allocator
  private val allocateType = ProcType(NamedInfo("size", IntType) :: Nil, IntType, preParamCount = 0)
  val allocate = new Symbol("alloc", allocateType, Flags.Prim, sourcePos = null)
