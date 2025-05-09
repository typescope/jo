package sast

import Types.*
import Flags.*
import Definitions.InfoProvider

import ast.Positions.SourcePosition

/** Symbols refer to definitions (names) in the program.
  *
  * Symbols are stable in the compilation process, while the types of a symbol
  * might change, e.g., due to erasure or encoding of types.
  */
object Symbols:
  /** The information about a symbol
    *
    * During transformation, the type and owner of a symbol may change.
    *
    * The information of a symbol is provided by info providers.
    */
  case class SymInfo(symbol: Symbol, owner: Symbol, tpe: Type):
    assert(owner != null || symbol.flags.is(Flags.NSpace))

  final class Symbol(val name: String, val flags: Flags, val sourcePos: SourcePosition):
    /** TODO: Cache could be introduced to improve performance based on timestamps */
    private def symInfo(using defn: Definitions): SymInfo = defn.info(this)

    /** Do not cache the result from provider
      *
      * The result may change. The cache is done by the provider.
      */
    def info(using Definitions): Type = symInfo.tpe

    def dealiasedInfo(using Definitions): Type = dealias.symInfo.tpe

    def owner(using Definitions): Symbol = symInfo.owner

    def isFunction : Boolean = flags.is(Flags.Fun)
    def isMethod   : Boolean = flags.is(Flags.Method)
    def isType     : Boolean = flags.is(Flags.Type)
    def isPattern  : Boolean = flags.is(Flags.Pattern)
    def isParameter: Boolean = flags.is(Flags.Param)
    def isMutable  : Boolean = flags.is(Flags.Mutable)
    def isField    : Boolean = flags.is(Flags.Field)
    def isSynthetic: Boolean = flags.is(Flags.Synthetic)
    def isAlias    : Boolean = flags.is(Flags.Alias)

    def isNamespace: Boolean = flags.is(Flags.NSpace)

    def isContainer: Boolean = flags.isOneOf(Flags.NSpace | Flags.Section)

    def isTypeParameter: Boolean = flags.isAllOf(Flags.Type | Flags.Param)

    def is(testFlag: Flag) = this.flags.isOneOf(testFlag)
    def isOneOf(testFlags: Flags) = this.flags.isOneOf(testFlags)
    def isAllOf(testFlags: Flags) = this.flags.isAllOf(testFlags)

    def isLocal(using Definitions): Boolean =
      owner != null & !owner.isContainer

    def enclosingContainer(using Definitions): Symbol =
      if this.isContainer then
        this
      else
        // The assertion in the constructor ensures `owner` cannot be null
        owner.enclosingContainer

    def enclosingFunction(using Definitions): Symbol =
      if this.isFunction || this.isMethod then
        this
      else
        // owner can be null, let exception be thrown
        owner.enclosingFunction

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

      this.dealias.info match
        case nsInfo: NameTableInfo => nsInfo.resolveTerm(name).getOrElse(error())
        case _ => error()

    def typeMember(name: String)(using Definitions): Symbol =
      def error() = throw new Exception(s"No type member $name for $this")

      this.dealias.info match
        case nsInfo: NameTableInfo => nsInfo.resolveType(name).getOrElse(error())
        case _ => error()

    def patternMember(name: String)(using Definitions): Symbol =
      def error() = throw new Exception(s"No pattern member $name for $this")

      this.dealias.info match
        case nsInfo: NameTableInfo => nsInfo.resolvePattern(name).getOrElse(error())
        case _ => error()

    /** Return the source symbol of an alias created by import or aliasing */
    def dealias(using Definitions): Symbol =
      if this.isAlias then this.info.as[TypeRef].symbol.dealias else this

    /** Is the current symbol equivalent to a TypeRef or AppliedType to the given symbol  */
    def refers(that: Symbol)(using Definitions): Boolean =
      this == that || this.info.refers(that)

    /** The default function associated with a context parameter */
    def defaultFunction(using Definitions): Symbol =
      assert(this.isAllOf(Flags.Default | Flags.Context))
      this.dealias.owner.termMember(this.name + "$default")

    /** The value function associated with a context parameter */
    def valueFunction(using Definitions): Symbol =
      assert(this.isAllOf(Flags.Default | Flags.Context))
      this.dealias.owner.termMember(this.name + "$value")

    /** The param of an option type associated with a default context parameter.
      *
      * The option param is bound at top-level to `None` if required.
      */
    def optionParam(using Definitions): Symbol =
      assert(this.isAllOf(Flags.Default | Flags.Context))
      this.dealias.owner.termMember(this.name + "$option")

    def fullName(using Definitions): String = this.ownersIterator.foldLeft(this.name):
      (acc, owner) => owner.name + "." + acc

    def toNamedInfo(using Definitions): NamedInfo[Type] = NamedInfo(name, info)

    override def toString() = name

  object Symbol:
    def createSymbol(name: String, flags: Flags, pos: SourcePosition) =
      new Symbol(name, flags, pos)

    def createSymbol
        (name: String, info: Type, flags: Flags, owner: Symbol, pos: SourcePosition)
        (using defn: Definitions)
    : Symbol =

      val sym = new Symbol(name, flags, pos)
      defn.add(sym, owner, info)
      sym

    def create
        (name: String, info: Type, flags: Flags, owner: Symbol, pos: SourcePosition)
        (using ip: InfoProvider)
    : Symbol =

      val sym = new Symbol(name, flags, pos)
      ip.add(sym, owner, info)
      sym
