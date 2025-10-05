package pickle

import sast.*
import sast.Trees.Namespace

import phases.NormalizeParams
import typing.Typer
import reporting.Reporter
import reporting.Config

import common.IO

/** Compiler for building libraries (producing .sast files) */
object Compiler:
  def compile(sources: List[String], targetDir: String)(using Config): Unit =
    Reporter.monitor:
      val rootNameTable = new NameTable
      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      // Parse and type check (no runtime needed for libraries)
      val runtime = Nil
      val lib = Nil
      val namespacesSAST = sources |> Typer.parseStep |> Typer.typeStep(lib, runtime) <| "Frontend"

      locally:
        given Definitions = lazyDefn.value

        // Run normalization and pickling
        val normalizer = new NormalizeParams
        val pickler = new PicklerWithTarget(targetDir)

        namespacesSAST |>
        pickler        |>
        normalizer

  /** Custom pickler that writes to a specified target directory */
  class PicklerWithTarget(targetDir: String)(using defn: Definitions)
    extends phases.Phase[Unit]:

    val contextObject = phases.Phase.DummyContext

    override def transformNamespace(ns: Namespace)(using ctx: Context): Namespace =
      val fullName = ns.symbol.fullName
      val fileName = fullName + ".sast"
      val path = java.nio.file.Paths.get(targetDir, fileName).toString

      val buf = Encoder.encode(ns)
      IO.writeFile(path, buf.getBytes, 0, buf.length)

      println(s"Generated: $path")

      ns

  def main(args: Array[String]): Unit =
    val optionSpec = Config.commonOptionsSpec + ("-d" -> true)
    val (options, sources) = IO.parseOptions(args, optionSpec)

    if sources.isEmpty then
      println("Error: No source files provided")
      System.exit(1)

    // Default target directory to current directory
    val targetDir = options.getOrElse("-d", ".")

    // Create target directory if it doesn't exist
    val targetPath = java.nio.file.Paths.get(targetDir)
    if !java.nio.file.Files.exists(targetPath) then
      java.nio.file.Files.createDirectories(targetPath)

    given Config = Config(options)

    compile(sources, targetDir)
