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
  /** The stdlib cannot depend on pre-defined symbols */
  def check
      (nssAst: List[Ast.Namespace], lib: List[String], runtime: List[String])
      (using defnLazy: Definitions.Lazy, rp: Reporter, cf: Config)
  : List[Namespace] =

    val rootNameTable = defnLazy.rootNameTable

    if lib.isEmpty then
      assert(runtime.isEmpty, "Unexpected runtime for compiling standard library: " + runtime)
      val nss = new Namer().transform(nssAst, rootNameTable, predef = new NameTable) <| "namer.source"

      if runtime.isEmpty then
        nss
      else
        val predefNameTable = defnLazy.value.Predef_nameTable

        // Runtime definitions are inaccessible in user programs and may only
        // use predef definitions
        val nssRuntime = runNamer(runtime, rootNameTable, predefNameTable) <| "runtime"

        nssRuntime ++ nss

    else
      // Load library from .sast files
      val nssLib = loadSastFiles(lib) <| "lib"

      // Must be after type checking the stdlib
      val predefNameTable = defnLazy.value.Predef_nameTable

      // Should be before checking runtime code such that they are not available
      val nss = new Namer().transform(nssAst, rootNameTable, predefNameTable) <| "namer.source"

      // Runtime definitions are inaccessible in user programs and may only
      // use predef definitions
      val nssRuntime = runNamer(runtime, rootNameTable, predefNameTable) <| "runtime"

      nssLib ++ nssRuntime ++ nss

  /** Load precompiled .sast files */
  private def loadSastFiles
      (files: List[String])
      (using defnLazy: Definitions.Lazy, rp: Reporter)
  : List[Namespace] =

    val delayedDefs = files.map(file => pickle.Decoder.load(file))

    // Force all delayed definitions
    val defn = defnLazy.value
    delayedDefs.map(_.force()(using defn))

  private def runNamer(
    files: List[String], rootNameTable: NameTable, predef: NameTable)
    (using defnLazy: Definitions.Lazy, rp: Reporter, cf: Config)
  : List[Namespace] =

    val namer = Step("namer", (nss: List[Ast.Namespace]) => {
      val code = new Namer().transform(nss, rootNameTable, predef)
      if cf.checkTree then TreeChecker.check(code)(using defnLazy.value)
      code
    })

    // `|>` will stop early in the presence of parsing errors
    files |> parseStep |> namer

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

  def typeStep(runtime: List[String])
      (using config: Config, lazyDefn: Definitions.Lazy, rp: Reporter)
  : Step[List[Ast.Namespace], List[Namespace]] =

    Step("Namer", (nssAst: List[Ast.Namespace]) => {
        // Get library files from -lib option if provided
        val libFiles = config.libPath match
          case Some(dir) => IO.getSastFiles(dir).toList
          case None => Nil

        val res = check(nssAst, libFiles, runtime)

        if config.checkTree then
          given Definitions = lazyDefn.value
          TreeChecker.check(res)

        if config.printAfter.contains("Namer") then
          given Definitions = lazyDefn.value
          Printing.print(res.filter(shouldPrint))

        if config.testPickling then
          given Definitions = lazyDefn.value

          val outDir = "out/sast"
          IO.ensureExists(outDir)
          for ns <- res do pickle.Encoder.store(ns, outDir, config.testPickling)

        res
      })

  def main(args: Array[String]): Unit =
    val (options, sources) = IO.parseOptions(args, Config.commonOptionsSpec)

    given config: Config = Config(options)

    Reporter.monitor:
      val runtimeFiles = Nil

      val rootNameTable = new NameTable
      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      sources |> parseStep |> typeStep(runtimeFiles)
