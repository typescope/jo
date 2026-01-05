package js

import common.IO

import sast.*
import phases.*

import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config

/***********************************************************************
 *
 * Main entry point for the JS compiler
 *
 ***********************************************************************/
object Compiler:
  // Default link mappings for JS runtime
  val defaultLinkMappings = Map(
    "jo.Predef.abort"      -> "jo.runtime.JS.abort",
    "jo.Array.get"         -> "jo.runtime.JS.Array_get",
    "jo.Array.set"         -> "jo.runtime.JS.Array_set",
    "jo.Array.size"        -> "jo.runtime.JS.Array_size",
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
            jsRuntime.JS_hasParam,
            jsRuntime.JS_getParam,
            jsRuntime.JS_setParam,
            jsRuntime.JS_delParam)

        val closureConvert = new ElimCapture
        val runtimeLowerer = new LowerRuntime(jsRuntime)
        val viewMaterializer = new MaterializeView
        val backend: Step[List[Trees.Namespace], Unit] =
          Step("Backend", new JSOptimized(outFile, jsRuntime, FrontEnd.rewireMap.value).compile)

        nss                 |>
        closureConvert      |>
        runtimeLowerer      |>
        contextParamsLower  |>
        viewMaterializer    |>
        backend
      } <| "Backend"
