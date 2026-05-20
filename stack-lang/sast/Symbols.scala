package sast

import Types.*
import Denotations.*
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

  /** A resolved annotation use on a definition.
    *
    * `sym` is the annotation definition symbol.
    * `args` are the literal constant arguments.
    */
  case class Annotation(symbol: Symbol, args: List[Constant])

  enum Universe:
    case Term, Type, Pattern, Container, Annot

    override def toString: String =
      this match
        case Term      => "term"
        case Type      => "type"
        case Pattern   => "pattern"
        case Container => "container"
        case Annot     => "annotation"

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
    * Currently Private does not have a qulifier, which could be a future extension.
    * Without qualifier, the name is private to its owner.
    *
    */
  enum Visibility:
    case Default
    case Private(within: Symbol)

  enum VisibleScope:
    case Global
    case Limit(container: Symbol)

    def contains(other: VisibleScope): Boolean =
      this match
        case Global => true
        case Limit(containerA) =>
          other match
            case Global => false
            case Limit(containerB) => containerB.containedIn(containerA)


  type AnnotationsInfo = (() => List[Annotation]) | List[Annotation]

  sealed abstract class Symbol(
    val name: String,
    val flags: Flags,
    val visibility: Visibility,
    val annotsInfo: AnnotationsInfo,
    val owner: Symbol,
    val sourcePos: SourcePosition):

    assert(owner != null || flags.is(Flags.NSpace), "symbol = " + name)

    lazy val annotations: List[Annotation] =
      annotsInfo match
        case annots: List[Annotation] => annots
        case provider: (() => List[Annotation]) => provider()

    /** Do not cache the result from provider
      *
      * The result may change. The cache is done by the provider.
      */
    def info(using defn: Definitions): Denotation =
      this match
        case container: ContainerSymbol if !this.isAlias => container.nameTable
        case _ => defn.info(this)

    /** The type of this symbol, as a Type. Throws if the symbol is a container. */
    def tpe(using Definitions): Type = info.asType

    /** All symbols that have a ProcType are functions, including top-level
      * functions, methods and pattern predicates
      */
    def isFunction   : Boolean = flags.is(Flags.Fun)
    def isAnnotation : Boolean = flags.is(Flags.Annotation)

    def isMethod   : Boolean = flags.is(Flags.Method)
    def isClass    : Boolean = flags.is(Flags.Class)
    def isInterface: Boolean = flags.is(Flags.Interface)
    def isParameter: Boolean = flags.is(Flags.Param)
    def isMutable  : Boolean = flags.is(Flags.Mutable)
    def isField    : Boolean = flags.is(Flags.Field)
    def isSynthetic: Boolean = flags.is(Flags.Synthetic)
    def isAlias    : Boolean = flags.is(Flags.Alias)

    def isTerm     : Boolean = this.isInstanceOf[TermSymbol]
    def isType     : Boolean = this.isInstanceOf[TypeSymbol]
    def isPattern  : Boolean = this.isInstanceOf[PatternSymbol]
    def isContainer: Boolean = this.isInstanceOf[ContainerSymbol]

    def isNamespace: Boolean = flags.is(Flags.NSpace)

    def isTypeParameter: Boolean = this.isType && flags.is(Flags.Param)

    def isGroundType: Boolean =
      this.isType && this.flags.isOneOf(Flags.Interface | Flags.Class | Flags.Defer | Flags.Param)

    def is(testFlag: Flag) = this.flags.isOneOf(testFlag)
    def isOneOf(testFlags: Flags) = this.flags.isOneOf(testFlags)
    def isAllOf(testFlags: Flags) = this.flags.isAllOf(testFlags)

    def isPrivate = this.visibility.isInstanceOf[Visibility.Private]

    def annotation(annot: Symbol): Option[Annotation] =
      annotations.find(_.symbol == annot)

    def hasAnnotation(annot: Symbol): Boolean =
      annotation(annot).nonEmpty

    /** Whether this symbol is an extension method (has 1 pre-parameter) */
    def isExtensionMethod(using Definitions): Boolean =
      this.info match
        case pt: ProcType => pt.preParamCount == 1
        case _ => false

    def nameTable: NameTable =
      assert(this.isContainer, "Not a container: " + this)

      this match
        case csym: ContainerSymbol  => csym.table
        case tp => throw new Exception("Unexpected type " + tp)

    def classInfo(using Definitions): ClassInfo =
      assert(this.isClass | this.isInterface, "Not a class nor interface: " + this)

      this.info match
        case info: ClassInfo => info
        case tp => throw new Exception("Unexpected type " + tp)

    def typeOperatorInfo(using Definitions): TypeOperatorInfo =
      this.info match
        case info: TypeOperatorInfo => info
        case tp => throw new Exception("Not type operator: " + tp)

    def universe: Universe =
      if this.isTerm then Universe.Term
      else if this.isPattern then Universe.Pattern
      else if this.isType then Universe.Type
      else Universe.Container

    def isTopLevel: Boolean = owner == null || owner.isContainer

    def isLocal: Boolean =
      owner != null && !owner.isContainer && !this.isOneOf(Flags.Method | Flags.Field)

    def enclosingContainer: Symbol =
      if this.isContainer then
        this
      else
        // The assertion in the constructor ensures `owner` cannot be null
        owner.enclosingContainer

    /** The enclosing function of the current symbol
      *
      * Throws exception if the current symbol is not enclosed in a function.
      *
      * A function can be both a term function and a pattern predicate.
      */
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
        case info: NameTable => info.resolveTerm(name).getOrElse(error())
        case info: ClassInfo => info.getMemberSymbol(name).getOrElse(error())
        case _ => error()

    def typeMember(name: String)(using Definitions): Symbol =
      def error() = throw new Exception(s"No type member $name for $this")

      this.info match
        case info: NameTable => info.resolveType(name).getOrElse(error())
        case _ => error()

    def patternMember(name: String)(using Definitions): Symbol =
      def error() = throw new Exception(s"No pattern member $name for $this")

      this.info match
        case info: NameTable => info.resolvePattern(name).getOrElse(error())
        case _ => error()

    def containerMember(name: String)(using Definitions): Symbol =
      def error() = throw new Exception(s"No container member $name for $this")

      this.info match
        case info: NameTable => info.resolveContainer(name).getOrElse(error())
        case _ => error()

    def annotationMember(name: String)(using Definitions): Symbol =
      def error() = throw new Exception(s"No annotation member $name for $this")

      this.info match
        case info: NameTable => info.resolveAnnotation(name).getOrElse(error())
        case _ => error()

    /** The visibile scope of a symbol is defined as follows:
      *
      * 1. The visible scope of a local symbol is its enclosing function.
      *
      * 2. A top-level symbol by default inherits visible scope of its parent.
      *
      * 3. If X is declared as private[N], its visible scope is N. And it is
      * an error if N is bigger than the visible scope of the owner of X.
      *
      * 4. Namespaces have global visible scope.
      */
    def visibleScope: VisibleScope =
      if this.owner != null && owner.isLocal then
        // interface and class methods return true for isLocal
        VisibleScope.Limit(enclosingFunction)

      else if isNamespace then
        VisibleScope.Global

      else
        visibility match
          case Visibility.Private(within) =>
            VisibleScope.Limit(within)

          case _ =>
            if owner == null then VisibleScope.Global
            else owner.visibleScope

    def visibleIn(site: Symbol): Boolean =
      this.visibleScope match
        case VisibleScope.Global => true
        case VisibleScope.Limit(limit) => site.containedIn(limit)

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

    def toNamedInfo(using Definitions): NamedInfo[Type] = NamedInfo(name, info.asType)

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
    annotsInfo: AnnotationsInfo,
    owner: Symbol,
    sourcePos: SourcePosition)
  extends Symbol(name, flags, visibility, annotsInfo, owner, sourcePos)

  final class TermSymbol private[Symbols](
    name: String,
    flags: Flags,
    visibility: Visibility,
    annotsInfo: AnnotationsInfo,
    owner: Symbol,
    sourcePos: SourcePosition)
  extends Symbol(name, flags, visibility, annotsInfo, owner, sourcePos)

  final class PatternSymbol private[Symbols](
    name: String,
    flags: Flags,
    visibility: Visibility,
    annotsInfo: AnnotationsInfo,
    owner: Symbol,
    sourcePos: SourcePosition)
  extends Symbol(name, flags, visibility, annotsInfo, owner, sourcePos)

  final class ContainerSymbol private[Symbols](
    name: String,
    nameTable: NameTable,
    flags: Flags,
    visibility: Visibility,
    annotsInfo: AnnotationsInfo,
    owner: Symbol,
    sourcePos: SourcePosition)
  extends Symbol(name, flags, visibility, annotsInfo, owner, sourcePos):
    val table: NameTable = nameTable

  object TermSymbol:
    def create(name: String, flags: Flags, visibility: Visibility, owner: Symbol, pos: SourcePosition, annotsInfo: AnnotationsInfo): TermSymbol =
      new TermSymbol(name, flags, visibility, annotsInfo, owner, pos)

    def create
        (name: String, info: Type, flags: Flags, visibility: Visibility, owner: Symbol, pos: SourcePosition, annotsInfo: AnnotationsInfo = Nil)
        (using defn: Definitions)
    : Symbol =
      val sym = new TermSymbol(name, flags, visibility, annotsInfo, owner, pos)
      defn.add(sym, info)
      sym

  object TypeSymbol:
    def create(kind: Kind, name: String, flags: Flags, visibility: Visibility, owner: Symbol, pos: SourcePosition, annotsInfo: AnnotationsInfo): TypeSymbol =
      new TypeSymbol(kind, name, flags, visibility, annotsInfo, owner, pos)

    def create
        (kind: Kind, name: String, info: Denotation, flags: Flags, visibility: Visibility, owner: Symbol, pos: SourcePosition, annotsInfo: AnnotationsInfo = Nil)
        (using defn: Definitions)
    : TypeSymbol =
      val sym = new TypeSymbol(kind, name, flags, visibility, annotsInfo, owner, pos)
      defn.add(sym, info)
      sym

  object PatternSymbol:
    def create(name: String, flags: Flags, visibility: Visibility, owner: Symbol, pos: SourcePosition, annotsInfo: AnnotationsInfo): PatternSymbol =
      new PatternSymbol(name, flags, visibility, annotsInfo, owner, pos)

    def create
        (name: String, info: Type, flags: Flags, visibility: Visibility, owner: Symbol, pos: SourcePosition, annotsInfo: AnnotationsInfo = Nil)
        (using defn: Definitions)
    : PatternSymbol =
      val sym = new PatternSymbol(name, flags, visibility, annotsInfo, owner, pos)
      defn.add(sym, info)
      sym

  object ContainerSymbol:
    def create
        (name: String, nameTable: NameTable, flags: Flags, visibility: Visibility, owner: Symbol, pos: SourcePosition, annotsInfo: AnnotationsInfo = Nil)
    : Symbol =
      new ContainerSymbol(name, nameTable, flags, visibility, annotsInfo, owner, pos)
