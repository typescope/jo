package sast

import Types.*
import Symbols.*

import reporting.Reporter

final class Definitions(private var _index: SymbolIndex) extends Definitions.Lazy, Cloneable:
  //----------------------------------------------------------------------------
  // Info provider for symbols
  //
  given Definitions = this

  def snapshot: Definitions =
    val image = this.clone().asInstanceOf[Definitions]
    image._index = this._index.snapshot
    image

  def index: SymbolIndex = _index

  //----------------------------------------------------------------------------
  // Name lookup

  def resolveTerm(path: String): Symbol = resolveStatic(path.split('.').toList, SymbolKind.Term)
  def resolveType(path: String): Symbol = resolveStatic(path.split('.').toList, SymbolKind.Type)
  def resolveContainer(path: String): Symbol = resolveStatic(path.split('.').toList, SymbolKind.Container)

  def resolveStatic(parts: List[String], universe: SymbolKind): Symbol =
    Definitions.resolveStatic(rootNameTable, parts, universe) match
      case Some(sym) => sym
      case None =>
        throw new Exception("[Internal error] cannot find " + parts.mkString("."))

  def resolveTermOpt(path: String): Option[Symbol] = Definitions.resolveStatic(rootNameTable, path.split('.').toList, SymbolKind.Term)
  def resolveContainerOpt(path: String): Option[Symbol] = Definitions.resolveStatic(rootNameTable, path.split('.').toList, SymbolKind.Container)

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

  val Array_class     =  jo.typeMember("Array")
  val Array_sec       =  jo.containerMember("Array")
  val Array_create    =  Array_sec.termMember("create")

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
  val Regex_compileChecked = Regex_sec.termMember("compileChecked")
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

  // Mutable List
  val MutableList_type =  mutable.typeMember("List")
  val MutableList_def  =  mutable.termMember("List")

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

  //----------------------------------------------------------------------------
  // Unique ids
  val uniqs = new Definitions.Uniques

  //----------------------------------------------------------------------------
  // helper functions

  def isNumeric(sym: Symbol): Boolean =
    sym == Byte_type
    || sym == Char_type
    || sym == Int_type
    || sym == Float_type

  def isNumericOrBool(sym: Symbol): Boolean =
    isNumeric(sym) || sym == Bool_type
end Definitions

object Definitions:
  class Uniques:
    val unification = new common.UniqueId

  abstract class Lazy:
    def rootNameTable: NameTable
    def index: SymbolIndex
    def value: Definitions

  def Lazy(nameTable: NameTable)(using Reporter) = new Lazy:
    val rootNameTable = nameTable
    val index: SymbolIndex = new SymbolIndex(rootNameTable, new SymInfoProvider)
    lazy val value: Definitions = new Definitions(index)

  def resolveStatic(nameTable: NameTable, parts: List[String], universe: SymbolKind): Option[Symbol] =
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
