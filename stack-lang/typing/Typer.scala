package typing

import ast.{ Trees => Ast }
import sast.*
import sast.Trees.*

import parsing.Parser
import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config
import common.IO

object Typer:
  /** The stdlib cannot depend on pre-defined symbols
    *
    * Assumption: the directory path in lib are in topological order of dependencies.
    */
  private def check
      (unitsAst: List[Ast.FileUnit], libs: List[String])
      (using defnLazy: Definitions.Lazy, rp: Reporter, cf: Config)
  : (List[FileUnit], List[DelayedDef[FileUnit]]) =

    val rootNameTable = defnLazy.rootNameTable
    val rootScope = new Scope.RootScope(rootNameTable, owner = null)

    def checkPostTyping(units: List[FileUnit]): Unit =
      given Definitions = defnLazy.value

      val effectCheck = new phases.EffectCheck
      effectCheck.transform(units)

      VisibilityChecker.check(units)
      ViewChecker.check(units)

      if !rp.hasErrors && Config.testPickling.value then
        given Definitions = defnLazy.value

        val outDir = "out/sast"
        IO.ensureExists(outDir)
        for unit <- units do pickle.Encoder.store(unit, outDir, Config.testPickling.value)
      end if
    end checkPostTyping

    if libs.isEmpty then
      // compile stdlib to a lib
      val units = new Namer().transform(unitsAst, rootNameTable, rootScope) <| "namer.source"

      // Don't check effect errors if there are type errors
      if !rp.hasErrors then checkPostTyping(units)

      (units, Nil)

    else
      // Load library from .sast files
      val delayedUnits = libs.flatMap(lib => loadSastSymbols(lib)) <| "load libs"

      // Must be after loading the stdlib
      val defn = defnLazy.value

      val joScope = rootScope.fresh(defn.jo, defn.jo_nameTable)
      val predefScope = joScope.fresh(defn.Predef, defn.Predef_nameTable)


      val units = new Namer().transform(unitsAst, rootNameTable, predefScope) <| "namer.source"

      // Don't check effect errors if there are type errors
      if !rp.hasErrors then checkPostTyping(units)

      (units, delayedUnits)


  private def loadSastSymbols(dir: String) (using defnLazy: Definitions.Lazy, rp: Reporter): List[DelayedDef[FileUnit]] =
    val files = IO.getSastFiles(dir).toList
    for file <- files yield pickle.Decoder.load(file)

  private def shouldPrint(unit: FileUnit)(using Config): Boolean =
    Config.printOnly.value.isEmpty || Config.printOnly.value.exists(unit.source.file.contains)

  def shouldPrint(unit: Ast.FileUnit)(using Config): Boolean =
    Config.printOnly.value.isEmpty || Config.printOnly.value.exists(unit.source.file.contains)

  def parseStep(using config: Config, rp: Reporter): Step[List[String], List[Ast.FileUnit]] =

    Step("Parser", sources => {
      val res = Parser.parse(sources)

      if Config.printAfter.value.contains("Parser") then
        ast.Printing.print(res.filter(shouldPrint))

      res
    })

  def typeStep(using config: Config, lazyDefn: Definitions.Lazy, rp: Reporter)
      : Step[List[Ast.FileUnit], (List[FileUnit], List[DelayedDef[FileUnit]])]
  =

    Step("Namer", (unitsAst: List[Ast.FileUnit]) => {
      val res @ (units, _) = check(unitsAst, Config.libPaths.value)

      if Config.checkTree.value then
        given Definitions = lazyDefn.value
        TreeChecker.check(units)

      if Config.printAfter.value.contains("Namer") then
        given Definitions = lazyDefn.value
        Printing.print(units.filter(shouldPrint))

      res
    })

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()
    val (config, sources) = cli.OptionParser.parseConfig(args, Config.commonOptions)
    given Config = config

    Reporter.monitor():

      val rootNameTable = new NameTable
      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      sources |> parseStep |> typeStep
