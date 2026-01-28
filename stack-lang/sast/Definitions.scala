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
  // Doc comments
  //

  private val docComments = scala.collection.mutable.Map[Symbol, List[String]]()

  def setDocComment(sym: Symbol, doc: List[String]): Unit =
    if doc.nonEmpty then docComments(sym) = doc

  def docComment(sym: Symbol): List[String] =
    docComments.getOrElse(sym, Nil)

  //----------------------------------------------------------------------------
  // Predefined symbols
  //

  val jo = resolveContainer("jo")
  val jo_nameTable = jo.nameTable

  // primitive terms without implementation in source code
  val Byte_type   =  jo.typeMember("Byte")
  val Char_type   =  jo.typeMember("Char")
  val Int_type    =  jo.typeMember("Int")
  val Bool_type   =  jo.typeMember("Bool")
  val Float_type  =  jo.typeMember("Float")
  val String_type =  jo.typeMember("String")

  val Bool_and    =  jo.termMember("&&")
  val Bool_or     =  jo.termMember("||")
  val Bool_not    =  jo.termMember("!")

  val jo_Pack          =  jo.typeMember("..")

  // Unit
  val jo_pass          =  jo.termMember("pass")

  // Pair
  val jo_Pair_def      =  jo.termMember("Pair")

  val Array_type      =  jo.typeMember("Array")

  val IntArray        =  jo.termMember("IntArray")
  val FloatArray      =  jo.termMember("FloatArray")
  val CharArray       =  jo.termMember("CharArray")
  val ByteArray       =  jo.termMember("ByteArray")
  val BoolArray       =  jo.termMember("BoolArray")
  val ObjectArray     =  jo.termMember("ObjectArray")

  val IntArray_class    =  jo.typeMember("IntArray")
  val FloatArray_class  =  jo.typeMember("FloatArray")
  val CharArray_class   =  jo.typeMember("CharArray")
  val ByteArray_class   =  jo.typeMember("ByteArray")
  val BoolArray_class   =  jo.typeMember("BoolArray")
  val ObjectArray_class =  jo.typeMember("ObjectArray")

  val ArrayBuilder       =  jo.typeMember("ArrayBuilder")
  val ArrayBuilder_sec   =  jo.containerMember("ArrayBuilder")

  val IntArrayBuilder    =  ArrayBuilder_sec.termMember("IntArrayBuilder")
  val FloatArrayBuilder  =  ArrayBuilder_sec.termMember("FloatArrayBuilder")
  val CharArrayBuilder   =  ArrayBuilder_sec.termMember("CharArrayBuilder")
  val ByteArrayBuilder   =  ArrayBuilder_sec.termMember("ByteArrayBuilder")
  val BoolArrayBuilder   =  ArrayBuilder_sec.termMember("BoolArrayBuilder")

  // Lists
  val List         =  resolveContainer("jo.List")
  val List_type    =  jo.typeMember("List")
  val List_def     =  jo.termMember("List")
  val List_empty   =  List.termMember("empty")

  // Maps
  val Map_type     =  jo.typeMember("Map")
  val Map_def      =  jo.termMember("Map")

  // Sets
  val Set_type     =  jo.typeMember("Set")
  val Set_def      =  jo.termMember("Set")

  // Mutable Maps
  val mutable         =  resolveContainer("jo.mutable")
  val MutableMap_type =  mutable.typeMember("Map")
  val MutableMap_def  =  mutable.termMember("Map")

  // Mutable Sets
  val MutableSet_type =  mutable.typeMember("Set")
  val MutableSet_def  =  mutable.termMember("Set")

  // ArrayBuffer
  val ArrayBuffer_type =  mutable.typeMember("ArrayBuffer")
  val ArrayBuffer_def  =  mutable.termMember("ArrayBuffer")

  // patterns
  val orPattern  = jo.patternMember("|")
  val andPattern = jo.patternMember("&")
  val notPattern = jo.patternMember("!")
  val Partial    = jo.typeMember("Partial")

  val main = resolveTerm("jo.main")

  // Internal
  val Internal              =  resolveContainer("jo.Internal")
  val Internal_abort        =  Internal.termMember("abort")
  val Internal_typeTest     =  Internal.termMember("typeTest")

  val IntType     = StaticRef(Int_type)
  val BoolType    = StaticRef(Bool_type)
  val ByteType    = StaticRef(Byte_type)
  val CharType    = StaticRef(Char_type)
  val StringType  = StaticRef(String_type)
  val FloatType   = StaticRef(Float_type)

  val UnitType    = StaticRef(jo.typeMember("Unit"))

  val StringLikeType = StaticRef(jo.typeMember("StringLike"))

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
