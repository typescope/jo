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
    List(resolveTerm(name), resolveType(name), resolvePattern(name)).flatMap:
      case None => Nil
      case Some(sym) => sym :: Nil

  def resolveByPath(path: String): List[Symbol] =
    val syms = NameTable.resolveStatic(this, path.split("\\.").toList)
    if syms.isEmpty then
      throw new Exception("Not found: " + path + ", name table " + this.show)
    else
      syms

  def resolveTermByPath(path: String): Symbol =
    resolveByPath(path).filter(!_.isOneOf(Flags.Pattern | Flags.Type)).head

  def resolvePatternByPath(path: String): Symbol =
    resolveByPath(path).filter(_.is(Flags.Pattern)).head

  def resolveTypeByPath(path: String): Symbol =
    resolveByPath(path).filter(_.is(Flags.Type)).head

  def define(sym: Symbol)(using rp: Reporter): Unit =
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
    assert(sym.isPattern, "Expect pattern symbol, found = " + sym)
    defineInTable(sym, termNames)

  def terms: List[Symbol] = termNames.values.toList

  def types: List[Symbol] = typeNames.values.toList

  def patterns: List[Symbol] = patternNames.values.toList

  def show: String =
    "terms: { " + termNames + "}" + "\ntypes: { " + typeNames + "}" + "\npatterns: { " + patternNames + "}"

object NameTable:
  def resolveStatic(nameTable: NameTable, parts: List[String]): List[Symbol] =
    (parts: @unchecked) match
      case name :: Nil =>
        nameTable.resolve(name)

      case name :: rest =>
        nameTable.resolveTerm(name) match
          case Some(sym) =>
            if sym.isContainer then
              val nameTable = sym.info.as[NameTableInfo].nameTable
              resolveStatic(nameTable, rest)
            else
              Nil

          case None =>
            Nil

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
