package typing

import ast.Ast
import sast.*
import sast.Sast.*

import parsing.Parser
import reporting.Reporter

object Typer:
  /** The stdlib cannot depend on pre-defined symbols */
  def check(
    nssAst: List[Ast.Namespace], stdlib: List[String], runtime: List[String],
    rootNameTable: NameTable, runtimeNameTable: NameTable)
    (using rp: Reporter)
  : List[Namespace] =

    // Install lazy definitions
    Definitions.initialize(rootNameTable)

    // StdLib is compiled without the Predef
    val nssStdLib = runNamer(stdlib, rootNameTable, predef = new NameTable)

    // Must be after type checking the stdlib
    val predefNameTable = Definitions.instance.Predef_nameTable

    // Runtime definitions are not entered into the root name table thus is
    // inaccessible in user programs
    val nssRuntime = runNamer(runtime, runtimeNameTable, predefNameTable)

    val nss = new Namer(rp).transform(nssAst, rootNameTable, predefNameTable)
    nssStdLib ++ nssRuntime ++ nss

  private def runNamer(
    files: List[String], rootNameTable: NameTable, predef: NameTable)
    (using rp: Reporter)
  : List[Namespace] =

    val namer = (nss: List[Ast.Namespace]) =>
      new Namer(rp).transform(nss, rootNameTable, predef)
    // `|>` will stop early in the presence of parsing errors
    Parser.parse(files) |> namer

  def main(args: Array[String]): Unit =
    Reporter.monitor:
      val stdLib = "lib/Predef.stk" :: Nil
      val runtimeFiles = Nil

      val namer = (nssAst: List[Ast.Namespace]) =>
        val rootNameTable = new NameTable
        val runtimeNameTable = new NameTable
        check(nssAst, stdLib, runtimeFiles, rootNameTable, runtimeNameTable)

      val nss = Parser.parse(args.toList) |> namer |> TreeChecker.check

      for ns <- nss do
        println(ns.symbol.sourcePos.source.file + ":")
        println(ns.show)
        println
