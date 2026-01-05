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

  def resolveTerm(path: String): Symbol = resolveStatic(path.split('.').toList, Universe.Term)
  def resolveContainer(path: String): Symbol = resolveStatic(path.split('.').toList, Universe.Container)

  def resolveStatic(parts: List[String], universe: Universe) = Definitions.resolveStatic(nameTable, parts, universe).head

  def resolveTermOpt(path: String): Option[Symbol] = Definitions.resolveStatic(nameTable, path.split('.').toList, Universe.Term)

  //----------------------------------------------------------------------------
  // Definitions.Lazy implementation
  //

  def rootNameTable: NameTable = nameTable
  def infoProvider: InfoProvider = provider
  def value: Definitions = this

  private var provider: InfoProvider = initProvider

  private var cacheForInfoProvider: Cache = new Cache

  def cache: Cache = cacheForInfoProvider

  def info(sym: Symbol): Type = provider(sym)

  def add(sym: Symbol, tp: Type): Unit =
    provider.add(sym, tp)

  def addLazy(sym: Symbol, infoLazy: () => Type, errorType: () => Type): Unit =
    provider.addLazy(sym, infoLazy, errorType)

  def addLazy(sym: Symbol, infoLazy: () => Type): Unit =
    provider.addLazy(sym, infoLazy, () => ErrorType)

  /** Install a transformer for symbols
    *
    * Warning: Accessing `sym.info` or `sym.owner` will loop. Use the provided
    * data instead.
    */
  def installTransform(transform: (Symbol, Type) => Type): Unit =
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

  val jo = resolveContainer("jo")
  val jo_nameTable = jo.nameTable

  val Predef = resolveContainer("jo.Predef")
  val Predef_nameTable = Predef.nameTable

  // primitive terms without implementation in source code
  val Int        =  resolveContainer("jo.Int")
  val Int_Int    =  Int.typeMember("Int")

  val Bool        =  resolveContainer("jo.Bool")
  val Bool_Bool   =  Bool.typeMember("Bool")
  val Bool_and    =  Bool.termMember("&&")
  val Bool_or     =  Bool.termMember("||")
  val Bool_not    =  Bool.termMember("!")
  val Bool_both   =  Bool.termMember("both")
  val Bool_either =  Bool.termMember("either")

  val Float         =  resolveContainer("jo.Float")
  val Float_Float   =  Float.typeMember("Float")

  val Predef_print      =  Predef.termMember("print")

  val Predef_triple_dot =  Predef.termMember("...")

  // I/O
  val IO        = resolveContainer("jo.IO")
  val IO_open   = IO.termMember("open")
  val IO_stdin  = IO.termMember("stdin")
  val IO_stdout = IO.termMember("stdout")
  val IO_stderr = IO.termMember("stderr")

  // types
  val Byte             =  resolveContainer("jo.Byte")
  val Byte_Byte        =  Byte.typeMember("Byte")
  val Char             =  resolveContainer("jo.Char")
  val Char_Char        =  Char.typeMember("Char")

  val Predef_Byte      =  Byte_Byte
  val Predef_Char      =  Char_Char
  val Predef_String    =  Predef.typeMember("String")
  val Predef_Pack      =  Predef.typeMember("..")

  // Unit
  val Predef_Unit_type =  Predef.typeMember("Unit")
  val Predef_Unit_def  =  Predef.termMember("Unit")

  val Array         =  resolveContainer("jo.Array")
  val Array_type    =  Array.typeMember("Array")
  val Array_create  =  Array.termMember("create")
  val Array_get     =  Array.termMember("get")
  val Array_set     =  Array.termMember("set")
  val Array_size     =  Array.termMember("size")

  // Lists
  val List         =  resolveContainer("jo.List")
  val List_type    =  List.typeMember("List")
  val List_List   =  List.termMember("List")
  val List_empty   =  List.termMember("empty")

  // patterns
  val Predef_orPattern = Predef.patternMember("|")
  val Predef_andPattern = Predef.patternMember("&")
  val Predef_Partial = Predef.typeMember("Partial")

  val Main_main = resolveTerm("jo.Main.main")

  // Internal
  val Internal              =  resolveContainer("jo.Internal")
  val Internal_abort        =  Internal.termMember("abort")
  val Internal_typeTest     =  Internal.termMember("typeTest")

  val IntType     = StaticRef(Int_Int)
  val BoolType    = StaticRef(Bool_Bool)
  val ByteType    = StaticRef(Predef_Byte)
  val CharType    = StaticRef(Predef_Char)
  val UnitType    = StaticRef(Predef_Unit_type)
  val StringType  = StaticRef(Predef_String)
  val FloatType   = StaticRef(Float_Float)


  val StringLikeType = StaticRef(Predef.typeMember("StringLike"))

  def isNumericType(tp: Type): Boolean =
    tp.isSubtype(ByteType)
    || tp.isSubtype(CharType)
    || tp.isSubtype(IntType)
    || tp.isSubtype(FloatType)

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

  def resolveStatic(nameTable: NameTable, parts: List[String]): List[Symbol] =
    (parts: @unchecked) match
      case name :: Nil =>
        nameTable.resolve(name)

      case name :: rest =>
        nameTable.resolveContainer(name) match
          case Some(sym) =>
            val nameTable = sym.nameTable
            resolveStatic(nameTable, rest)

          case None =>
            Nil

  def resolveStatic(nameTable: NameTable, parts: List[String], universe: Universe): Option[Symbol] =
    (parts: @unchecked) match
      case name :: Nil =>
        nameTable.resolve(name, universe)

      case name :: rest =>
        nameTable.resolveContainer(name) match
          case Some(sym) =>
            val nameTable = sym.nameTable
            resolveStatic(nameTable, rest, universe)

          case None =>
            None

  def resolveStatic(nameTable: NameTable, path: String, universe: Universe): Option[Symbol] =
    resolveStatic(nameTable, path.split('.').toList, universe)
