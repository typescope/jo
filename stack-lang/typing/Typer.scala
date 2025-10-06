package typing

import ast.{ Trees => Ast }
import sast.*
import sast.Trees.*

import parsing.Parser
import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config
import reporting.Mode
import common.IO

object Typer:
  /** The stdlib cannot depend on pre-defined symbols
    *
    * Assumption: the directory path in lib/runtime are in topological order.
    */
  private def check
      (nssAst: List[Ast.Namespace], libs: List[String], runtimes: List[String])
      (using defnLazy: Definitions.Lazy, rp: Reporter, cf: Config)
  : List[Namespace] =

    val rootNameTable = defnLazy.rootNameTable

    def checkEffects(nss: List[Namespace]): Unit =
      // Run normalization and pickling
      given Definitions = defnLazy.value
      val effectCheck = new phases.EffectCheck

      effectCheck.transform(nss)

      if cf.testPickling then
        given Definitions = defnLazy.value

        val outDir = "out/sast"
        IO.ensureExists(outDir)
        for ns <- nss do pickle.Encoder.store(ns, outDir, cf.testPickling)
      end if

    if libs.isEmpty then
      // compile stdlib to a lib
      val nss = new Namer().transform(nssAst, rootNameTable, predef = new NameTable) <| "namer.source"
      checkEffects(nss)

      if runtimes.nonEmpty && !rp.hasErrors then
        // most likely wrong parameters by users
        println("Warning: Unexpected runtime specified in compiling library, " + runtimes)

      // Don't check effect errors if there are type errors
      if !rp.hasErrors then checkEffects(nss)
      nss

    else
      cf.mode match
        case Mode.Library =>
          // Load library from .sast files
          for lib <- libs do loadSastSymbols(lib) <| "load lib: " + lib

          // Must be after loading the stdlib
          val predefNameTable = defnLazy.value.Predef_nameTable

          val nss = new Namer().transform(nssAst, rootNameTable, predefNameTable) <| "namer.source"

          if runtimes.nonEmpty && !rp.hasErrors then
            // most likely wrong parameters by users
            println("Warning: Unexpected runtime specified in compiling library, " + runtimes)

          // Don't check effect errors if there are type errors
          if !rp.hasErrors then checkEffects(nss)

          nss

        case Mode.Application =>
          // Load library from .sast files
          val nssLib = libs.flatMap: lib =>
            loadSastTrees(lib) <| "load lib: " + lib

          // Must be after loading the stdlib
          val predefNameTable = defnLazy.value.Predef_nameTable

          // Should be before checking runtime code such that they are not available
          val nss = new Namer().transform(nssAst, rootNameTable, predefNameTable) <| "namer.source"

          // Runtime definitions are inaccessible in user programs and may only
          // use predef definitions
          val nssRuntime = runtimes.flatMap: runtime =>
             loadSastTrees(runtime) <| "load runtime: " + runtime

          // Don't check effect errors if there are type errors
          if !rp.hasErrors then checkEffects(nss)

          nssLib ++ nssRuntime ++ nss

  /** Load precompiled .sast files */
  private def loadSastTrees(dir: String) (using defnLazy: Definitions.Lazy, rp: Reporter): List[Namespace] =
    val files = IO.getSastFiles(dir).toList
    val delayedDefs = files.map(file => pickle.Decoder.load(file))

    // Force all delayed definitions
    val defn = defnLazy.value
    delayedDefs.map(_.force()(using defn))

  private def loadSastSymbols(dir: String) (using defnLazy: Definitions.Lazy, rp: Reporter): Unit =
    val files = IO.getSastFiles(dir).toList
    for file <- files do pickle.Decoder.load(file)

  private def shouldPrint(ns: Namespace)(using config: Config): Boolean =
    config.printOnly.isEmpty || config.printOnly.exists(ns.source.contains)

  def shouldPrint(ns: Ast.Namespace)(using config: Config): Boolean =
    config.printOnly.isEmpty || config.printOnly.exists(ns.source.contains)

  def parseStep(using config: Config, rp: Reporter)
  : Step[List[String], List[Ast.Namespace]] =

    Step("Parser", sources => {
      val res = Parser.parse(sources)

      if config.printAfter.contains("Parser") then
        ast.Printing.print(res.filter(shouldPrint))

      res
    })

  def typeStep(runtimes: List[String])
      (using config: Config, lazyDefn: Definitions.Lazy, rp: Reporter)
  : Step[List[Ast.Namespace], List[Namespace]] =

    Step("Namer", (nssAst: List[Ast.Namespace]) => {
      val res = check(nssAst, config.libPaths, runtimes)

      if config.checkTree then
        given Definitions = lazyDefn.value
        TreeChecker.check(res)

      if config.printAfter.contains("Namer") then
        given Definitions = lazyDefn.value
        Printing.print(res.filter(shouldPrint))

      res
    })

  def main(args: Array[String]): Unit =
    val (options, sources) = IO.parseOptions(args, Config.commonOptionsSpec)

    given config: Config = Config(options, Mode.Library)

    Reporter.monitor:
      val runtime = Nil

      val rootNameTable = new NameTable
      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      sources |> parseStep |> typeStep(runtime)
