package python

import common.IO

import sast.*
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
    // The internal abort is intrinsified -- the binding is necessary for deferred functions
    "jo.Predef.abort"      -> "jo.Internal.abort",

    // IntArray operations
    "jo.Array.IntArray.create" -> "jo.runtime.Python.IntArray.create",
    "jo.Array.IntArray.get"    -> "jo.runtime.Python.IntArray.get",
    "jo.Array.IntArray.set"    -> "jo.runtime.Python.IntArray.set",
    "jo.Array.IntArray.size"   -> "jo.runtime.Python.IntArray.size",

    // FloatArray operations
    "jo.Array.FloatArray.create" -> "jo.runtime.Python.FloatArray.create",
    "jo.Array.FloatArray.get"    -> "jo.runtime.Python.FloatArray.get",
    "jo.Array.FloatArray.set"    -> "jo.runtime.Python.FloatArray.set",
    "jo.Array.FloatArray.size"   -> "jo.runtime.Python.FloatArray.size",

    // ByteArray operations
    "jo.Array.ByteArray.create" -> "jo.runtime.Python.ByteArray.create",
    "jo.Array.ByteArray.get"    -> "jo.runtime.Python.ByteArray.get",
    "jo.Array.ByteArray.set"    -> "jo.runtime.Python.ByteArray.set",
    "jo.Array.ByteArray.size"   -> "jo.runtime.Python.ByteArray.size",

    // ObjectArray operations
    "jo.Array.ObjectArray.create" -> "jo.runtime.Python.ObjectArray.create",
    "jo.Array.ObjectArray.get"    -> "jo.runtime.Python.ObjectArray.get",
    "jo.Array.ObjectArray.set"    -> "jo.runtime.Python.ObjectArray.set",
    "jo.Array.ObjectArray.size"   -> "jo.runtime.Python.ObjectArray.size",
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

      val runtimes = Config.PythonRuntimePath :: Config.runtimePaths.value
      val nss = FrontEnd.run(runtimes, sources, defaultLinkMappings) <| "Frontend"

      locally {
        given Definitions = lazyDefn.value

        val pythonRuntime = new PythonRuntime
        val contextParamsLower = new LowerContextParams(
            pythonRuntime.paramKey,
            pythonRuntime.hasParam,
            pythonRuntime.getParam,
            pythonRuntime.setParam,
            pythonRuntime.delParam)

        val closureConvert = new ElimCapture
        val viewMaterializer = new phases.MaterializeView
        val codeGen = new PythonCodeGen(pythonRuntime, FrontEnd.rewireMap.value)
        val backend: Step[List[Trees.Namespace], Unit] =
          Step("Backend", (nss: List[Trees.Namespace]) =>
            codeGen.generate(nss, outFile)
          )

        nss                 |>
        closureConvert      |>
        contextParamsLower  |>
        viewMaterializer    |>
        backend
      } <| "Backend"
