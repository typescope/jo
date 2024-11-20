import Sast.*
import Symbols.*

import scala.collection.mutable

/** The compiler phase that makes initialization explicit.
  *
  * - Rewrite val definitions to assign statement.
  * - Augment function definitions with a list of local symbols.
  * - Remove type definitions.
  */
class ExplicitInit(using Reporter):
  val treeMap = new ExplicitInit.LocalsTreeMap

  def transform(nss: List[Namespace]): List[Namespace] =
    for ns <- nss yield transformNamespace(ns)

  def transformNamespace(ns: Namespace): Namespace =
    val funs =
      for case funDef: FunDef <- ns.defs
      yield treeMap.transform(funDef)

    Namespace(ns.symbol, ns.fullName, funs)(ns.span)

object ExplicitInit:
  class NamesInfo:
    val locals = new mutable.ArrayBuffer[Symbol]
    val free = new mutable.ArrayBuffer[Symbol]

  class LocalsTreeMap extends SastOps.TreeMap:
    type Context = NamesInfo

    def transform(fdef: FunDef): FunDef =
      given info: NamesInfo = new NamesInfo
      val body = this(fdef.body)
      val locals = info.locals.distinct.toList
      val masked = fdef.params ++ locals
      val free = info.free.filter(sym => !masked.contains(sym)).distinct.toList
      fdef.copy(body = body)(locals.filter(_.isValue), free, fdef.span)

    def apply(word: Word)(using info: Context): Word =
      word match
        case Ident(sym) =>
          info.free += sym
          word

        case ValDef(sym, rhs) =>
          info.locals += sym
          Assign(sym, this(rhs))(word.span)

        case fdef: FunDef =>
          info.locals += fdef.symbol
          transform(fdef)

        case Phrase(words) =>
          val words2 =
            for
              word <- words if !word.isInstanceOf[TypeDef]
            yield
              this(word)
          Phrase(words2)(word.tpe, word.span)

        case _ => recur(word)
