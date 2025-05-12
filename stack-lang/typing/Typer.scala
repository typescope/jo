package typing

import ast.Ast
import sast.*
import sast.Sast.*

import parsing.Parser
import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config
import common.IO

object Typer:
  val stdLib = List(
    "lib/Array.stk",
    "lib/Bool.stk",
    "lib/Int.stk",
    "lib/Internal.stk",
    "lib/IO.stk",
    "lib/List.stk",
    "lib/Predef.stk",
  )

  /** The stdlib cannot depend on pre-defined symbols */
  def check
      (nssAst: List[Ast.Namespace], runtime: List[String])
      (using defnLazy: Definitions.Lazy, rp: Reporter, cf: Config)
  : List[Namespace] =

    val rootNameTable = defnLazy.rootNameTable

    // StdLib is compiled without the Predef
    val nssStdLib = runNamer(stdLib, rootNameTable, predef = new NameTable) <| "stdlib"

    // Must be after type checking the stdlib
    val predefNameTable = defnLazy.value.Predef_nameTable

    // Should be before checking runtime code such that they are not available
    val nss = new Namer().transform(nssAst, rootNameTable, predefNameTable) <| "namer.source"

    // Runtime definitions are inaccessible in user programs and may only
    // use predef definitions
    val nssRuntime = runNamer(runtime, rootNameTable, predefNameTable) <| "runtime"

    nssStdLib ++ nssRuntime ++ nss

  private def runNamer(
    files: List[String], rootNameTable: NameTable, predef: NameTable)
    (using defnLazy: Definitions.Lazy, rp: Reporter, cf: Config)
  : List[Namespace] =

    val namer = Step("namer", (nss: List[Ast.Namespace]) => {
      new Namer().transform(nss, rootNameTable, predef)
    })

    // `|>` will stop early in the presence of parsing errors
    Parser.parse(files) |> namer

  def main(args: Array[String]): Unit =
    val (options, sources) = IO.parseOptions(args, Config.commonOptionsSpec)

    given Config = Config(options)

    Reporter.monitor:
      val runtimeFiles = Nil

      val rootNameTable = new NameTable
      given lazyDefn: Definitions.Lazy = new Definitions.Lazy(rootNameTable)

      val namer = Step("namer", (nssAst: List[Ast.Namespace]) => check(nssAst, runtimeFiles))

      Parser.parse(sources) |> namer
