package sast

import Types.*
import Symbols.*
import Trees.FunDef

import reporting.Reporter

final class Definitions(nameTable: NameTable, initProvider: InfoProvider)
extends Definitions.Lazy:
  //----------------------------------------------------------------------------
  // Info provider for symbols
  //
  given Definitions = this

  //----------------------------------------------------------------------------
  // Name lookup

  def resolveTermByPath(path: String): Symbol = nameTable.resolveTermByPath(path)
  def resolveTermByPathOpt(path: String): Option[Symbol] = nameTable.resolveTermByPathOpt(path)
  def resolveTypeByPath(path: String): Symbol = nameTable.resolveTypeByPath(path)
  def resolvePatternByPath(path: String): Symbol = nameTable.resolvePatternByPath(path)

  def resolveTermByPathParts(parts: List[String]): Symbol = nameTable.resolveTermByPathParts(parts)
  def resolveTypeByPathParts(parts: List[String]): Symbol = nameTable.resolveTypeByPathParts(parts)
  def resolvePatternByPathParts(parts: List[String]): Symbol = nameTable.resolvePatternByPathParts(parts)

  //----------------------------------------------------------------------------
  // Definitions.Lazy implementation
  //

  def rootNameTable: NameTable = nameTable
  def infoProvider: InfoProvider = provider
  def value: Definitions = this

  private var provider: InfoProvider = initProvider

  private var cacheForInfoProvider: Cache = new Cache

  def cache: Cache = cacheForInfoProvider

  def info(sym: Symbol): SymInfo = provider(sym)

  def add(sym: Symbol, owner: Symbol, tp: Type): Unit =
    provider.add(sym, owner, tp)

  def addLazy(sym: Symbol, owner: Symbol, infoLazy: () => Type, errorType: () => Type): Unit =
    provider.addLazy(sym, owner, infoLazy, errorType)

  def addLazy(sym: Symbol, owner: Symbol, infoLazy: () => Type): Unit =
    provider.addLazy(sym, owner, infoLazy, () => ErrorType)

  /** Install a transformer for symbols
    *
    * Warning: Accessing `sym.info` or `sym.owner` will loop. Use the provided
    * data in `SymInfo` instead.
    */
  def installTransform(transform: SymInfo => SymInfo): Unit =
    provider = new InfoProvider.InfoTransformer(provider, transform)

    // Invalidate old cache
    cacheForInfoProvider = new Cache


  //----------------------------------------------------------------------------
  // Effects provider
  //
  val effectEngine: EffectAnalysis = new EffectAnalysis

  def receives(sym: Symbol): List[Symbol] = effectEngine.effects(sym).keys.toList

  //----------------------------------------------------------------------------
  // Code provider
  //

  private val codeProvider = new CodeProvider

  def getCode(sym: Symbol): FunDef = codeProvider.get(sym).get

  def getCodeOpt(sym: Symbol): Option[FunDef] = codeProvider.get(sym)

  def setCode(sym: Symbol, code: FunDef): Unit = codeProvider.set(sym, code)

  //----------------------------------------------------------------------------
  // Predefined symbols
  //

  val Predef = resolveTermByPath("stk.Predef")
  val Predef_nameTable = Predef.info.as[ContainerInfo].nameTable

  // primitive terms without implementation in source code
  val Int        =  resolveTermByPath("stk.Int")
  val Int_Int    =  Int.typeMember("Int")
  val Int_add    =  Int.termMember("+")
  val Int_sub    =  Int.termMember("-")
  val Int_mul    =  Int.termMember("*")
  val Int_div    =  Int.termMember("/")
  val Int_mod    =  Int.termMember("%")
  val Int_gt     =  Int.termMember(">")
  val Int_lt     =  Int.termMember("<")
  val Int_ge     =  Int.termMember(">=")
  val Int_le     =  Int.termMember("<=")
  val Int_eql    =  Int.termMember("==")
  val Int_srl    =  Int.termMember(">>")
  val Int_sll    =  Int.termMember("<<")
  val Int_land   =  Int.termMember("&")
  val Int_lor    =  Int.termMember("|")
  val Int_lxor   =  Int.termMember("^")

  val Bool        =  resolveTermByPath("stk.Bool")
  val Bool_Bool   =  Bool.typeMember("Bool")
  val Bool_and    =  Bool.termMember("&&")
  val Bool_or     =  Bool.termMember("||")
  val Bool_not    =  Bool.termMember("!")
  val Bool_both   =  Bool.termMember("both")
  val Bool_either =  Bool.termMember("either")

  val Predef_print      =  Predef.termMember("print")
  val Predef_printChar  =  Predef.termMember("printChar")
  val Predef_abort      =  Predef.termMember("abort")
  val Predef_dotdot     =  Predef.termMember("..")

  // numeric coercion
  val Predef_byteToChar = Predef.termMember("byteToChar")
  val Predef_byteToInt  = Predef.termMember("byteToInt")
  val Predef_charToByte = Predef.termMember("charToByte")
  val Predef_charToInt  = Predef.termMember("charToInt")
  val Predef_charToStr  = Predef.termMember("charToStr")
  val Predef_intToByte  = Predef.termMember("intToByte")
  val Predef_intToChar  = Predef.termMember("intToChar")
  val Predef_intToStr   = Predef.termMember("intToStr")

  // I/O
  val IO        = resolveTermByPath("stk.IO")
  val IO_open   = IO.termMember("open")
  val IO_stdin  = IO.termMember("stdin")
  val IO_stdout = IO.termMember("stdout")
  val IO_stderr = IO.termMember("stderr")

  // types
  val Predef_Byte   =  Predef.typeMember("Byte")
  val Predef_Char   =  Predef.typeMember("Char")
  val Predef_Unit   =  Predef.typeMember("Unit")
  val Predef_String =  Predef.typeMember("String")
  val Predef_Pack   =  Predef.typeMember("..")

  val Array         =  resolveTermByPath("stk.Array")
  val Array_Array   =  Array.typeMember("Array")
  val Array_create  =  Array.termMember("create")
  val Array_get     =  Array.termMember("get")
  val Array_set     =  Array.termMember("set")
  val Array_size     =  Array.termMember("size")

  // Lists
  val List         =  resolveTermByPath("stk.List")
  val List_type    =  List.typeMember("List")
  val List_List   =  List.termMember("List")
  val List_empty   =  List.termMember("empty")

  // patterns
  val Predef_orPattern = Predef.patternMember("|")
  val Predef_Partial = Predef.typeMember("Partial")


  // Internal
  val Internal              =  resolveTermByPath("stk.Internal")
  val Internal_Seq          =  Internal.typeMember("Seq")
  val Internal_PackElemType =  Internal.typeMember("PackElemType")

  val IntType     = StaticRef(Int_Int)
  val BoolType    = StaticRef(Bool_Bool)
  val ByteType    = StaticRef(Predef_Byte)
  val CharType    = StaticRef(Predef_Char)
  val UnitType    = StaticRef(Predef_Unit)
  val StringType  = StaticRef(Predef_String)

  def isNumericType(tp: Type): Boolean =
    tp.refersAny(Predef_Byte :: Predef_Char :: Int_Int :: Nil)

  val runtimeContextParams = Set(
    IO_open,
    IO_stdin,
    IO_stdout,
    IO_stderr,
  )

  def isRuntimeContextParam(sym: Symbol): Boolean =
    runtimeContextParams.exists(param => sym.refers(param))

end Definitions

object Definitions:
  abstract class Lazy:
    def rootNameTable: NameTable
    def infoProvider: InfoProvider
    def value: Definitions

  def Lazy(nameTable: NameTable)(using Reporter) = new Lazy:
    val rootNameTable = nameTable
    val infoProvider: InfoProvider = new SymInfoProvider
    lazy val value: Definitions = new Definitions(nameTable, infoProvider)
