package sast

import Symbols.*
import Types.*

import reporting.Diagnostics.*
import reporting.Reporter

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
        val error = NameTable.DoubleDefinition(symBefore, sym)
        rp.report(error)
  end define

object NameTable:
  def resolvePath(nameTable: NameTable, path: String, isType: Boolean): Symbol =
    resolvePath(nameTable, path.split("\\.").toList, isType).get

  private def resolvePath(nameTable: NameTable, parts: List[String], isType: Boolean): Option[Symbol] =
    (parts: @unchecked) match
      case name :: Nil => nameTable.resolve(name, isType)

      case name :: rest =>
        nameTable.resolveTerm(name).flatMap: sym =>
          if sym.isNamespace then
            val nameTable = sym.info.as[NamespaceInfo].nameTable
            resolvePath(nameTable, rest, isType)
          else
            None

  class DoubleDefinition(symBefore: Symbol, symNow: Symbol)
  extends Diagnostic:
    assert(symBefore.name == symNow.name)

    val kind = Kind.Error
    val positioned = true
    val pos = symNow.sourcePos

    override def toString() =
      val message = "Redefinition of " + symBefore.name
      val pos = symNow.sourcePos
      val lineContent = pos.source.readLine(pos.startLine).replaceAll("[\n\r]$", "")
      val padding = " " * pos.startLineColumn
      val num = if pos.length == 0 then 1 else pos.length
      val pointer = if pos.isOneLine then "^" * num else "^"

      val pos2 = symBefore.sourcePos
      val lineContent2 = pos2.source.readLine(pos2.startLine).replaceAll("[\n\r]$", "")
      val num2 = if pos2.length == 0 then 1 else pos2.length
      val padding2 = " " * pos2.startLineColumn
      val pointer2 = if pos2.isOneLine then "^" * num2 else "^"

      s"""|---------- $kind at $pos ---------------
          || $lineContent
          || $padding$pointer
          || $padding$message
          ||
          || The name `${symNow.name}` is already defined at $pos2:
          || $lineContent2
          || $padding2$pointer2""".stripMargin
