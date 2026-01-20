package js

import common.IO

import sast.*
import phases.*

import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config

import scala.language.implicitConversions

/***********************************************************************
 *
 * Main entry point for the JS compiler
 *
 ***********************************************************************/
object Compiler:
  // Default link mappings for JS runtime
  val defaultLinkMappings = Map(
    "jo.Predef.abort"      -> "jo.runtime.JS.abort",

    // IntArray operations
    "jo.Array.IntArray.create" -> "jo.runtime.JS.IntArray.create",
    "jo.Array.IntArray.get"    -> "jo.runtime.JS.IntArray.get",
    "jo.Array.IntArray.set"    -> "jo.runtime.JS.IntArray.set",
    "jo.Array.IntArray.size"   -> "jo.runtime.JS.IntArray.size",

    // FloatArray operations
    "jo.Array.FloatArray.create" -> "jo.runtime.JS.FloatArray.create",
    "jo.Array.FloatArray.get"    -> "jo.runtime.JS.FloatArray.get",
    "jo.Array.FloatArray.set"    -> "jo.runtime.JS.FloatArray.set",
    "jo.Array.FloatArray.size"   -> "jo.runtime.JS.FloatArray.size",

    // ByteArray operations
    "jo.Array.ByteArray.create" -> "jo.runtime.JS.ByteArray.create",
    "jo.Array.ByteArray.get"    -> "jo.runtime.JS.ByteArray.get",
    "jo.Array.ByteArray.set"    -> "jo.runtime.JS.ByteArray.set",
    "jo.Array.ByteArray.size"   -> "jo.runtime.JS.ByteArray.size",

    // ObjectArray operations
    "jo.Array.ObjectArray.create" -> "jo.runtime.JS.ObjectArray.create",
    "jo.Array.ObjectArray.get"    -> "jo.runtime.JS.ObjectArray.get",
    "jo.Array.ObjectArray.set"    -> "jo.runtime.JS.ObjectArray.set",
    "jo.Array.ObjectArray.size"   -> "jo.runtime.JS.ObjectArray.size",
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
          IO.fileNameNoExt(sources.head) + ".js"
        else
          "out.js"
      }

      val rootNameTable = new NameTable

      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      val runtimes = Config.JSRuntimePath :: Config.runtimePaths.value
      val nss = FrontEnd.run(runtimes, sources, defaultLinkMappings) <| "Frontend"

      locally {
        given Definitions = lazyDefn.value

        val jsRuntime = new JSRuntime
        val contextParamsLower = new LowerContextParams(
            jsRuntime.paramSymbol,
            jsRuntime.hasParam,
            jsRuntime.getParam,
            jsRuntime.setParam,
            jsRuntime.delParam)

        val closureConvert = new ElimCapture
        val viewMaterializer = new phases.MaterializeView
        val backend: Step[List[Trees.Namespace], Unit] =
          Step("Backend", new JSOptimized(outFile, jsRuntime, FrontEnd.rewireMap.value).compile)

        nss                 |>
        closureConvert      |>
        contextParamsLower  |>
        viewMaterializer    |>
        backend
      } <| "Backend"
