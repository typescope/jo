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
      (nssAst: List[Ast.Namespace], libs: List[String])
      (using defnLazy: Definitions.Lazy, rp: Reporter, cf: Config)
  : (List[Namespace], List[DelayedDef[Namespace]]) =

    val rootNameTable = defnLazy.rootNameTable

    def checkEffects(nss: List[Namespace]): Unit =
      // Run normalization and pickling
      given Definitions = defnLazy.value
      val effectCheck = new phases.EffectCheck

      effectCheck.transform(nss)

      if Config.testPickling.value then
        given Definitions = defnLazy.value

        val outDir = "out/sast"
        IO.ensureExists(outDir)
        for ns <- nss do pickle.Encoder.store(ns, outDir, Config.testPickling.value)
      end if

    if libs.isEmpty then
      // compile stdlib to a lib
      val nss = new Namer().transform(nssAst, rootNameTable, predef = new NameTable) <| "namer.source"
      checkEffects(nss)

      // Don't check effect errors if there are type errors
      if !rp.hasErrors then checkEffects(nss)
      (nss, Nil)

    else
      // Load library from .sast files
      val delayedNss = libs.flatMap(lib => loadSastSymbols(lib)) <| "load libs"

      // Must be after loading the stdlib
      val predefNameTable = defnLazy.value.Predef_nameTable

      val nss = new Namer().transform(nssAst, rootNameTable, predefNameTable) <| "namer.source"

      // Don't check effect errors if there are type errors
      if !rp.hasErrors then checkEffects(nss)

      (nss, delayedNss)


  private def loadSastSymbols(dir: String) (using defnLazy: Definitions.Lazy, rp: Reporter): List[DelayedDef[Namespace]] =
    val files = IO.getSastFiles(dir).toList
    for file <- files yield pickle.Decoder.load(file)

  private def shouldPrint(ns: Namespace)(using Config): Boolean =
    Config.printOnly.value.isEmpty || Config.printOnly.value.exists(ns.source.contains)

  def shouldPrint(ns: Ast.Namespace)(using Config): Boolean =
    Config.printOnly.value.isEmpty || Config.printOnly.value.exists(ns.source.contains)

  def parseStep(using config: Config, rp: Reporter): Step[List[String], List[Ast.Namespace]] =

    Step("Parser", sources => {
      val res = Parser.parse(sources)

      if Config.printAfter.value.contains("Parser") then
        ast.Printing.print(res.filter(shouldPrint))

      res
    })

  def typeStep(using config: Config, lazyDefn: Definitions.Lazy, rp: Reporter)
      : Step[List[Ast.Namespace], (List[Namespace], List[DelayedDef[Namespace]])]
  =

    Step("Namer", (nssAst: List[Ast.Namespace]) => {
      val res @ (nss, _) = check(nssAst, Config.libPaths.value)

      if Config.checkTree.value then
        given Definitions = lazyDefn.value
        TreeChecker.check(nss)

      if Config.printAfter.value.contains("Namer") then
        given Definitions = lazyDefn.value
        Printing.print(nss.filter(shouldPrint))

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
