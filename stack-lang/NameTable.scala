import Symbols.*

import scala.collection.mutable

class NameTable(
  termNames: mutable.Map[String, Symbol],
  typeNames: mutable.Map[String, Symbol]):

  def this() = this(mutable.Map.empty, mutable.Map.empty)

  private def getTable(isType: Boolean) =
    if isType then typeNames else termNames

  def resolveTerm(name: String): Option[Symbol] =
    val table = getTable(isType = false)
    table.get(name)

  def resolveType(name: String): Option[Symbol] =
    val table = getTable(isType = true)
    table.get(name)

  def resolve(name: String, isType: Boolean): Option[Symbol] =
    val table = getTable(isType)
    table.get(name)

  def define(sym: Symbol)(using rp: Reporter): Unit =
    val table = getTable(sym.isType)
    table.get(sym.name) match
      case None =>
        table(sym.name) = sym

      case Some(symBefore) =>
        val error = Diagnostics.DoubleDefinition(symBefore, sym)
        rp.report(error)
  end define
