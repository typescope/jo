package sast

import Types.*

import common.Dynamic

class Definitions(rootNameTable: NameTable):
  import rootNameTable.resolvePath

  val Predef =  resolvePath("stk.Predef")
  val Predef_nameTable = Predef.info.as[NamespaceInfo].nameTable

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
  val Predef_band   =  Predef.termMember("and")
  val Predef_bor    =  Predef.termMember("or")
  val Predef_bnot   =  Predef.termMember("not")
  val Predef_p      =  Predef.termMember("p")
  val Predef_print  =  Predef.termMember("print")
  val Predef_abort  =  Predef.termMember("abort")

  val Predef_js     =  Predef.termMember("js")

  // types
  val Predef_Int    =  Predef.typeMember("Int")
  val Predef_Bool   =  Predef.typeMember("Bool")
  val Predef_String =  Predef.typeMember("String")
  val Predef_Void   =  Predef.typeMember("Void")

object Definitions:
  val key = new Dynamic.Key[Definitions]("definitions")

  def initialize(rootNameTable: NameTable): Unit =
    val definitions = new Definitions(rootNameTable)
    Dynamic.install(Definitions.key, definitions)

  def instance: Definitions = Dynamic.get(key)
