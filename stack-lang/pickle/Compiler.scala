package pickle

import sast.*
import sast.Trees.Namespace

import typing.Typer
import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config

import common.IO

/** Compiler for building libraries (producing .sast files) */
object Compiler:
  def compile(sources: List[String], targetDir: String)(using cf: Config): Unit =
    Reporter.monitor:
      val rootNameTable = new NameTable
      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      // Parse and type check (no runtime needed for libraries)
      val runtime = Nil
      val namespacesSAST = sources |> Typer.parseStep |> Typer.typeStep(runtime) <| "Frontend"

      locally:
        given Definitions = lazyDefn.value

        val pickler = new Step("Pickler", (nssAst: List[Namespace]) => {
          for ns <- nssAst do Encoder.store(ns, targetDir, testPickling = false, verbose = true)

          nssAst
        })

        namespacesSAST |> pickler

  def main(args: Array[String]): Unit =
    val optionSpec = Config.commonOptionsSpec + ("-d" -> true)
    val (options, sources) = IO.parseOptions(args, optionSpec)

    if sources.isEmpty then
      println("Error: No source files provided")
      System.exit(1)

    // Default target directory to current directory
    val targetDir = options.getOrElse("-d", ".")
    IO.ensureExists(targetDir)

    given Config = Config(options)

    compile(sources, targetDir)
