package sast

import Types.*
import Flags.*

import ast.Positions.{ Source, Span, SourcePosition }

/** Symbols refer to definitions (names) in the program.
  *
  * Symbols are stable in the compilation process, while the types of a symbol
  * might change, e.g., due to erasure or encoding of types.
  */
object Symbols:
  final val debugSymbol = false

  /** The information about a symbol
    *
    * During transformation, the type and owner of a symbol may change.
    *
    * The information of a symbol is provided by info providers.
    */
  case class SymInfo(symbol: Symbol, owner: Symbol, tpe: Type):
    assert(owner != null || symbol.flags.is(Flags.NSpace))

  sealed class Symbol private[Symbols](val name: String, val flags: Flags, val sourcePos: SourcePosition):
    /** TODO: Cache could be introduced to improve performance based on timestamps */
    private def symInfo(using defn: Definitions): SymInfo = defn.info(this)

    /** Do not cache the result from provider
      *
      * The result may change. The cache is done by the provider.
      */
    def info(using Definitions): Type = symInfo.tpe

    def owner(using Definitions): Symbol = symInfo.owner

    /** All symbols that have a ProcType are functions, including top-level
      * functions, methods and pattern predicates
      */
    def isFunction : Boolean = flags.is(Flags.Fun)

    def isMethod   : Boolean = flags.is(Flags.Method)
    def isType     : Boolean = flags.is(Flags.Type)
    def isClass    : Boolean = flags.is(Flags.Class)
    def isPattern  : Boolean = flags.is(Flags.Pattern)
    def isParameter: Boolean = flags.is(Flags.Param)
    def isMutable  : Boolean = flags.is(Flags.Mutable)
    def isField    : Boolean = flags.is(Flags.Field)
    def isSynthetic: Boolean = flags.is(Flags.Synthetic)
    def isAlias    : Boolean = flags.is(Flags.Alias)

    def isConstructor: Boolean = name == Names.Constructor

    def isNamespace: Boolean = flags.is(Flags.NSpace)

    def isContainer: Boolean = flags.isOneOf(Flags.NSpace | Flags.Section)

    def isTypeParameter: Boolean = flags.isAllOf(Flags.Type | Flags.Param)

    def is(testFlag: Flag) = this.flags.isOneOf(testFlag)
    def isOneOf(testFlags: Flags) = this.flags.isOneOf(testFlags)
    def isAllOf(testFlags: Flags) = this.flags.isAllOf(testFlags)

    def classInfo(using Definitions): ClassInfo =
      assert(this.isClass, "Not a class")

      this.info match
        case info: ClassInfo => info
        case TypeLambda(_, info: ClassInfo, _) => info
        case tp => throw new Exception("Unexpected type " + tp.show)

    def isLocal(using Definitions): Boolean =
      owner != null && !owner.isContainer

    def enclosingContainer(using Definitions): Symbol =
      if this.isContainer then
        this
      else
        // The assertion in the constructor ensures `owner` cannot be null
        owner.enclosingContainer

    def enclosingFunction(using Definitions): Symbol =
      if this.isFunction then
        this
      else
        // owner can be null, let exception be thrown
        owner.enclosingFunction

    def containedIn(other: Symbol)(using Definitions): Boolean =
      this == other || (this.owner != null && this.owner.containedIn(other))

    def ownersIterator(using Definitions): Iterator[Symbol] =
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

    /** Is the current symbol equivalent to a StaticRef or AppliedType to the given symbol  */
    def refers(that: Symbol)(using Definitions): Boolean =
      this == that || this.info.refers(that)

    /** The default function associated with a context parameter */
    def defaultFunction(using Definitions): Symbol =
      assert(this.isAllOf(Flags.Default | Flags.Context))
      this.owner.termMember(this.name + "$default")

    /** The value function associated with a context parameter */
    def valueFunction(using Definitions): Symbol =
      assert(this.isAllOf(Flags.Default | Flags.Context))
      this.owner.termMember(this.name + "$value")

    /** The param of an option type associated with a default context parameter.
      *
      * The option param is bound at top-level to `None` if required.
      */
    def optionParam(using Definitions): Symbol =
      assert(this.isAllOf(Flags.Default | Flags.Context))
      this.owner.termMember(this.name + "$option")

    def fullName(using Definitions): String =
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

  final class TypeSymbol(
    val kind: Kind, name: String, flags: Flags, sourcePos: SourcePosition)
  extends Symbol(name, flags | Flags.Type, sourcePos)

  object TypeSymbol:
    def create
        (kind: Kind, name: String, info: Type, flags: Flags, owner: Symbol, pos: SourcePosition)
        (using ip: InfoProvider)
    : TypeSymbol =
      val sym = new TypeSymbol(kind, name, flags, pos)
      ip.add(sym, owner, info)
      sym

    def createSymbol
        (kind: Kind, name: String, info: Type, flags: Flags, owner: Symbol, pos: SourcePosition)
        (using defn: Definitions)
    : TypeSymbol =
      val sym = new TypeSymbol(kind, name, flags, pos)
      defn.add(sym, owner, info)
      sym


  object Symbol:
    /** Create a term or pattern symbol */
    def createSymbol(name: String, flags: Flags, pos: SourcePosition) =
      assert(!flags.is(Flags.Type), "type symbols should be created by `new TypeSymbol`")
      new Symbol(name, flags, pos)

    /** Create a term or pattern symbol */
    def createSymbol
        (name: String, info: Type, flags: Flags, owner: Symbol, pos: SourcePosition)
        (using defn: Definitions)
    : Symbol =
      assert(!flags.is(Flags.Type), "type symbols should be created by `TypeSymbol.createSymbol`")

      val sym = new Symbol(name, flags, pos)
      defn.add(sym, owner, info)
      sym
