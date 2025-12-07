package sast

import Symbols.*

import reporting.Diagnostics.*
import reporting.Reporter

import scala.collection.mutable

class NameTable(
  containerNames: mutable.Map[String, Symbol],
  termNames: mutable.Map[String, Symbol],
  typeNames: mutable.Map[String, Symbol],
  patternNames: mutable.Map[String, Symbol]):

  private var frozen: Boolean = false

  def freeze(): this.type =
    frozen = true
    this

  def this() = this(mutable.Map.empty, mutable.Map.empty, mutable.Map.empty, mutable.Map.empty)

  private def getTable(sym: Symbol) =
    if sym.isTerm then termNames
    else if sym.isType then typeNames
    else if sym.isPattern then patternNames
    else containerNames

  def resolveTerm(name: String): Option[Symbol] =
    termNames.get(name)

  def resolveContainer(name: String): Option[Symbol] =
    containerNames.get(name)

  def resolveType(name: String): Option[Symbol] =
    typeNames.get(name)

  def resolvePattern(name: String): Option[Symbol] =
    patternNames.get(name)

  def resolve(name: String): List[Symbol] =
    List(resolveTerm(name), resolveType(name), resolvePattern(name)).flatMap:
      case None => Nil
      case Some(sym) => sym :: Nil

  def resolve(name: String, universe: Universe): Option[Symbol] =
    universe match
      case Universe.Term => resolveTerm(name)
      case Universe.Type => resolveType(name)
      case Universe.Pattern => resolvePattern(name)
      case Universe.Container => resolveContainer(name)

  def define(sym: Symbol)(using rp: Reporter): Unit =
    assert(!frozen, "Name table is frozen")

    val table = getTable(sym)
    defineInTable(sym, table)

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

  def containers: List[Symbol] = containerNames.values.toList

  /** For printing only */
  def members: List[Symbol] = terms ++ types ++ patterns

  def show: String =
    "terms: { " + termNames + "}\n"
    + "types: { " + typeNames + "}\n"
    + "patterns: { " + patternNames + "}\n"
    + "containers: { " + containerNames + "}\n"

object NameTable:
  class DoubleDefinition(symBefore: Symbol, symNow: Symbol)
  extends DoublePositionedReport:
    assert(symBefore.name == symNow.name)

    val kind = Kind.Error

    val pos1 = symNow.sourcePos
    val pos2 = symBefore.sourcePos

    val message1 = "Redefinition of " + symBefore.name
    val message2 = s"The name `${symNow.name}` is already defined at $pos2"
