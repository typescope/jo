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
    runtimeNameTable: NameTable)
    (using defnLazy: Definitions.Lazy, rp: Reporter)
  : List[Namespace] =

    // StdLib is compiled without the Predef
    val nssStdLib = runNamer(stdlib, defnLazy, predef = new NameTable)

    // Must be after type checking the stdlib
    val predefNameTable = defnLazy.value.Predef_nameTable

    // Runtime definitions are not entered into the root name table thus is
    // inaccessible in user programs
    val nssRuntime = runNamer(runtime, predefNameTable)

    val nss = new Namer(rp).transform(nssAst, predefNameTable)
    nssStdLib ++ nssRuntime ++ nss

  private def runNamer(
    files: List[String], predef: NameTable)
    (using defnLazy: Definitions.Lazy, rp: Reporter)
  : List[Namespace] =

    val namer = (nss: List[Ast.Namespace]) =>
      new Namer(rp).transform(nss, predef)
    // `|>` will stop early in the presence of parsing errors
    Parser.parse(files) |> namer

  def main(args: Array[String]): Unit =
    Reporter.monitor:
      val stdLib = "lib/Predef.stk" :: Nil
      val runtimeFiles = Nil

      val namer = (nssAst: List[Ast.Namespace]) =>
        val rootNameTable = new NameTable
        val runtimeNameTable = new NameTable
        given new Definitions.Lazy(rootNameTable)
        check(nssAst, stdLib, runtimeFiles, runtimeNameTable)

      val nss = Parser.parse(args.toList) |> namer |> TreeChecker.check

      for ns <- nss do
        println(ns.symbol.sourcePos.source.file + ":")
        println(ns.show)
        println
