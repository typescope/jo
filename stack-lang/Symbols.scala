import Types.*
import Flags.*
import Positions.SourcePosition

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
    val sourcePos: SourcePosition):

    /** Do not cache the result from provider
      *
      * The result may change due to
      *
      * - fixed point computation
      * - type inference
      * - erasure
      *
      * The cache is done by the provider
      */
    def info: Type =
      infoProvider match
        case tp: Type => tp
        case provider: InfoProvider => provider(this)

    def isPrimitive: Boolean = flags.is(Flags.Prim)
    def isFunction : Boolean = flags.is(Flags.Fun)
    def isValue    : Boolean = flags.is(Flags.Val)
    def isType     : Boolean = flags.is(Flags.Type)
    def isLocal    : Boolean = flags.is(Flags.Local)
    def isParameter: Boolean = flags.isAllOf(Flags.Val | Flags.Param)
    def isMutable  : Boolean = flags.isAllOf(Flags.Val | Flags.Mutable)

    def isOneOf(testFlags: Flags) = this.flags.isOneOf(testFlags)
    def isAllOf(testFlags: Flags) = this.flags.isAllOf(testFlags)

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

    def createParamSymbol(name: String, tp: Type | InfoProvider, pos: SourcePosition) =
      new Symbol(name, tp, Flags.Param | Flags.Val | Flags.Local, pos)
