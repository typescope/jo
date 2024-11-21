import Types.*
import Flags.*
import Positions.SourcePosition

import scala.collection.mutable

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

  class NamespaceInfo:
    private val termNames: mutable.Map[String, Symbol] = mutable.Map.empty
    private val typeNames: mutable.Map[String, Symbol] = mutable.Map.empty

    private def getTable(isType: Boolean) =
      if isType then typeNames else termNames

    def resolveTerm(name: String): Option[Symbol] =
      val table = getTable(isType = false)
      table.get(name)

    def resolveType(name: String): Option[Symbol] =
      val table = getTable(isType = true)
      table.get(name)

    def define(sym: Symbol)(using Reporter): Unit =
      val table = getTable(sym.isType)
      table.get(sym.name) match
        case None =>
          table(sym.name) = sym

        case Some(sym) =>
          Reporter.error(sym.name + " is already bound", sym.sourcePos)
    end define
  end NamespaceInfo

  final class Symbol(
    val name: String,
    infoProvider: Type | InfoProvider | NamespaceInfo,
    val flags: Flags,
    val sourcePos: SourcePosition):

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
        case nspace: NamespaceInfo => throw new Exception("Namespace does not have info")

    def namespace: NamespaceInfo =
      infoProvider match
        case nspace: NamespaceInfo => nspace
        case _ => throw new Exception("Symbol is not a namespace")

    def isPrimitive: Boolean = flags.is(Flags.Prim)
    def isFunction : Boolean = flags.is(Flags.Fun)
    def isValue    : Boolean = flags.is(Flags.Val)
    def isType     : Boolean = flags.is(Flags.Type)
    def isNamespace: Boolean = flags.is(Flags.NSpace)
    def isLocal    : Boolean = flags.is(Flags.Local)
    def isParameter: Boolean = flags.isAllOf(Flags.Val | Flags.Param)
    def isMutable  : Boolean = flags.isAllOf(Flags.Val | Flags.Mutable)

    def isTypeParameter: Boolean = flags.isAllOf(Flags.Type | Flags.Param)

    def isOneOf(testFlags: Flags) = this.flags.isOneOf(testFlags)
    def isAllOf(testFlags: Flags) = this.flags.isAllOf(testFlags)

    def toNamedInfo: NamedInfo[Type] = NamedInfo(name, info)

    override def toString() = name

  object Symbol:
    def createValueSymbol(name: String, tp: Type | InfoProvider, pos: SourcePosition) =
      new Symbol(name, tp, Flags.Val, pos)

    def createValueSymbol(name: String, tp: Type | InfoProvider, flags: Flags, pos: SourcePosition) =
      new Symbol(name, tp, Flags.Val | flags, pos)

    def createFunSymbol(name: String, info: Type | InfoProvider, pos: SourcePosition) =
      new Symbol(name, info, Flags.Fun, pos)

    def createFunSymbol(name: String, info: Type | InfoProvider, flags: Flags, pos: SourcePosition) =
      new Symbol(name, info, Flags.Fun | flags, pos)

    def createTypeSymbol(name: String, info: Type | InfoProvider, pos: SourcePosition) =
      new Symbol(name, info, Flags.Type, pos)

    def createParamSymbol(name: String, tp: Type, pos: SourcePosition) =
      new Symbol(name, tp, Flags.Param | Flags.Val | Flags.Local, pos)

    def createTypeParamSymbol(name: String, tp: Type | InfoProvider, pos: SourcePosition) =
      new Symbol(name, tp, Flags.Param | Flags.Type, pos)

    def createNamespaceSymbol(name: String, info: NamespaceInfo, pos: SourcePosition, isBranch: Boolean) =
      val flags = if isBranch then Flags.NSpace | Flags.Branch else Flags.NSpace
      new Symbol(name, info, flags, pos)
