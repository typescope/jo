package sast

import Types.*
import Flags.*

import ast.Positions.SourcePosition

/** Symbols refer to definitions (names) in the program.
  *
  * Symbols are stable in the compilation process, while the types of a symbol
  * might change, e.g., due to erasure or encoding of types.
  */
object Symbols:
  /** Provides type to a symbol
    *
    * The provider needs to perform cache. The symbol does not cache.
    *
    * An abstract class is used instead of a type alias to enable runtime
    * type test.
    */
  abstract class InfoProvider extends (Symbol => Type)

  final class Symbol(
    val name: String,
    infoProvider: Type | InfoProvider,
    val flags: Flags,
    val owner: Symbol,        // can be null for top-level namespace symbols
    val sourcePos: SourcePosition):

    assert(owner != null || flags.is(Flags.NSpace))

    /** Do not cache the result from provider
      *
      * The result may change due to
      *
      * - fixed point computation for infering result type of functions
      * - erasure
      *
      * The cache is done by the provider
      */
    def info: Type =
      infoProvider match
        case tp: Type => tp
        case provider: InfoProvider => provider(this)

    def isFunction : Boolean = flags.is(Flags.Fun)
    def isMethod   : Boolean = flags.is(Flags.Method)
    def isValue    : Boolean = flags.is(Flags.Val)
    def isType     : Boolean = flags.is(Flags.Type)
    def isNamespace: Boolean = flags.is(Flags.NSpace)
    def isParameter: Boolean = flags.isAllOf(Flags.Val | Flags.Param)
    def isMutable  : Boolean = flags.isAllOf(Flags.Val | Flags.Mutable)
    def isField    : Boolean = flags.isAllOf(Flags.Val | Flags.Field)

    def isTypeParameter: Boolean = flags.isAllOf(Flags.Type | Flags.Param)

    def isLocal: Boolean = owner != null & !owner.isNamespace

    def is(testFlag: Flag) = this.flags.isOneOf(testFlag)
    def isOneOf(testFlags: Flags) = this.flags.isOneOf(testFlags)
    def isAllOf(testFlags: Flags) = this.flags.isAllOf(testFlags)

    def enclosingNamespace: Symbol =
      if this.isNamespace then
        this
      else
        // The assertion in the constructor ensures `owner` cannot be null
        owner.enclosingNamespace

    def enclosingFunction: Symbol =
      if this.isFunction || this.isMethod then
        this
      else
        // owner can be null, let exception be thrown
        owner.enclosingFunction

    def ownersIterator: Iterator[Symbol] =
      var current = this
      new Iterator[Symbol]:
          def hasNext: Boolean = current.owner != null
          def next: Symbol =
            val res = current.owner
            current = res
            res

    def termMember(name: String): Symbol =
      info match
        case nsInfo: NameTableInfo =>nsInfo.resolveTerm(name)
        case _ => None

    def typeMember(name: String): Symbol =
      info match
        case nsInfo: NameTableInfo =>nsInfo.resolveType(name)
        case _ => None

    /** The default function associated with a context parameter */
    def defaultFunction: Symbol =
      assert(this.isAllOf(Flags.Default | Flags.Context))
      this.owner.termMember(this.name + "$default")

    /** The value function associated with a context parameter */
    def valueFunction: Symbol =
      assert(this.isAllOf(Flags.Default | Flags.Context))
      this.owner.termMember(this.name + "$value")

    /** The param of an option type associated with a default context parameter.
      *
      * The option param is bound at top-level to `None` if required.
      */
    def optionParam: Symbol =
      assert(this.isAllOf(Flags.Default | Flags.Context))
      this.owner.termMember(this.name + "$option")

    def fullName: String = this.ownersIterator.foldLeft(this.name):
      (acc, owner) => owner.name + "." + acc

    def toNamedInfo: NamedInfo[Type] = NamedInfo(name, info)

    override def toString() = name

  object Symbol:
    def createSymbol(name: String, info: Type | InfoProvider, flags: Flags, owner: Symbol, pos: SourcePosition) =
      new Symbol(name, info, flags, owner, pos)

    def createValueSymbol(name: String, tp: Type | InfoProvider, owner: Symbol, pos: SourcePosition) =
      new Symbol(name, tp, Flags.Val, owner, pos)

    def createValueSymbol(name: String, tp: Type | InfoProvider, flags: Flags, owner: Symbol, pos: SourcePosition) =
      new Symbol(name, tp, Flags.Val | flags, owner, pos)

    def createFunSymbol(name: String, info: Type | InfoProvider, owner: Symbol, pos: SourcePosition) =
      new Symbol(name, info, Flags.Fun, owner, pos)

    def createTypeSymbol(name: String, info: Type | InfoProvider, owner: Symbol, pos: SourcePosition) =
      new Symbol(name, info, Flags.Type, owner, pos)

    def createParamSymbol(name: String, tp: Type, owner: Symbol, pos: SourcePosition) =
      new Symbol(name, tp, Flags.Param | Flags.Val, owner, pos)

    def createPatternSymbol(name: String, tp: Type, owner: Symbol, pos: SourcePosition) =
      new Symbol(name, tp, Flags.Pattern, owner, pos)

    def createTypeParamSymbol(name: String, tp: Type | InfoProvider, owner: Symbol, pos: SourcePosition) =
      new Symbol(name, tp, Flags.Param | Flags.Type, owner, pos)

    def createNamespaceSymbol(name: String, info: NameTableInfo, owner: Symbol, pos: SourcePosition, isBranch: Boolean) =
      val flags = if isBranch then Flags.NSpace | Flags.Branch else Flags.NSpace
      new Symbol(name, info, flags, owner, pos)
