package sast

import Symbols.*
import Types.*

import reporting.Diagnostics.*
import reporting.Reporter

import scala.collection.mutable

class NameTable(
  termNames: mutable.Map[String, Symbol],
  typeNames: mutable.Map[String, Symbol],
  patternNames: mutable.Map[String, Symbol]):

  def this() = this(mutable.Map.empty, mutable.Map.empty, mutable.Map.empty)

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
    List(resolveTerm(name), resolveType(name), resolvePattern(name)).flatMap(_.getOrElse(Nil))

  def resolvePath(path: String) =
    NameTable.resolvePath(this, path, isType = false)

  def define(sym: Symbol)(using rp: Reporter): Unit =
    val table = getTable(sym)
    table.get(sym.name) match
      case None =>
        table(sym.name) = sym

      case Some(symBefore) =>
        val error = NameTable.DoubleDefinition(symBefore, sym)
        rp.report(error)
  end define

  def terms: List[Symbol] = termNames.values.toList

  def types: List[Symbol] = typeNames.values.toList

  def patterns: List[Symbol] = patternNames.values.toList

  def show: String =
    "terms: { " + termNames + "}" + "\ntypes: { " + typeNames + "}" + "\npatterns: { " + patternNames + "}"

object NameTable:
  def resolvePath(nameTable: NameTable, path: String, isType: Boolean): Symbol =
    resolvePath(nameTable, path.split("\\.").toList, isType) match
      case Some(sym) => sym
      case None => throw new Exception("Not found: " + path)

  def resolvePath(nameTable: NameTable, parts: List[String], isType: Boolean): Option[Symbol] =
    (parts: @unchecked) match
      case name :: Nil =>
        if isType then nameTable.resolveType(name) else nameTable.resolveTerm(name)

      case name :: rest =>
        nameTable.resolveTerm(name).flatMap: sym =>
          if sym.isNamespace then
            val nameTable = sym.info.as[NameTableInfo].nameTable
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
      val lineContent = pos.source.lineContent(pos.startLine)
      val padding = " " * pos.startLineColumn
      val num = if pos.length == 0 then 1 else pos.length
      val pointer = if pos.isOneLine then "^" * num else "^"

      val pos2 = symBefore.sourcePos
      val lineContent2 = pos2.source.lineContent(pos2.startLine)
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
