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

  /** A scope where the symbols are non-static members of the owner
    *
    * The scope is used for auto-importing members of `this`.
    */
  case PrefixedScope(outer: Scope, table: NameTable, prefix: Symbol, owner: Symbol)

  val table: NameTable

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

  def outerOpt: Option[Scope] =
    this match
      case nsc: NestedScope => Some(nsc.outer)

      case nsc: PrefixedScope => Some(nsc.outer)

      case _: RootScope => None


  def resolveTypeOpt(name: String)(using Definitions): Option[Symbol] = Debug.trace(s"Resolving type $name in scope " + table.show, enable = false):
    table.resolveType(name) match
      case None =>
        this.outerOpt.flatMap: outer =>
          outer.resolveTypeOpt(name)

      case Some(sym)  =>
        Some(sym.dealias)

  def resolveTermOpt(name: String)(using oob: OutOfBand, defn: Definitions): Option[Symbol] = Debug.trace(s"Resolving term $name in scope " + table.show, enable = false):
    table.resolveTerm(name) match
      case None =>
        this.outerOpt.flatMap: outer =>
          outer.resolveTermOpt(name)

      case Some(sym)  =>
        this match
          case sc: PrefixedScope => oob.addKey(Scope.PrefixKey, sc.prefix)
          case _ =>

        Some(sym.dealias)

  def resolvePatternOpt(name: String)(using Definitions): Option[Symbol] = Debug.trace(s"Resolving pattern $name in scope " + table.show, enable = false):
    table.resolvePattern(name) match
      case None =>
        this.outerOpt.flatMap: outer =>
          outer.resolvePatternOpt(name)

      case Some(sym)  =>
        Some(sym.dealias)

  def resolveContainerOpt(name: String)(using Definitions): Option[Symbol] = Debug.trace(s"Resolving container $name in scope " + table.show, enable = false):
    table.resolveContainer(name) match
      case None =>
        this.outerOpt.flatMap: outer =>
          outer.resolveContainerOpt(name)

      case Some(sym)  =>
        Some(sym.dealias)

  def resolveTerm(name: String, pos: SourcePosition)(using Reporter, Definitions, OutOfBand): Symbol =
    resolveTermOpt(name) match
      case Some(sym) => sym
      case None =>
        Reporter.error(s"Undefined term name " + name, pos)
        TermSymbol.create(name, ErrorType, Flags.Synthetic, Visibility.Default, owner, pos)

  def resolveOpt(name: String, universe: Universe)(using Definitions, OutOfBand): Option[Symbol] =
    universe match
      case Universe.Term => resolveTermOpt(name)
      case Universe.Type => resolveTypeOpt(name)
      case Universe.Pattern => resolvePatternOpt(name)
      case Universe.Container => resolveContainerOpt(name)

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
        case Scope.PrefixedScope(outer, _, _, _) => collect(outer)
        case Scope.RootScope(_, _) => () // Stop at root

    collect(this)
    result.toList

object Scope:
  val PrefixKey = new KeyProps.Key[Symbol]("Prefix")
