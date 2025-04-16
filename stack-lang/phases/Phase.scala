package phases

import sast.*
import sast.Sast.*
import sast.Symbols.Symbol

import Phase.ContextObject

/** Shared code for phases */
abstract class Phase[T] extends SastOps.TreeMap:
  val contextObject: ContextObject[T]
  type Context = T

  def transform(nss: List[Namespace]): List[Namespace] =
    for ns <- nss
    yield
      given Context = contextObject.newContext(ns.symbol)
      transformNamespace(ns)

  def transformNamespace(ns: Namespace)(using ctx: Context): Namespace =
    val defs = ns.defs.map:
      case fdef: FunDef =>
        transformFunDef(fdef)

      case defn => defn

    Namespace(ns.symbol, ns.imports, defs)(ns.span)

  override def transformFunDef(fdef: FunDef)(using ctx: Context): FunDef =
    given Context = contextObject.newContext(fdef.symbol, ctx)
    val body = this(fdef.body)
    fdef.copy(body = body)(fdef.span)

object Phase:
  trait ContextObject[T]:
    def newContext(owner: Symbol, old: T): T
    def newContext(namespace: Symbol): T

  object OwnerContext extends ContextObject[Symbol]:
    def newContext(owner: Symbol, old: Symbol): Symbol = owner
    def newContext(namespace: Symbol): Symbol = namespace

  object DummyContext extends ContextObject[Unit]:
    def newContext(owner: Symbol, old: Unit): Unit = ()
    def newContext(namespace: Symbol): Unit = ()
