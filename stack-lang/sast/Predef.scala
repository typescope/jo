package sast

import Types.*
import Symbols.*

import scala.collection.mutable

object Predef:
  private val termNames: mutable.Map[String, Symbol] = mutable.Map.empty
  private val typeNames: mutable.Map[String, Symbol] = mutable.Map.empty

  val nameTable: NameTable = new NameTable(termNames, typeNames)

  val predefSym = Symbol.createNamespaceSymbol(
      "predef", new NamespaceInfo(nameTable),
      owner = null, pos = null, isBranch = false)

  private def createPrimSymbol(name: String, tp: Type, isType: Boolean = false): Symbol =
    val flags = if isType then Flags.Prim | Flags.Type else Flags.Prim
    val sym = new Symbol(name, tp, flags, owner = predefSym, sourcePos = null)

    if isType then
      assert(!typeNames.contains(sym.name))
      typeNames(sym.name) = sym
    else
      assert(!termNames.contains(sym.name))
      termNames(sym.name) = sym

    sym

  //----------------------------------------------------------------------------
  // primitive symbols are available to programmers

  private val aInt = NamedInfo("a", IntType)
  private val bInt = NamedInfo("b", IntType)

  private val aBool = NamedInfo("a", BoolType)
  private val bBool = NamedInfo("b", BoolType)

  private val strParam = NamedInfo("s", StringType)

  private val typeArith = ProcType(aInt :: bInt :: Nil, IntType, preParamCount = 1)
  private val typeComp  = ProcType(aInt :: bInt :: Nil, BoolType, preParamCount = 1)
  private val typeBits  = ProcType(aInt :: bInt :: Nil, IntType, preParamCount = 1)

  private val typeAnd = ProcType(aBool :: bBool :: Nil, BoolType, preParamCount = 1)
  private val typeOr  = ProcType(aBool :: bBool :: Nil, BoolType, preParamCount = 1)
  private val typeNot = ProcType(aBool :: Nil, BoolType, preParamCount = 0)

  private val typePrintInt  = ProcType(params = aInt :: Nil, VoidType, preParamCount = 1)
  private val typePrintStr  = ProcType(params = strParam :: Nil, VoidType, preParamCount = 0)

  private val abortType = ProcType(NamedInfo("msg", StringType) :: Nil, BottomType, preParamCount = 0)

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
  val p      =  createPrimSymbol("p",   typePrintInt)
  val print  =  createPrimSymbol("print", typePrintStr)
  val abort  =  createPrimSymbol("abort", abortType)

  val Int    =  createPrimSymbol("Int",    IntType,  isType = true)
  val Bool   =  createPrimSymbol("Bool",   BoolType, isType = true)
  val String =  createPrimSymbol("String", StringType, isType = true)
  val Void   =  createPrimSymbol("Void",   VoidType, isType = true)
