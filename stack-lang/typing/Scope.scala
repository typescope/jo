package typing

import sast.*
import sast.Symbols.*
import sast.Types.*

import ast.Positions.*
import common.Debug
import common.KeyProps
import common.OutOfBand
import reporting.Reporter


enum Scope:
  case RootScope(table: NameTable, owner: Symbol)

  /** A nested scope will go from inner to outer scopes in resolving names  */
  case NestedScope(outer: Scope, table: NameTable, owner: Symbol)

  /** In a local pattern scope, resolving pattern names will ignore pattern value symbols from outer scopes */
  case LocalPatternScope(outer: Scope, table: NameTable, owner: Symbol)

  /** A scope where the symbols are non-static members of the owner
    *
    * The scope is used for auto-importing members of `this`.
    */
  case PrefixedScope(outer: Scope, table: NameTable, prefix: Symbol, owner: Symbol)

  protected val table: NameTable

  /** The owner symbol of the current scope
    *
    * It can be null for top-level scopes
    */
  val owner: Symbol

  def fresh(): Scope =
    new Scope.NestedScope(this, new NameTable, owner)

  def freshLocalPatternScope(): Scope =
    new Scope.LocalPatternScope(this, new NameTable, owner)

  def freshPrefixedScope(prefix: Symbol, owner: Symbol): Scope =
    new Scope.PrefixedScope(this, new NameTable, prefix, owner)

  def fresh(owner: Symbol): Scope =
    new Scope.NestedScope(this, new NameTable, owner)

  def fresh(owner: Symbol, nameTable: NameTable): Scope =
    new Scope.NestedScope(this, nameTable, owner)

  def resolveType(name: String)(using Definitions): Option[Symbol] = Debug.trace(s"Resolving type $name in scope " + table.show, enable = false):
    table.resolveType(name) match
      case None =>
        this match
          case nsc: NestedScope => nsc.outer.resolveType(name)
          case nsc: PrefixedScope => nsc.outer.resolveType(name)
          case nsc: LocalPatternScope => nsc.outer.resolveType(name)
          case _ => None

      case Some(sym)  => Some(sym.dealias)

  def resolveTerm(name: String)(using oob: OutOfBand, defn: Definitions): Option[Symbol] = Debug.trace(s"Resolving term $name in scope " + table.show, enable = false):
    table.resolveTerm(name) match
      case None =>
        this match
          case nsc: NestedScope => nsc.outer.resolveTerm(name)
          case nsc: PrefixedScope => nsc.outer.resolveTerm(name)
          case nsc: LocalPatternScope => nsc.outer.resolveTerm(name)
          case _ => None

      case Some(sym)  =>
        this match
          case sc: PrefixedScope => oob.addKey(Scope.PrefixKey, sc.prefix)
          case _ =>

        Some(sym.dealias)

  def resolvePattern(name: String)(using Definitions): Option[Symbol] = Debug.trace(s"Resolving pattern $name in scope " + table.show, enable = false):
    table.resolvePattern(name) match
      case None =>
        this match
          case nsc: NestedScope => nsc.outer.resolvePattern(name)
          case nsc: PrefixedScope => nsc.outer.resolvePattern(name)
          case nsc: LocalPatternScope =>
            // The condition should be refined to only allow pattern
            // predicates that do not capture pattern variables once we enable
            // nesting a pattern predicate inside another predicate.
            //
            // The only reason to access an outer pattern variable is to
            // create binding --- use of them in terms is always allowed.
            //
            // Therefore, whenever we do not perform occur checks for a
            // pattern variable, the binding of the variable should be
            // disallowed. It means the pattern variable should not be visible
            // in the scope.
            nsc.outer.resolvePattern(name) match
              case res @ Some(sym) if sym.isFunction => res
              case _ => None
          case _ => None

      case Some(sym)  => Some(sym.dealias)

  def resolveTerm(name: String, pos: SourcePosition)(using Reporter, Definitions, OutOfBand): Symbol =
    resolveTerm(name) match
      case Some(sym) => sym
      case None =>
        Reporter.error(s"Undefined term name " + name, pos)
        Symbol.createSymbol(name, ErrorType, Flags.Synthetic, Visibility.Scope, owner, pos)

  def resolveType(name: String, pos: SourcePosition)(using Reporter, Definitions): Symbol =
    resolveType(name) match
      case Some(sym) => sym
      case None =>
        Reporter.error(s"Undefined type name " + name, pos)
        Symbol.createSymbol(name, ErrorType, Flags.Synthetic, Visibility.Scope, owner, pos)

  def resolvePattern(name: String, pos: SourcePosition)(using Reporter, Definitions): Symbol =
    resolvePattern(name) match
      case Some(sym) => sym
      case None =>
        Reporter.error(s"Undefined pattern name " + name, pos)
        Symbol.createSymbol(name, ErrorType, Flags.Synthetic, Visibility.Scope, owner, pos)

  def define(sym: Symbol)(using Reporter): Unit =
    table.define(sym)

  def definePatternAsTerm(sym: Symbol)(using Reporter): Unit =
    table.definePatternAsTerm(sym)

  def autos: Seq[Symbol] = table.autos

object Scope:
  val PrefixKey = new KeyProps.Key[Symbol]("Prefix")
