package phases

import sast.*
import sast.Sast.*
import sast.Symbols.Symbol

import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config

import Phase.ContextObject

/** Shared code for phases */
abstract class Phase[T](using Definitions) extends SastOps.TreeMap:
  val contextObject: ContextObject[T]
  type Context = T

  def transform(nss: List[Namespace]): List[Namespace] =
    for ns <- nss
    yield
      given Context = contextObject.newContext(ns.symbol)
      transformNamespace(ns)

  def transformNamespace(ns: Namespace)(using ctx: Context): Namespace =
    val defs = transformTopLevelDefs(ns.defs)
    Namespace(ns.symbol, ns.imports, defs)(ns.span)

  /** Transform top-level definitions */
  def transformTopLevelDefs(defs: List[Def])(using ctx: Context): List[Def] =
    for defn <- defs yield transformTopLevelDef(defn)

  def transformTopLevelDef(defn: Def)(using ctx: Context): Def =
    defn match
      case fdef: FunDef =>
        transformTopLevelFunDef(fdef)

      case pdef: PatDef =>
        transformTopLevelPatDef(pdef)

      case sec: Section =>
        transformSection(sec)

      case defn => defn

  def transformSection(section: Section)(using ctx: Context): Section =
    given Context = contextObject.newContext(section.symbol, ctx)
    val defs = transformTopLevelDefs(section.defs)
    Section(section.symbol, defs)(section.span)

  /** Transform top-level function definitions */
  def transformTopLevelFunDef(fdef: FunDef)(using ctx: Context): FunDef =
    given Context = contextObject.newContext(fdef.symbol, ctx)
    val body = this(fdef.body)
    fdef.copy(body = body)(fdef.span)

  /** Transform top-level function definitions */
  def transformTopLevelPatDef(pdef: PatDef)(using ctx: Context): PatDef =
    given Context = contextObject.newContext(pdef.symbol, ctx)
    val body = this(pdef.body)
    pdef.copy(body = body)(pdef.span)

  override def transformNestedFunDef(fdef: FunDef)(using ctx: Context): Word =
    transformTopLevelFunDef(fdef)

  override def transformNestedPatDef(pdef: PatDef)(using ctx: Context): Word =
    transformTopLevelPatDef(pdef)

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

  type PhaseStep = Step[List[Namespace], List[Namespace]]
  given (using defn: Definitions, rp: Reporter, config: Config): Conversion[Phase[?], PhaseStep] = phase =>
    val name = phase.getClass.getSimpleName()
    Step(name, code => {
      if config.checkTree then TreeChecker.check(code)
      val output = phase.transform(code)
      if config.printAfter.contains(name) then Printing.print(output)
      output
    })
