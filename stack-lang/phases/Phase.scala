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

      case pdef: PatDef =>
        transformPatDef(pdef)

      case defn => defn

    Namespace(ns.symbol, ns.imports, defs)(ns.span)

  /** Transform top-level function definitions */
  def transformFunDef(fdef: FunDef)(using ctx: Context): FunDef =
    given Context = contextObject.newContext(fdef.symbol, ctx)
    val body = this(fdef.body)
    fdef.copy(body = body)(fdef.span)

  /** Transform top-level function definitions */
  def transformPatDef(pdef: PatDef)(using ctx: Context): PatDef =
    given Context = contextObject.newContext(pdef.symbol, ctx)
    val body = this(pdef.body)
    pdef.copy(body = body)(pdef.span)

  override def transformNestedFunDef(fdef: FunDef)(using ctx: Context): Word =
    transformFunDef(fdef)

  override def transformNestedPatDef(pdef: PatDef)(using ctx: Context): Word =
    transformPatDef(pdef)

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
