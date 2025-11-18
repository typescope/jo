package sast

import Types.*
import Flags.*

import ast.Positions.{ Source, Span, SourcePosition }

/** Symbols refer to definitions (names) in the program.
  *
  * Symbols are stable in the compilation process, while the types of a symbol
  * might change, e.g., due to erasure or encoding of types.
  *
  * Names fall into three universes:
  *
  * - term names
  * - type names
  * - pattern names
  */
object Symbols:
  final val debugSymbol = false

  /** The visibility of a symbol
    *
    * Two rules regarding private members:
    *
    *  - A private member may only be selected when the scope owner is within
    *    visibility of the private member.
    *
    *  - A symbol's visibility must be smaller than that of its parent.
    *
    * While class members may have visibility, object members may not.
    *
    * This design does not prevent a library author from exposing a type member
    * of a private section to outer world. This is a feature of the owner-based
    * access control.
    *
    * Currently Private does not have a qulifier, which could be a future extension.
    * Without qualifier, the name is private to its owner.
    *
    */
  enum Visibility:
    case Scope
    case Private

  sealed abstract class Symbol(
    val name: String,
    val flags: Flags,
    val visibility: Visibility,
    val owner: Symbol,
    val sourcePos: SourcePosition):

    assert(owner != null || flags.is(Flags.NSpace), "symbol = " + name)

    /** TODO: Cache could be introduced to improve performance based on timestamps */

    /** Do not cache the result from provider
      *
      * The result may change. The cache is done by the provider.
      */
    def info(using defn: Definitions): Type = defn.info(this)

    /** All symbols that have a ProcType are functions, including top-level
      * functions, methods and pattern predicates
      */
    def isFunction : Boolean = flags.is(Flags.Fun)

    def isMethod   : Boolean = flags.is(Flags.Method)
    def isClass    : Boolean = flags.is(Flags.Class)
    def isParameter: Boolean = flags.is(Flags.Param)
    def isMutable  : Boolean = flags.is(Flags.Mutable)
    def isField    : Boolean = flags.is(Flags.Field)
    def isSynthetic: Boolean = flags.is(Flags.Synthetic)
    def isAlias    : Boolean = flags.is(Flags.Alias)

    def isTerm     : Boolean = this.isInstanceOf[TermSymbol]
    def isType     : Boolean = this.isInstanceOf[TypeSymbol]
    def isPattern  : Boolean = this.isInstanceOf[PatternSymbol]

    def isConstructor: Boolean = name == Names.Constructor

    def isNamespace: Boolean = flags.is(Flags.NSpace)
    def isContainer: Boolean = flags.isOneOf(Flags.NSpace | Flags.Section)

    def isTypeParameter: Boolean = this.isType && flags.is(Flags.Param)

    def is(testFlag: Flag) = this.flags.isOneOf(testFlag)
    def isOneOf(testFlags: Flags) = this.flags.isOneOf(testFlags)
    def isAllOf(testFlags: Flags) = this.flags.isAllOf(testFlags)

    def isPrivate = this.visibility == Visibility.Private

    def classInfo(using Definitions): ClassInfo =
      assert(this.isClass, "Not a class")

      this.info match
        case info: ClassInfo => info
        case TypeLambda(_, info: ClassInfo, _) => info
        case tp => throw new Exception("Unexpected type " + tp.show)

    def isLocal: Boolean =
      owner != null && !owner.isContainer

    def enclosingContainer: Symbol =
      if this.isContainer then
        this
      else
        // The assertion in the constructor ensures `owner` cannot be null
        owner.enclosingContainer

    def enclosingFunction: Symbol =
      if this.isFunction then
        this
      else
        // owner can be null, let exception be thrown
        owner.enclosingFunction

    def containedIn(other: Symbol): Boolean =
      this == other || (this.owner != null && this.owner.containedIn(other))

    def ownersIterator: Iterator[Symbol] =
      var current = this
      new Iterator[Symbol]:
          def hasNext: Boolean = current.owner != null
          def next: Symbol =
            val res = current.owner
            current = res
            res

    def termMember(name: String)(using Definitions): Symbol =
      def error() = throw new Exception(s"No term member $name for $this")

      this.info match
        case info: ContainerInfo => info.resolveTerm(name).getOrElse(error())
        case _ => error()

    def typeMember(name: String)(using Definitions): Symbol =
      def error() = throw new Exception(s"No type member $name for $this")

      this.info match
        case info: ContainerInfo => info.resolveType(name).getOrElse(error())
        case _ => error()

    def patternMember(name: String)(using Definitions): Symbol =
      def error() = throw new Exception(s"No pattern member $name for $this")

      this.info match
        case info: ContainerInfo => info.resolvePattern(name).getOrElse(error())
        case _ => error()

    /** Return the source symbol of an alias created by import or aliasing
      *
      * Invariant: It is important that we do not have cycles in aliases, which
      * is guaranteed by disallowing creating an alias of another alias.
      *
      * Warning: Don't call this method. Dealiasing is done systematically
      * during type checking in name resolution. Later phases can assume that
      * there are no intermediate aliases.
      */
    def dealias(using Definitions): Symbol =
      if this.isAlias then this.info.as[StaticRef].symbol.dealias else this

    /** The default function associated with a context parameter */
    def defaultFunction(using Definitions): Symbol =
      this.owner.termMember(this.name + "$default")

    def fullName: String =
      if isLocal then
        this.name
      else
        this.ownersIterator.foldLeft(this.name):
          (acc, owner) => owner.name + "." + acc

    def toNamedInfo(using Definitions): NamedInfo[Type] = NamedInfo(name, info)

    def span: Span = sourcePos.span

    def source: Source = sourcePos.source

    override def toString() =
      if Symbols.debugSymbol then
        name + "#" + System.identityHashCode(this)
      else
        name

    def asTypeSymbol: TypeSymbol = this.asInstanceOf[TypeSymbol]
  end Symbol

  final class TypeSymbol private[Symbols](
    val kind: Kind,
    name: String,
    flags: Flags,
    visibility: Visibility,
    owner: Symbol,
    sourcePos: SourcePosition)
  extends Symbol(name, flags, visibility, owner, sourcePos)

  final class TermSymbol private[Symbols](
    name: String,
    flags: Flags,
    visibility: Visibility,
    owner: Symbol,
    sourcePos: SourcePosition)
  extends Symbol(name, flags, visibility, owner, sourcePos)

  final class PatternSymbol private[Symbols](
    name: String,
    flags: Flags,
    visibility: Visibility,
    owner: Symbol,
    sourcePos: SourcePosition)
  extends Symbol(name, flags, visibility, owner, sourcePos)

  object TermSymbol:
    def create(name: String, flags: Flags, visibility: Visibility, owner: Symbol, pos: SourcePosition): TermSymbol =
      new TermSymbol(name, flags, visibility, owner, pos)

    def create
        (name: String, info: Type, flags: Flags, visibility: Visibility, owner: Symbol, pos: SourcePosition)
        (using defn: Definitions)
    : Symbol =
      val sym = new TermSymbol(name, flags, visibility, owner, pos)
      defn.add(sym, info)
      sym

  object TypeSymbol:
    def create(kind: Kind, name: String, flags: Flags, visibility: Visibility, owner: Symbol, pos: SourcePosition): TypeSymbol =
      new TypeSymbol(kind, name, flags, visibility, owner, pos)

    def create
        (kind: Kind, name: String, info: Type, flags: Flags, visibility: Visibility, owner: Symbol, pos: SourcePosition)
        (using defn: Definitions)
    : TypeSymbol =
      val sym = new TypeSymbol(kind, name, flags, visibility, owner, pos)
      defn.add(sym, info)
      sym

  object PatternSymbol:
    def create(name: String, flags: Flags, visibility: Visibility, owner: Symbol, pos: SourcePosition): PatternSymbol =
      new PatternSymbol(name, flags, visibility, owner, pos)

    def create
        (name: String, info: Type, flags: Flags, visibility: Visibility, owner: Symbol, pos: SourcePosition)
        (using defn: Definitions)
    : PatternSymbol =
      val sym = new PatternSymbol(name, flags, visibility, owner, pos)
      defn.add(sym, info)
      sym
