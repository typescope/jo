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

    "jo.abort"      -> "jo.py.runtime.abort",

    // IntArray operations
    "jo.Array.IntArray.create" -> "jo.py.runtime.IntArray.create",
    "jo.Array.IntArray.get"    -> "jo.py.runtime.IntArray.get",
    "jo.Array.IntArray.set"    -> "jo.py.runtime.IntArray.set",
    "jo.Array.IntArray.size"   -> "jo.py.runtime.IntArray.size",

    // FloatArray operations
    "jo.Array.FloatArray.create" -> "jo.py.runtime.FloatArray.create",
    "jo.Array.FloatArray.get"    -> "jo.py.runtime.FloatArray.get",
    "jo.Array.FloatArray.set"    -> "jo.py.runtime.FloatArray.set",
    "jo.Array.FloatArray.size"   -> "jo.py.runtime.FloatArray.size",

    // ByteArray operations
    "jo.Array.ByteArray.create" -> "jo.py.runtime.ByteArray.create",
    "jo.Array.ByteArray.get"    -> "jo.py.runtime.ByteArray.get",
    "jo.Array.ByteArray.set"    -> "jo.py.runtime.ByteArray.set",
    "jo.Array.ByteArray.size"   -> "jo.py.runtime.ByteArray.size",

    // RefArray operations
    "jo.Array.RefArray.create" -> "jo.py.runtime.RefArray.create",
    "jo.Array.RefArray.get"    -> "jo.py.runtime.RefArray.get",
    "jo.Array.RefArray.set"    -> "jo.py.runtime.RefArray.set",
    "jo.Array.RefArray.size"   -> "jo.py.runtime.RefArray.size",

    // Regex engine hooks
    "jo.regex.Engine.compilePattern" -> "jo.py.runtime.RegexEngine.compilePattern",
    "jo.regex.Engine.execPatternAt"  -> "jo.py.runtime.RegexEngine.execPatternAt",
  )

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, sources) = cli.OptionParser.parseConfig(args, Config.appOptions)

    if sources.isEmpty then
      println("Expect source file as input")
      return

    given Config = config
    config.setInternal(Config.postChecks, List(new PythonPostCheck))

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
