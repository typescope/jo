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

  private val typeArith = ProcType(aInt :: bInt :: Nil, IntType, preParamCount = 1, precedence = 0)
  private val typeComp  = ProcType(aInt :: bInt :: Nil, BoolType, preParamCount = 1, precedence = 0)
  private val typeBits  = ProcType(aInt :: bInt :: Nil, IntType, preParamCount = 1, precedence = 0)

  private val typeAnd = ProcType(aBool :: bBool :: Nil, BoolType, preParamCount = 1, precedence = 0)
  private val typeOr  = ProcType(aBool :: bBool :: Nil, BoolType, preParamCount = 1, precedence = 0)
  private val typeNot = ProcType(aBool :: Nil, BoolType, preParamCount = 0, precedence = 0)

  private val typePrint  = ProcType(params = aInt :: Nil, VoidType, preParamCount = 1, precedence = 0)

  val add    =  createPrimSymbol("+",   typeArith.copy(precedence = 50))
  val sub    =  createPrimSymbol("-",   typeArith.copy(precedence = 50))
  val mul    =  createPrimSymbol("*",   typeArith.copy(precedence = 60))
  val div    =  createPrimSymbol("/",   typeArith.copy(precedence = 60))
  val mod    =  createPrimSymbol("%",   typeArith.copy(precedence = 60))
  val gt     =  createPrimSymbol(">",   typeComp.copy(precedence = 40))
  val lt     =  createPrimSymbol("<",   typeComp.copy(precedence = 40))
  val ge     =  createPrimSymbol(">=",  typeComp.copy(precedence = 40))
  val le     =  createPrimSymbol("<=",  typeComp.copy(precedence = 40))
  val eql    =  createPrimSymbol("==",  typeComp.copy(precedence = 40))
  val srl    =  createPrimSymbol(">>",  typeBits.copy(precedence = 70))
  val sll    =  createPrimSymbol("<<",  typeBits.copy(precedence = 70))
  val land   =  createPrimSymbol("&",   typeBits.copy(precedence = 70))
  val lor    =  createPrimSymbol("|",   typeBits.copy(precedence = 70))
  val lxor   =  createPrimSymbol("^",   typeBits.copy(precedence = 70))
  val band   =  createPrimSymbol("and", typeAnd.copy(precedence = 30))
  val bor    =  createPrimSymbol("or",  typeOr.copy(precedence = 20))
  val bnot   =  createPrimSymbol("not", typeNot.copy(precedence = 35))
  val p      =  createPrimSymbol("p",   typePrint.copy(precedence = 10))

  val Int    =  new Symbol("Int",  IntType,  Flags.Prim | Flags.Type, sourcePos = null)
  val Bool   =  new Symbol("Bool", BoolType, Flags.Prim | Flags.Type, sourcePos = null)
  val Void   =  new Symbol("Void", VoidType, Flags.Prim | Flags.Type, sourcePos = null)

  val allSymbols: List[Symbol] = symbols.toList

  //----------------------------------------------------------------------------
  // run-time symbols are only available to the compiler

  private val abortType = ProcType(
      ParamInfo("n", IntType) :: Nil, BottomType,
      preParamCount = 0, precedence = 0)
  val abort = new Symbol("abort", abortType, Flags.Prim, sourcePos = null)

  //----------------------------------------------------------------------------
  // the memory allocator
  private val allocateType = ProcType(
      ParamInfo("size", IntType) :: Nil, IntType,
      preParamCount = 0, precedence = 0)
  val allocate = new Symbol("alloc", allocateType, Flags.Prim, sourcePos = null)
