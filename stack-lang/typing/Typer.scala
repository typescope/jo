package typing

import ast.Ast
import sast.*
import sast.Sast.*

import parsing.Parser
import reporting.Reporter
import common.IO

object Typer:
  /** The stdlib cannot depend on pre-defined symbols */
  def check(
    nssAst: List[Ast.Namespace], stdlib: List[String], runtime: List[String],
    runtimeNameTable: NameTable)
    (using defnLazy: Definitions.Lazy, rp: Reporter)
  : List[Namespace] =
    val rootNameTable = defnLazy.rootNameTable

    // StdLib is compiled without the Predef
    val nssStdLib = runNamer(stdlib, rootNameTable, predef = new NameTable)

    // Must be after type checking the stdlib
    val predefNameTable = defnLazy.value.Predef_nameTable

    // Runtime definitions are inaccessible in user programs and may only
    // use predef definitions
    val nssRuntime = runNamer(runtime, runtimeNameTable, predefNameTable)

    val nss = new Namer(rp).transform(nssAst, rootNameTable, predefNameTable)
    nssStdLib ++ nssRuntime ++ nss

  private def runNamer(
    files: List[String], rootNameTable: NameTable, predef: NameTable)
    (using defnLazy: Definitions.Lazy, rp: Reporter)
  : List[Namespace] =

    val namer = (nss: List[Ast.Namespace]) =>
      new Namer(rp).transform(nss, rootNameTable, predef)
    // `|>` will stop early in the presence of parsing errors
    Parser.parse(files) |> namer

  def main(args: Array[String]): Unit =
    val optionSpec = Map(
      "-fatal-warnings" -> false,
    )

    val (options, sources) = IO.parseOptions(args, optionSpec)

    Reporter.monitor:
      val stdLib = "lib/Predef.stk" :: Nil
      val runtimeFiles = Nil

      val rootNameTable = new NameTable
      val runtimeNameTable = new NameTable
      given lazyDefn: Definitions.Lazy = new Definitions.Lazy(rootNameTable)

      val namer = (nssAst: List[Ast.Namespace]) =>
        check(nssAst, stdLib, runtimeFiles, runtimeNameTable)

      given defn: Definitions = lazyDefn.value
      val nss = Parser.parse(sources) |> namer |> TreeChecker.check

      if !options.contains("-fatal-warnings") || !summon[Reporter].hasWarnings then
        for ns <- nss if ns.symbol != defn.Predef do
          println(ns.symbol.sourcePos.source.file + ":")
          println(ns.show)
          println
