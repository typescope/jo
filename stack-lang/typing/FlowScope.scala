package typing

import sast.*
import sast.Symbols.Symbol

import ast.Positions.*
import reporting.Reporter

import scala.collection.mutable

/** A flat scope for flow typing of patterns
  *
  * In a flow scope, resolving pattern names will ignore pattern value symbols
  * from outer scopes.
  *
  * Meanwhile, nested scopes of a flow scope will have no access to pattern
  * variables defined in the flow scope.
  *
  * This guarantees local reasoning for flow typing.
  */
class FlowScope(val outer: Scope):
  /** Term names promoted from bound pattern names are available in nested scopes.
    *
    * Nested scopes will inherit from this scope while ignoring pattern names.
    */
  private var promotedNames = Set.empty[Symbol]

  private def promotedTermScope()(using Reporter): Scope =
    val nameTable = new NameTable
    val sc = outer.fresh(outer.owner, nameTable)
    promotedNames.foreach { sym => sc.definePatternAsTerm(sym) }
    sc

  /** Pattern names defined in the flow scope
    *
    * Pattern names are not available in nested flow scopes.
    */
  private val patternNameTable = mutable.Map.empty[String, Symbol]

  /** A normal nested scope
    *
    * The nested scope has no access to the pattern names in current flow scope.
    */
  def fresh()(using Reporter): Scope = promotedTermScope()

  def owner: Symbol = outer.owner

  def resolvePatternVariable(name: String): Option[Symbol] =
    patternNameTable.get(name)

  def define(sym: Symbol): Unit =
    assert(sym.isPattern && !sym.is(Flags.Fun), "Not a pattern variable: " + sym)
    assert(!patternNameTable.contains(sym.name), "Already defined variable: " + sym)
    patternNameTable(sym.name) = sym

  /** Promote a bound pattern variable to be visible in the term universe
    *
    * A promoted pattern variable is definitely bound.
    */
  def promote(sym: Symbol, pos: SourcePosition)(using Reporter): Unit =
    if promotedNames.contains(sym) then
      Reporter.error(s"The pattern variable $sym is already bound", pos)

    else
      promotedNames = promotedNames + sym

  def isPromoted(sym: Symbol): Boolean = promotedNames.contains(sym)

  def promotedSet(): Set[Symbol] = promotedNames

  /** Reset the promoted set and return the current promoted set
    *
    * Reset is used in checking branches of OR-patterns.
    */
  def resetPromotedSet(newSet: Set[Symbol]): Set[Symbol] =
    val current = promotedNames
    promotedNames = newSet
    current

  def show: String = promotedNames.toString
