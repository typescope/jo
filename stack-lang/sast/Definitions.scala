package sast

import Symbols.*
import Types.*

import common.Dynamic

class Definitions(val rootNameTable: NameTable):
  //----------------------------------------------------------------------------
  // primitive symbols are available to programmers

  def resolveTerm(path: String): Symbol =
    NameTable.resolvePath(rootNameTable, path, isType = false)

  def resolveType(path: String): Symbol =
    NameTable.resolvePath(rootNameTable, path, isType = true)

  val Predef        =  resolveTerm("stk.Predef")
  val Predef_nameTable = Predef.info.as[NamespaceInfo].nameTable

  // primitive terms without implementation in source code
  val Predef_add    =  resolveTerm("stk.Predef.+")
  val Predef_sub    =  resolveTerm("stk.Predef.-")
  val Predef_mul    =  resolveTerm("stk.Predef.*")
  val Predef_div    =  resolveTerm("stk.Predef./")
  val Predef_mod    =  resolveTerm("stk.Predef.%")
  val Predef_gt     =  resolveTerm("stk.Predef.>")
  val Predef_lt     =  resolveTerm("stk.Predef.<")
  val Predef_ge     =  resolveTerm("stk.Predef.>=")
  val Predef_le     =  resolveTerm("stk.Predef.<=")
  val Predef_eql    =  resolveTerm("stk.Predef.==")
  val Predef_srl    =  resolveTerm("stk.Predef.>>")
  val Predef_sll    =  resolveTerm("stk.Predef.<<")
  val Predef_land   =  resolveTerm("stk.Predef.&")
  val Predef_lor    =  resolveTerm("stk.Predef.|")
  val Predef_lxor   =  resolveTerm("stk.Predef.^")
  val Predef_band   =  resolveTerm("stk.Predef.and")
  val Predef_bor    =  resolveTerm("stk.Predef.or")
  val Predef_bnot   =  resolveTerm("stk.Predef.not")
  val Predef_p      =  resolveTerm("stk.Predef.p")
  val Predef_print  =  resolveTerm("stk.Predef.print")
  val Predef_abort  =  resolveTerm("stk.Predef.abort")

  val Predef_js     =  resolveTerm("stk.Predef.js")

  // types
  val Predef_Int    =  resolveType("stk.Predef.Int")
  val Predef_Bool   =  resolveType("stk.Predef.Bool")
  val Predef_String =  resolveType("stk.Predef.String")
  val Predef_Void   =  resolveType("stk.Predef.Void")

object Definitions:
  val key = new Dynamic.Key[Definitions]("definitions")

  def initialize(rootNameTable: NameTable): Unit =
    val definitions = new Definitions(rootNameTable)
    Dynamic.install(Definitions.key, definitions)

  def instance: Definitions = Dynamic.get(key)
