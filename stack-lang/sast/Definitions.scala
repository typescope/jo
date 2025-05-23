package sast

import Types.*
import Symbols.*
import Definitions.InfoProvider

import reporting.Reporter

import scala.collection.mutable

final class Definitions(rootNameTable: NameTable, provider: InfoProvider):
  export rootNameTable.resolveTermByPath

  //----------------------------------------------------------------------------
  // Info provider for symbols
  //
  given Definitions = this

  def info(sym: Symbol): SymInfo = provider(sym)

  export provider.{ add, addLazy }

  //----------------------------------------------------------------------------
  // Predefined symbols
  //

  val Predef = rootNameTable.resolveTermByPath("stk.Predef")
  val Predef_nameTable = Predef.info.as[NameTableInfo].nameTable

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
  val List_List    =  List.typeMember("List")
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

  private val subtypingCache: mutable.Map[Type, mutable.Map[Type, Boolean]] =
    mutable.Map.empty

  def cachedConforms(tp1: Type, tp2: Type, cache: Boolean)(work: => Boolean): Boolean =
    val tp1norm = TypeOps.normalize(tp1)
    val tp2norm = TypeOps.normalize(tp2)
    subtypingCache.get(tp1norm) match
      case Some(innerMap) =>
        innerMap.get(tp2norm) match
          case Some(res) => res
          case None =>
            val res = work
            if cache then innerMap(tp2norm) = res
            res

      case None =>
        val innerMap: mutable.Map[Type, Boolean] = mutable.Map.empty
        subtypingCache(tp1norm) = innerMap
        val res = work
        if cache then innerMap(tp2norm) = res
        res

end Definitions

object Definitions:
  abstract class InfoProvider:
    def add(sym: Symbol, owner: Symbol, tp: Type): Unit

    def addLazy(sym: Symbol, owner: Symbol, infoLazy: () => Type, errorType: () => Type): Unit

    def addLazy(sym: Symbol, owner: Symbol, infoLazy: () => Type): Unit =
      addLazy(sym, owner, infoLazy, () => ErrorType)

    def get(sym: Symbol): Option[SymInfo]

    def info(sym: Symbol): Type = apply(sym).tpe

    def dealiasedInfo(sym: Symbol): Type =
      apply(sym).tpe match
        case StaticRef(sym) if sym.isAlias => dealiasedInfo(sym)
        case tp => tp

    def apply(sym: Symbol): SymInfo =
      get(sym) match
        case Some(info) => info
        case _ => throw new Exception("Not found info for " + sym)

  class Lazy(val rootNameTable: NameTable)(using Reporter):
    val infoProvider: InfoProvider = new SymInfoProvider
    lazy val value: Definitions = new Definitions(rootNameTable, infoProvider)
