package native

import phases.BackendTyping

import sast.*
import sast.Symbols.Symbol
import sast.Types.*

/** How native represents a value in a calling sequence: a tagged (boxed) word
  * or a raw machine word. Lambda types keep their structure, because a
  * lambda's own parameters and result are passed by the same rules and can
  * differ in tagging independently of the lambda value itself.
  */
enum TagRepr:
  case Word(tagged: Boolean)
  case Fn(params: List[TagRepr], result: TagRepr)

/** Native's calling sequence distinguishes only tagged from untagged words —
  * every reference is passed identically regardless of its declared type — so
  * both of `BackendTyping`'s questions reduce to agreeing on tagging. They
  * coincide here, which is why the distinction between them stayed invisible
  * until the JVM backend existed.
  */
class NativeTyping(isTagged: Type => Boolean)(using Definitions) extends BackendTyping:
  /** Symmetric: a tagged word and an untagged one need a conversion in either
    * direction, and two words of the same tagging need none regardless of
    * their declared types.
    */
  def compatible(a: Type, b: Type): Boolean =
    isTagged(a) == isTagged(b)

  def callCompatible(iface: ProcType, impl: ProcType): Boolean =
    signature(iface) == signature(impl)

  /** Native looks a bridge up in its interface table by this suffixed name
    * (see `native/runtime/InterfaceTable.scala`), so the bridge and the
    * natural method coexist under distinct names.
    */
  def bridgeName(ifaceMethod: Symbol): String =
    ifaceMethod.name + Names.BridgeSuffix

  private def signature(pt: ProcType): List[TagRepr] =
    (pt.paramTypes ++ pt.autoTypes :+ pt.resultType).map(repr)

  private def repr(tp: Type): TagRepr =
    if !tp.isLambdaType then TagRepr.Word(isTagged(tp))
    else
      val lambda = tp.asLambdaType
      TagRepr.Fn(lambda.paramTypes.map(repr), repr(lambda.resultType))
