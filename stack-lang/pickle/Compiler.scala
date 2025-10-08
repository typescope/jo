package pickle

import sast.*
import sast.Trees.Namespace

import typing.Typer
import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config
import reporting.Mode

import common.IO

/** Compiler for building libraries (producing .sast files) */
object Compiler:
  def compile(sources: List[String], targetDir: String)(using cf: Config): Unit =
    Reporter.monitor:
      val rootNameTable = new NameTable
      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      val namespacesSAST = sources |> Typer.parseStep |> Typer.typeStep <| "Frontend"

      locally:
        given Definitions = lazyDefn.value

        val pickler = new Step("Pickler", (nssAst: List[Namespace]) => {
          for ns <- nssAst do Encoder.store(ns, targetDir, testPickling = false, verbose = true)

          nssAst
        })

        namespacesSAST |> pickler

  def main(args: Array[String]): Unit =
    val additionalOptions = Map("-d" -> cli.OptionParser.OptionSpec.Single)
    val (options, sources) = cli.OptionParser.parseOptions(args, cli.OptionParser.commonOptions ++ additionalOptions)

    if sources.isEmpty then
      println("Error: No source files provided")
      System.exit(1)

    // Default target directory to current directory
    val targetDir = cli.OptionParser.getOption(options, "-d").getOrElse(".")
    IO.ensureExists(targetDir)

    given Config = cli.OptionParser.buildConfig(options, Mode.Library)

    compile(sources, targetDir)
