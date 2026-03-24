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

    def checkPostTyping(units: List[FileUnit]): List[FileUnit] =
      given Definitions = defnLazy.value

      val effectCheck = new phases.EffectCheck
      val units2 = effectCheck.transform(units)

      VisibilityChecker.check(units2)
      ViewChecker.check(units2)

      if !rp.hasErrors then
        Config.sastDir.value.foreach: dir =>
          IO.ensureExists(dir)
          for unit <- units2 do pickle.Encoder.store(unit, dir, testPickling = false, verbose = true)

        if Config.testPickling.value then
          val outDir = "out/sast"
          IO.ensureExists(outDir)
          for unit <- units2 do pickle.Encoder.store(unit, outDir, testPickling = true)
      end if

      units2
    end checkPostTyping

    if libs.isEmpty then
      // compile stdlib to a lib
      val units0 = new Namer().transform(unitsAst, rootNameTable, rootScope) <| "namer.source"
      val units = if !rp.hasErrors then checkPostTyping(units0) else units0

      (units, Nil)

    else
      // Load library from .sast files
      val delayedUnits = libs.flatMap(lib => pickle.Decoder.loadPackage(lib)) <| "load libs"

      // Must be after loading the stdlib
      val defn = defnLazy.value

      val joScope = rootScope.fresh(defn.jo, defn.jo_nameTable)

      val units0 = new Namer().transform(unitsAst, rootNameTable, joScope) <| "namer.source"
      val units = if !rp.hasErrors then checkPostTyping(units0) else units0

      (units, delayedUnits)


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
