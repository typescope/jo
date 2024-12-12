package sast

import Sast.Namespace
import Symbols.*

import common.Dynamic
import parsing.Parser
import typing.Namer
import reporting.Reporter

class Predef(val nameTable: NameTable):
  //----------------------------------------------------------------------------
  // primitive symbols are available to programmers

  def resolveTerm(name: String): Symbol = nameTable.resolveTerm(name).get
  def resolveType(name: String): Symbol = nameTable.resolveType(name).get

  // primitive terms without implementation in source code
  val add    =  resolveTerm("+")
  val sub    =  resolveTerm("-")
  val mul    =  resolveTerm("*")
  val div    =  resolveTerm("/")
  val mod    =  resolveTerm("%")
  val gt     =  resolveTerm(">")
  val lt     =  resolveTerm("<")
  val ge     =  resolveTerm(">=")
  val le     =  resolveTerm("<=")
  val eql    =  resolveTerm("==")
  val srl    =  resolveTerm(">>")
  val sll    =  resolveTerm("<<")
  val land   =  resolveTerm("&")
  val lor    =  resolveTerm("|")
  val lxor   =  resolveTerm("^")
  val band   =  resolveTerm("and")
  val bor    =  resolveTerm("or")
  val bnot   =  resolveTerm("not")
  val p      =  resolveTerm("p")
  val print  =  resolveTerm("print")
  val abort  =  resolveTerm("abort")

  // types
  val Int    =  resolveType("Int")
  val Bool   =  resolveType("Bool")
  val String =  resolveType("String")
  val Void   =  resolveType("Void")

object Predef:
  val key = new Dynamic.Key[Predef]("predef")

  def instance: Predef = Dynamic.get(key)

  def load(path: String)(using rp: Reporter): Namespace =
    val namer = new Namer(rp)
    val source = Reporter.source(path)
    val ast = Parser.parse(source)
    namer.transformPredef(ast)
