package sast

import Symbols.*
import Types.*

import reporting.Diagnostics.*
import reporting.Reporter

import scala.collection.mutable

class NameTable(
  termNames: mutable.Map[String, Symbol],
  typeNames: mutable.Map[String, Symbol],
  patternNames: mutable.Map[String, Symbol],
  autoSymbols: mutable.ArrayBuffer[Symbol]):

  private var frozen: Boolean = false

  def freeze(): this.type =
    frozen = true
    this

  def this() = this(mutable.Map.empty, mutable.Map.empty, mutable.Map.empty, new mutable.ArrayBuffer)

  private def getTable(sym: Symbol) =
    if sym.isType then typeNames
    else if sym.isPattern then patternNames
    else termNames

  def resolveTerm(name: String): Option[Symbol] =
    termNames.get(name)

  def resolveType(name: String): Option[Symbol] =
    typeNames.get(name)

  def resolvePattern(name: String): Option[Symbol] =
    patternNames.get(name)

  def resolve(name: String): List[Symbol] =
    List(resolveTerm(name), resolveType(name), resolvePattern(name)).flatMap:
      case None => Nil
      case Some(sym) => sym :: Nil

  def resolveByPath(path: String)(using Definitions): List[Symbol] =
    val syms = NameTable.resolveStatic(this, path.split("\\.").toList)
    if syms.isEmpty then
      throw new Exception("Not found: " + path + ", name table " + this.show)
    else
      syms

  def resolveTermByPath(path: String)(using Definitions): Symbol =
    resolveByPath(path).filter(!_.isOneOf(Flags.Pattern | Flags.Type)).head

  def resolvePatternByPath(path: String)(using Definitions): Symbol =
    resolveByPath(path).filter(_.is(Flags.Pattern)).head

  def resolveTypeByPath(path: String)(using Definitions): Symbol =
    resolveByPath(path).filter(_.is(Flags.Type)).head

  def define(sym: Symbol)(using rp: Reporter): Unit =
    assert(!frozen, "Name table is frozen")

    val table = getTable(sym)
    defineInTable(sym, table)

    if sym.is(Flags.Auto) then
      defineAuto(sym)

  def autos: Seq[Symbol] = autoSymbols.toList

  private def defineAuto(sym: Symbol): Unit =
    autoSymbols += sym

  private def defineInTable(sym: Symbol, table: mutable.Map[String, Symbol])(using rp: Reporter): Unit =
    table.get(sym.name) match
      case None =>
        table(sym.name) = sym

      case Some(symBefore) =>
        val error = NameTable.DoubleDefinition(symBefore, sym)
        rp.report(error)
  end defineInTable

  def definePatternAsTerm(sym: Symbol)(using rp: Reporter): Unit =
    assert(!frozen, "Name table is frozen")
    assert(sym.isPattern, "Expect pattern symbol, found = " + sym)

    defineInTable(sym, termNames)

  def terms: List[Symbol] = termNames.values.toList

  def types: List[Symbol] = typeNames.values.toList

  def patterns: List[Symbol] = patternNames.values.toList

  def members: List[Symbol] = terms ++ types ++ patterns

  def show: String =
    "terms: { " + termNames + "}" + "\ntypes: { " + typeNames + "}" + "\npatterns: { " + patternNames + "}"

object NameTable:
  def resolveStatic(nameTable: NameTable, parts: List[String])(using Definitions): List[Symbol] =
    (parts: @unchecked) match
      case name :: Nil =>
        nameTable.resolve(name)

      case name :: rest =>
        nameTable.resolveTerm(name) match
          case Some(sym) =>
            if sym.isContainer then
              val nameTable = sym.dealias.info.as[ContainerInfo].nameTable
              resolveStatic(nameTable, rest)
            else
              Nil

          case None =>
            Nil

  class DoubleDefinition(symBefore: Symbol, symNow: Symbol)
  extends DoublePositionedReport:
    assert(symBefore.name == symNow.name)

    val kind = Kind.Error

    val pos1 = symNow.sourcePos
    val pos2 = symBefore.sourcePos

    val message1 = "Redefinition of " + symBefore.name
    val message2 = s"The name `${symNow.name}` is already defined at $pos2"
