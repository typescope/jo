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

    "jo.abort"      -> "jo.runtime.python.abort",

    // IntArray operations
    "jo.Array.IntArray.create" -> "jo.runtime.python.IntArray.create",
    "jo.Array.IntArray.get"    -> "jo.runtime.python.IntArray.get",
    "jo.Array.IntArray.set"    -> "jo.runtime.python.IntArray.set",
    "jo.Array.IntArray.size"   -> "jo.runtime.python.IntArray.size",

    // FloatArray operations
    "jo.Array.FloatArray.create" -> "jo.runtime.python.FloatArray.create",
    "jo.Array.FloatArray.get"    -> "jo.runtime.python.FloatArray.get",
    "jo.Array.FloatArray.set"    -> "jo.runtime.python.FloatArray.set",
    "jo.Array.FloatArray.size"   -> "jo.runtime.python.FloatArray.size",

    // ByteArray operations
    "jo.Array.ByteArray.create" -> "jo.runtime.python.ByteArray.create",
    "jo.Array.ByteArray.get"    -> "jo.runtime.python.ByteArray.get",
    "jo.Array.ByteArray.set"    -> "jo.runtime.python.ByteArray.set",
    "jo.Array.ByteArray.size"   -> "jo.runtime.python.ByteArray.size",

    // RefArray operations
    "jo.Array.RefArray.create" -> "jo.runtime.python.RefArray.create",
    "jo.Array.RefArray.get"    -> "jo.runtime.python.RefArray.get",
    "jo.Array.RefArray.set"    -> "jo.runtime.python.RefArray.set",
    "jo.Array.RefArray.size"   -> "jo.runtime.python.RefArray.size",

    // Regex engine hooks
    "jo.regex.Engine.compilePattern" -> "jo.runtime.python.RegexEngine.compilePattern",
    "jo.regex.Engine.execPatternAt"  -> "jo.runtime.python.RegexEngine.execPatternAt",
  )

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, sources) = cli.OptionParser.parseConfig(args, Config.appOptions)

    if sources.isEmpty then
      println("Expect source file as input")
      return

    given Config = config

    Config.useRuntimeApi.value match
      case Some(runtime) if runtime != "python" =>
        Reporter.error(s"--python does not support --use-runtime-api $runtime")
      case _ =>

    Reporter.monitor():
      val outFile = Config.outFilePath.value.getOrElse{
        if sources.size == 1 then
          IO.fileNameNoExt(sources.head) + ".py"
        else
          "out.py"
      }

      val rootNameTable = new NameTable

      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      val defaultRuntimePackages =
        if Config.useRuntimeApi.value.contains("python") then Nil
        else Config.PythonRuntimePath :: Nil
      val units = FrontEnd.run(defaultRuntimePackages, sources, defaultLinkMappings) <| "Frontend"

      locally {
        given Definitions = lazyDefn.value

        val pythonRuntime = new PythonRuntime
        val contextParamsLower = new LowerContextParams(
            pythonRuntime.paramKey,
            pythonRuntime.emptyCtx,
            pythonRuntime.getParam,
            pythonRuntime.startBatch,
            pythonRuntime.addBinding,
            pythonRuntime.finishBatch)

        val closureConvert = new ElimCapture
        val viewMaterializer = new phases.MaterializeView
        val codeGen = new PythonCodeGen(pythonRuntime, FrontEnd.rewireMap.value)
        val backend: Step[List[FileUnit], Unit] =
          Step("Backend", (units: List[FileUnit]) =>
            codeGen.generate(units, outFile)
          )
        units               |>
        contextParamsLower  |>
        closureConvert      |>
        viewMaterializer    |>
        backend
      } <| "Backend"
