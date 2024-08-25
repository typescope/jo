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

  private val aInt = ParamInfo("a", IntType)
  private val bInt = ParamInfo("b", IntType)

  private val aBool = ParamInfo("a", BoolType)
  private val bBool = ParamInfo("b", BoolType)

  private val typeArith = ProcType(aInt :: Nil, bInt :: Nil, IntType)
  private val typeComp  = ProcType(aInt :: Nil, bInt :: Nil, BoolType)
  private val typeBits  = ProcType(aInt :: Nil, bInt :: Nil, IntType)

  private val typeAnd = ProcType(aBool :: Nil, bBool :: Nil, BoolType)
  private val typeOr  = ProcType(aBool :: Nil, bBool :: Nil, BoolType)
  private val typeNot = ProcType(Nil, aBool :: Nil, BoolType)

  private val typePrint  = ProcType(preParams = aInt :: Nil, postParams = Nil, VoidType)

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

  private val abortType = ProcType(preParams = Nil, postParams = ParamInfo("n", IntType) :: Nil, BottomType)
  val abort = new Symbol("abort", abortType, Flags.Prim, sourcePos = null)

  //----------------------------------------------------------------------------
  // the memory allocator
  private val allocateType = ProcType(
    preParams = ParamInfo("size", IntType) :: Nil,
    postParams = Nil,
    IntType)
  val allocate = new Symbol("alloc", allocateType, Flags.Prim, sourcePos = null)
