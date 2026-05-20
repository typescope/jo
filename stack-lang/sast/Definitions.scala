package sast

import Types.*
import Symbols.*

import reporting.Reporter

final class Definitions(val index: SymbolIndex) extends Definitions.Lazy:
  //----------------------------------------------------------------------------
  // Info provider for symbols
  //
  given Definitions = this

  //----------------------------------------------------------------------------
  // Name lookup

  def resolveTerm(path: String): Symbol = resolveStatic(path.split('.').toList, Universe.Term)
  def resolveContainer(path: String): Symbol = resolveStatic(path.split('.').toList, Universe.Container)

  def resolveStatic(parts: List[String], universe: Universe): Symbol =
    Definitions.resolveStatic(rootNameTable, parts, universe) match
      case Some(sym) => sym
      case None =>
        throw new Exception("[Internal error] cannot find " + parts.mkString("."))

  def resolveTermOpt(path: String): Option[Symbol] = Definitions.resolveStatic(rootNameTable, path.split('.').toList, Universe.Term)
  def resolveContainerOpt(path: String): Option[Symbol] = Definitions.resolveStatic(rootNameTable, path.split('.').toList, Universe.Container)

  //----------------------------------------------------------------------------
  // Definitions.Lazy implementation
  //

  def rootNameTable: NameTable = index.nameTable
  def value: Definitions = this

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

  val Iterator_type = jo.typeMember("Iterator")

  val jo_Pack =  jo.typeMember("..")

  // Unit
  val Unit_type  =  jo.typeMember("Unit")
  val jo_pass    =  jo.termMember("pass")

  // Pair
  val jo_Pair_def      =  jo.termMember("Pair")

  val Array_type      =  jo.typeMember("Array")

  val Array_sec       =  jo.containerMember("Array")
  val IntArray        =  Array_sec.termMember("IntArray")
  val FloatArray      =  Array_sec.termMember("FloatArray")
  val CharArray       =  Array_sec.termMember("CharArray")
  val ByteArray       =  Array_sec.termMember("ByteArray")
  val BoolArray       =  Array_sec.termMember("BoolArray")
  val RefArray     =  Array_sec.termMember("RefArray")

  val IntArray_class    = Array_sec.typeMember("IntArray")
  val FloatArray_class  = Array_sec.typeMember("FloatArray")
  val CharArray_class   = Array_sec.typeMember("CharArray")
  val ByteArray_class   = Array_sec.typeMember("ByteArray")
  val BoolArray_class   = Array_sec.typeMember("BoolArray")
  val RefArray_class = Array_sec.typeMember("RefArray")

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

  // Compile utilities
  val compile          = resolveContainer("jo.compile")
  val Mixed_type       = compile.typeMember("Mixed")
  val Named_type       = compile.typeMember("Named")
  val compile_namedArg = compile.termMember("namedArg")
  val intrinsic        = compile.annotationMember("intrinsic")
  val shadow           = jo.annotationMember("shadow")

  // Regex
  val regex = resolveContainer("jo.regex")
  val Regex_sec = regex.containerMember("Regex")
  val Regex_compileValidated = Regex_sec.termMember("compileValidated")
  val Regex_Match_type = regex.typeMember("Match")

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

  val main = jo.termMember("main")
  val abort = jo.termMember("abort")

  val IntType     = StaticRef(Int_type)
  val BoolType    = StaticRef(Bool_type)
  val ByteType    = StaticRef(Byte_type)
  val CharType    = StaticRef(Char_type)
  val StringType  = StaticRef(String_type)
  val FloatType   = StaticRef(Float_type)

  val UnitType    = StaticRef(Unit_type)

  val StringLikeType = StaticRef(jo.typeMember("StringLike"))

  def isNumeric(sym: Symbol): Boolean =
    sym == Byte_type
    || sym == Char_type
    || sym == Int_type
    || sym == Float_type

  def isNumericOrBool(sym: Symbol): Boolean =
    isNumeric(sym) || sym == Bool_type
end Definitions

object Definitions:
  abstract class Lazy:
    def rootNameTable: NameTable
    def index: SymbolIndex
    def value: Definitions

  def Lazy(nameTable: NameTable)(using Reporter) = new Lazy:
    val rootNameTable = nameTable
    val index: SymbolIndex = new SymbolIndex(rootNameTable, new SymInfoProvider)
    lazy val value: Definitions = new Definitions(index)

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
