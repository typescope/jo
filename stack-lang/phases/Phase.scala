package phases

import sast.*
import sast.Trees.*
import sast.Symbols.Symbol

import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config

import Phase.ContextObject

/** Shared code for phases */
abstract class Phase[T](using Definitions) extends TreeMap:
  val contextObject: ContextObject[T]
  type Context = T

  def transform(nss: List[Namespace]): List[Namespace] =
    for ns <- nss
    yield
      given Context = contextObject.newContext(ns.symbol)
      transformNamespace(ns)

  def transformNamespace(ns: Namespace)(using ctx: Context): Namespace =
    val defs = transformDefs(ns.defs)
    Namespace(ns.symbol, ns.imports, defs)(ns.span)

  /** Transform top-level definitions */
  def transformDefs(defs: List[Def])(using ctx: Context): List[Def] =
    for defn <- defs yield transformDef(defn)

  def transformDef(defn: Def)(using ctx: Context): Def =
    defn match
      case fdef: FunDef =>
        transformFunDef(fdef)

      case pdef: PatDef =>
        transformPatDef(pdef)

      case cdef: ClassDef =>
        transformClassDef(cdef)

      case sec: Section =>
        transformSection(sec)

      case defn => defn

  def transformSection(section: Section)(using ctx: Context): Section =
    given Context = contextObject.newContext(section.symbol, ctx)
    val defs = transformDefs(section.defs)
    Section(section.symbol, defs)(section.span)

  /** Transform top-level function definitions */
  def transformClassDef(cdef: ClassDef)(using ctx: Context): ClassDef =
    given Context = contextObject.newContext(cdef.symbol, ctx)
    val funs = cdef.funs.map(transformFunDef)
    cdef.copy(funs = funs)(cdef.span)

  /** Transform function definitions */
  def transformFunDef(fdef: FunDef)(using ctx: Context): FunDef =
    given Context = contextObject.newContext(fdef.symbol, ctx)
    val body = this(fdef.body)
    if body `eq` fdef.body then fdef
    else fdef.copy(body = body)(fdef.span)

  /** Transform function definitions */
  def transformPatDef(pdef: PatDef)(using ctx: Context): PatDef =
    given Context = contextObject.newContext(pdef.symbol, ctx)
    val body = this(pdef.body)
    if body `eq` pdef.body then pdef
    else pdef.copy(body = body)(pdef.span)

  override def transformLocalFunDef(fdef: FunDef)(using ctx: Context): Word =
    transformFunDef(fdef)

  override def transformLocalPatDef(pdef: PatDef)(using ctx: Context): Word =
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

  def shouldPrint(ns: Namespace)(using config: Config): Boolean =
    config.printOnly.isEmpty || config.printOnly.exists(ns.source.contains)

  type PhaseStep = Step[List[Namespace], List[Namespace]]
  given (using defn: Definitions, rp: Reporter, config: Config): Conversion[Phase[?], PhaseStep] = phase =>
    val name = phase.getClass.getSimpleName()
    Step(name, code => {
      val output = phase.transform(code)

      if config.checkTree then
        TreeChecker.check(output)

      if config.printAfter.contains(name) then
        Printing.print(output.filter(shouldPrint))

      output
    })
