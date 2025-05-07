package sast

import Types.*
import Symbols.*
import Definitions.InfoProvider

final class Definitions(rootNameTable: NameTable, provider: InfoProvider):
  //----------------------------------------------------------------------------
  // Info provider for symbols
  //
  given Definitions = this

  def info(sym: Symbol): SymInfo = provider(sym)

  export provider.{ add, addLazy }

  //----------------------------------------------------------------------------
  // Predefined symbols
  //

  val Predef =  rootNameTable.resolveTermByPath("stk.Predef")
  val Predef_nameTable = Predef.info.as[NameTableInfo].nameTable

  // primitive terms without implementation in source code
  val Predef_add    =  Predef.termMember("+")
  val Predef_sub    =  Predef.termMember("-")
  val Predef_mul    =  Predef.termMember("*")
  val Predef_div    =  Predef.termMember("/")
  val Predef_mod    =  Predef.termMember("%")
  val Predef_gt     =  Predef.termMember(">")
  val Predef_lt     =  Predef.termMember("<")
  val Predef_ge     =  Predef.termMember(">=")
  val Predef_le     =  Predef.termMember("<=")
  val Predef_eql    =  Predef.termMember("==")
  val Predef_srl    =  Predef.termMember(">>")
  val Predef_sll    =  Predef.termMember("<<")
  val Predef_land   =  Predef.termMember("&")
  val Predef_lor    =  Predef.termMember("|")
  val Predef_lxor   =  Predef.termMember("^")

  val Predef_and   =  Predef.termMember("&&")
  val Predef_or    =  Predef.termMember("||")
  val Predef_not  =  Predef.termMember("!")

  val Predef_both   =  Predef.termMember("both")
  val Predef_either =  Predef.termMember("either")

  val Predef_print      =  Predef.termMember("print")
  val Predef_printChar  =  Predef.termMember("printChar")
  val Predef_abort      =  Predef.termMember("abort")

  val Predef_array  =  Predef.termMember("array")

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
  val Predef_open   = Predef.termMember("open")
  val Predef_stdin  = Predef.termMember("stdin")
  val Predef_stdout = Predef.termMember("stdout")
  val Predef_stderr = Predef.termMember("stderr")

  // types
  val Predef_Bool   =  Predef.typeMember("Bool")
  val Predef_Byte   =  Predef.typeMember("Byte")
  val Predef_Char   =  Predef.typeMember("Char")
  val Predef_Int    =  Predef.typeMember("Int")
  val Predef_Unit   =  Predef.typeMember("Unit")
  val Predef_String =  Predef.typeMember("String")
  val Predef_Array  =  Predef.typeMember("Array")

  // Lists
  val Predef_List      =  Predef.typeMember("List")
  val Predef_Nil_fun   =  Predef.termMember("Nil").termMember("Nil")
  val Predef_Cons_fun  =  Predef.termMember("Cons").termMember("Cons")

  // patterns
  val Predef_orPattern = Predef.patternMember("|")
  val Predef_Partial = Predef.typeMember("Partial")
  val Predef_Seq    =  Predef.typeMember("Seq")

  val IntType     = TypeRef(Predef_Int)
  val BoolType    = TypeRef(Predef_Bool)
  val ByteType    = TypeRef(Predef_Byte)
  val CharType    = TypeRef(Predef_Char)
  val UnitType    = TypeRef(Predef_Unit)
  val StringType  = TypeRef(Predef_String)

  def isNumericType(tp: Type): Boolean =
    tp.refersAny(Predef_Byte :: Predef_Char :: Predef_Int :: Nil)

  val runtimeContextParams = Set(
    Predef_open,
    Predef_stdin,
    Predef_stdout,
    Predef_stderr,
  )

  def isRuntimeContextParam(sym: Symbol): Boolean =
    runtimeContextParams.contains(sym)


object Definitions:
  abstract class InfoProvider:
    def add(sym: Symbol, owner: Symbol, tp: Type): Unit

    def addLazy(sym: Symbol, owner: Symbol, infoLazy: () => Type, errorType: () => Type): Unit

    def addLazy(sym: Symbol, owner: Symbol, infoLazy: () => Type): Unit =
      addLazy(sym, owner, infoLazy, () => ErrorType)

    def get(sym: Symbol): Option[SymInfo]

    def info(sym: Symbol): Type = apply(sym).tpe

    def apply(sym: Symbol): SymInfo =
      get(sym) match
        case Some(info) => info
        case _ => throw new Exception("Not found info for " + sym)


  class Lazy(val rootNameTable: NameTable, val infoProvider: InfoProvider):
    lazy val value: Definitions = new Definitions(rootNameTable, infoProvider)
