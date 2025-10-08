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
  def compile(sources: List[String])(using Reporter, Config): Unit =
    val rootNameTable = new NameTable
    given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

    val namespacesSAST = sources |> Typer.parseStep |> Typer.typeStep <| "Frontend"

    locally:
      given Definitions = lazyDefn.value

      val pickler = new Step("Pickler", (nssAst: List[Namespace]) => {
        for ns <- nssAst do Encoder.store(ns, Config.targetDir.value, testPickling = false, verbose = true)

        nssAst
      })

      namespacesSAST |> pickler

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()
    val (config, sources) = cli.OptionParser.parseConfig(args, Config.targetDir :: Config.commonOptions)
    given Config = config

    Reporter.monitor():


      if sources.isEmpty then
        println("Error: No source files provided")
        System.exit(1)

      // Default target directory to current directory
      IO.ensureExists(Config.targetDir.value)

      compile(sources)
