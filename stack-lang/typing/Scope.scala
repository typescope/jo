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

  /** A scope imported via "Container >" at expression start -- only accessible symbols are imported */
  case ImportedScope(outer: Scope, table: NameTable, owner: Symbol)

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

  def freshPrefixedScope(prefix: Symbol, owner: Symbol): Scope =
    new Scope.PrefixedScope(this, new NameTable, prefix, owner)

  def fresh(owner: Symbol): Scope =
    new Scope.NestedScope(this, new NameTable, owner)

  def fresh(owner: Symbol, nameTable: NameTable): Scope =
    new Scope.NestedScope(this, nameTable, owner)

  def freshImportedScope(owner: Symbol, nameTable: NameTable): Scope =
    new Scope.ImportedScope(this, nameTable, owner)

  def resolveType(name: String)(using Definitions): Option[Symbol] = Debug.trace(s"Resolving type $name in scope " + table.show, enable = false):
    table.resolveType(name) match
      case None =>
        this match
          case nsc: NestedScope => nsc.outer.resolveType(name)
          case nsc: ImportedScope => nsc.outer.resolveType(name)
          case nsc: PrefixedScope => nsc.outer.resolveType(name)
          case _ => None

      case Some(sym)  =>
        this match
          case ic: ImportedScope => if !sym.visibleIn(ic.owner) then return None
          case _ =>

        Some(sym.dealias)

  def resolveTerm(name: String)(using oob: OutOfBand, defn: Definitions): Option[Symbol] = Debug.trace(s"Resolving term $name in scope " + table.show, enable = false):
    table.resolveTerm(name) match
      case None =>
        this match
          case nsc: NestedScope => nsc.outer.resolveTerm(name)
          case nsc: ImportedScope => nsc.outer.resolveTerm(name)
          case nsc: PrefixedScope => nsc.outer.resolveTerm(name)
          case _ => None

      case Some(sym)  =>
        this match
          case sc: PrefixedScope => oob.addKey(Scope.PrefixKey, sc.prefix)
          case ic: ImportedScope => if !sym.visibleIn(ic.owner) then return None
          case _ =>

        Some(sym.dealias)

  def resolvePattern(name: String)(using Definitions): Option[Symbol] = Debug.trace(s"Resolving pattern $name in scope " + table.show, enable = false):
    table.resolvePattern(name) match
      case None =>
        this match
          case nsc: NestedScope => nsc.outer.resolvePattern(name)
          case nsc: PrefixedScope => nsc.outer.resolvePattern(name)
          case nsc: ImportedScope => nsc.outer.resolvePattern(name)
          case _ => None

      case Some(sym)  =>
        this match
          case ic: ImportedScope => if !sym.visibleIn(ic.owner) then return None
          case _ =>

        Some(sym.dealias)

  def resolveContainer(name: String)(using Definitions): Option[Symbol] = Debug.trace(s"Resolving container $name in scope " + table.show, enable = false):
    table.resolveContainer(name) match
      case None =>
        this match
          case nsc: NestedScope => nsc.outer.resolveContainer(name)
          case nsc: ImportedScope => nsc.outer.resolveContainer(name)
          case nsc: PrefixedScope => nsc.outer.resolveContainer(name)
          case _ => None

      case Some(sym)  =>
        this match
          case ic: ImportedScope => if !sym.visibleIn(ic.owner) then return None
          case _ =>

        Some(sym.dealias)

  def resolveTerm(name: String, pos: SourcePosition)(using Reporter, Definitions, OutOfBand): Symbol =
    resolveTerm(name) match
      case Some(sym) => sym
      case None =>
        Reporter.error(s"Undefined term name " + name, pos)
        TermSymbol.create(name, ErrorType, Flags.Synthetic, Visibility.Default, owner, pos)

  def resolveType(name: String, pos: SourcePosition)(using Reporter, Definitions): Symbol =
    resolveType(name) match
      case Some(sym) => sym
      case None =>
        Reporter.error(s"Undefined type name " + name, pos)
        TermSymbol.create(name, ErrorType, Flags.Synthetic, Visibility.Default, owner, pos)

  def resolvePattern(name: String, pos: SourcePosition)(using Reporter, Definitions): Symbol =
    resolvePattern(name) match
      case Some(sym) => sym
      case None =>
        Reporter.error(s"Undefined pattern name " + name, pos)
        PatternSymbol.create(name, ErrorType, Flags.Synthetic, Visibility.Default, owner, pos)

  def resolveContainer(name: String, pos: SourcePosition)(using Reporter, Definitions): Symbol =
    resolvePattern(name) match
      case Some(sym) => sym
      case None =>
        Reporter.error(s"Undefined container name " + name, pos)
        TermSymbol.create(name, ErrorType, Flags.Synthetic, Visibility.Default, owner, pos)

  def resolve(name: String, universe: Universe)(using Definitions, OutOfBand): Option[Symbol] =
    universe match
      case Universe.Term => resolveTerm(name)
      case Universe.Type => resolveType(name)
      case Universe.Pattern => resolvePattern(name)
      case Universe.Container => resolveContainer(name)

  def resolve(name: String, universe: Universe, pos: SourcePosition)(using Reporter, Definitions, OutOfBand): Symbol =
    universe match
      case Universe.Term => resolveTerm(name, pos)
      case Universe.Type => resolveType(name, pos)
      case Universe.Pattern => resolvePattern(name, pos)
      case Universe.Container => resolveContainer(name, pos)

  def define(sym: Symbol)(using Reporter): Unit =
    table.define(sym)

  def definePatternAsTerm(sym: Symbol)(using rp: Reporter): Unit =
    table.definePatternAsTerm(sym)

  /** Collect all local auto symbols from the scope chain.
    *
    * Traverses scopes collecting autos until a non-local scope is reached.
    * A scope is non-local if its owner is a container (top-level definitions).
    */
  def collectLocalAutos: List[Symbol] =
    import scala.collection.mutable.ArrayBuffer
    val result = ArrayBuffer[Symbol]()

    def collect(sc: Scope): Unit =
      // Stop when we reach a scope owned by a container (non-local)
      if sc.owner != null && sc.owner.isContainer then
        return

      // Collect autos from current scope
      result ++= sc.table.autos

      // Continue to outer scope
      sc match
        case Scope.NestedScope(outer, _, _) => collect(outer)
        case Scope.ImportedScope(outer, _, _) => collect(outer)
        case Scope.PrefixedScope(outer, _, _, _) => collect(outer)
        case Scope.RootScope(_, _) => () // Stop at root

    collect(this)
    result.toList

object Scope:
  val PrefixKey = new KeyProps.Key[Symbol]("Prefix")
