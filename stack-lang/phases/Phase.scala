package phases

import ast.Positions.Source

import sast.*
import sast.Trees.*
import sast.Symbols.Symbol

import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config

import common.KeyProps

/** Shared code for phases */
abstract class Phase(using Definitions) extends TreeMap:
  type Context = Phase.Context

  /** Initialize the context for all file units */
  def initContext()(using Context): Unit = ()

  def transform(units: List[FileUnit]): List[FileUnit] =
    given ctx: Context = new Phase.Context
    this.initContext()

    for unit <- units
    yield
      Phase.source.set(unit.source)
      Phase.owner.set(unit.owner)
      transformFileUnit(unit)

  def transformFileUnit(unit: FileUnit)(using Context): FileUnit =
    val defs = transformDefs(unit.defs)
    FileUnit(unit.owner, unit.imports, defs, unit.source)

  /** Transform top-level definitions */
  def transformDefs(defs: List[Def])(using Context): List[Def] =
    for defn <- defs yield transformDef(defn)

  def transformDef(defn: Def)(using Context): Def =
    defn match
      case fdef: FunDef =>
        transformFunDef(fdef)

      case pdef: PatDef =>
        transformPatDef(pdef)

      case cdef: ClassDef =>
        transformClassDef(cdef)

      case idef: InterfaceDef =>
        transformInterfaceDef(idef)

      case sec: Section =>
        transformSection(sec)

      case defn => defn

  def transformSection(section: Section)(using Context): Section =
    Phase.owner.set(section.symbol)
    val defs = transformDefs(section.defs)
    Section(section.symbol, defs)(section.span)

  /** Transform top-level function definitions */
  def transformClassDef(cdef: ClassDef)(using Context): ClassDef =
    Phase.owner.set(cdef.symbol)
    val funs = cdef.funs.map(transformFunDef)
    cdef.copy(funs = funs)(cdef.span)

  /** Transform interface definitions */
  def transformInterfaceDef(idef: InterfaceDef)(using Context): InterfaceDef =
    Phase.owner.set(idef.symbol)
    val methods = idef.methods.map(transformFunDef)
    idef.copy(methods = methods)(idef.span)

  /** Transform function definitions */
  def transformFunDef(fdef: FunDef)(using Context): FunDef =
    Phase.owner.set(fdef.symbol)
    val body = this(fdef.body)
    if body `eq` fdef.body then fdef
    else fdef.copy(body = body)(fdef.span)

  /** Transform function definitions */
  def transformPatDef(pdef: PatDef)(using Context): PatDef =
    Phase.owner.set(pdef.symbol)
    val body = this(pdef.body)
    if body `eq` pdef.body then pdef
    else pdef.copy(body = body)(pdef.span)

  override def transformLocalFunDef(fdef: FunDef)(using ctx: Context): Word =
    transformFunDef(fdef)

  override def transformLocalPatDef(pdef: PatDef)(using ctx: Context): Word =
    transformPatDef(pdef)

object Phase:
  class Context extends KeyProps.Container

  class PhaseKey[T](name: String) extends KeyProps.UpdatableKey[T](name):
    def value(using ctx: Context): T = ctx.getKey(this)
    def set(value: T)(using ctx: Context): Unit = ctx.updateKey(this, value)
    def test(using ctx: Context): Option[T] = ctx.testKey(this)
    def unset()(using ctx: Context): Unit = ctx.removeKey(this)

  val owner: PhaseKey[Symbol] = new PhaseKey("owner")
  val source: PhaseKey[Source] = new PhaseKey("source")

  def shouldPrint(unit: FileUnit)(using config: Config): Boolean =
    Config.printOnly.value.isEmpty || Config.printOnly.value.exists(unit.source.file.contains)

  type PhaseStep = Step[List[FileUnit], List[FileUnit]]
  given (using defn: Definitions, rp: Reporter, config: Config): Conversion[Phase, PhaseStep] = phase =>
    val name = phase.getClass.getSimpleName()
    Step(name, code => {
      if Config.printBefore.value.contains(name) then
        Printing.print(code.filter(shouldPrint))

      val output = phase.transform(code)

      if Config.checkTree.value then
        TreeChecker.check(output)

      if Config.printAfter.value.contains(name) then
        Printing.print(output.filter(shouldPrint))

      output
    })
