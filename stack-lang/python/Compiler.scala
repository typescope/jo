package python

import common.IO

import sast.*
import sast.Trees.FileUnit
import phases.*

import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config

import scala.language.implicitConversions

/***********************************************************************
 *
 * Main entry point for the Python compiler
 *
 ***********************************************************************/
object Compiler:
  // Default link mappings for Python runtime
  val defaultLinkMappings = Map(

    "jo.abort"      -> "py.abort",

    // IntArray operations
    "jo.Array.IntArray.create" -> "py.IntArray.create",
    "jo.Array.IntArray.get"    -> "py.IntArray.get",
    "jo.Array.IntArray.set"    -> "py.IntArray.set",
    "jo.Array.IntArray.size"   -> "py.IntArray.size",

    // FloatArray operations
    "jo.Array.FloatArray.create" -> "py.FloatArray.create",
    "jo.Array.FloatArray.get"    -> "py.FloatArray.get",
    "jo.Array.FloatArray.set"    -> "py.FloatArray.set",
    "jo.Array.FloatArray.size"   -> "py.FloatArray.size",

    // ByteArray operations
    "jo.Array.ByteArray.create" -> "py.ByteArray.create",
    "jo.Array.ByteArray.get"    -> "py.ByteArray.get",
    "jo.Array.ByteArray.set"    -> "py.ByteArray.set",
    "jo.Array.ByteArray.size"   -> "py.ByteArray.size",

    // RefArray operations
    "jo.Array.RefArray.create" -> "py.RefArray.create",
    "jo.Array.RefArray.get"    -> "py.RefArray.get",
    "jo.Array.RefArray.set"    -> "py.RefArray.set",
    "jo.Array.RefArray.size"   -> "py.RefArray.size",

    // Regex engine hooks
    "jo.regex.Engine.compilePattern" -> "py.RegexEngine.compilePattern",
    "jo.regex.Engine.execPatternAt"  -> "py.RegexEngine.execPatternAt",
  )

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, sources) = cli.OptionParser.parseConfig(args, Config.appOptions)

    if sources.isEmpty then
      println("Expect source file as input")
      return

    given Config = config


    Reporter.monitor():
      val outFile = Config.outFilePath.value.getOrElse{
        if sources.size == 1 then
          IO.fileNameNoExt(sources.head) + ".py"
        else
          "out.py"
      }

      val rootNameTable = new NameTable

      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      val runtimes =
        if Config.noRuntime.value then Config.runtimePaths.value
        else Config.PythonRuntimePath :: Config.runtimePaths.value
      val units = FrontEnd.run(runtimes, sources, defaultLinkMappings) <| "Frontend"

      locally {
        given Definitions = lazyDefn.value

        val pythonRuntime = new PythonRuntime
        val contextParamsLower = new LowerContextParams(
            pythonRuntime.paramKey,
            pythonRuntime.emptyCtx,
            pythonRuntime.getParam,
            pythonRuntime.bindParam)

        val closureConvert = new ElimCapture
        val viewMaterializer = new phases.MaterializeView
        val codeGen = new PythonCodeGen(pythonRuntime, FrontEnd.rewireMap.value)
        val backend: Step[List[FileUnit], Unit] =
          Step("Backend", (units: List[FileUnit]) =>
            codeGen.generate(units, outFile)
          )

        units               |>
        closureConvert      |>
        contextParamsLower  |>
        viewMaterializer    |>
        backend
      } <| "Backend"
