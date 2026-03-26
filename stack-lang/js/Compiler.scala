package js

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
 * Main entry point for the JS compiler
 *
 ***********************************************************************/
object Compiler:
  // Default link mappings for JS runtime
  val defaultLinkMappings = Map(
    "jo.abort"      -> "jo.runtime.js.abort",

    // IntArray operations
    "jo.Array.IntArray.create" -> "jo.runtime.js.IntArray.create",
    "jo.Array.IntArray.get"    -> "jo.runtime.js.IntArray.get",
    "jo.Array.IntArray.set"    -> "jo.runtime.js.IntArray.set",
    "jo.Array.IntArray.size"   -> "jo.runtime.js.IntArray.size",

    // FloatArray operations
    "jo.Array.FloatArray.create" -> "jo.runtime.js.FloatArray.create",
    "jo.Array.FloatArray.get"    -> "jo.runtime.js.FloatArray.get",
    "jo.Array.FloatArray.set"    -> "jo.runtime.js.FloatArray.set",
    "jo.Array.FloatArray.size"   -> "jo.runtime.js.FloatArray.size",

    // ByteArray operations
    "jo.Array.ByteArray.create" -> "jo.runtime.js.ByteArray.create",
    "jo.Array.ByteArray.get"    -> "jo.runtime.js.ByteArray.get",
    "jo.Array.ByteArray.set"    -> "jo.runtime.js.ByteArray.set",
    "jo.Array.ByteArray.size"   -> "jo.runtime.js.ByteArray.size",

    // RefArray operations
    "jo.Array.RefArray.create" -> "jo.runtime.js.RefArray.create",
    "jo.Array.RefArray.get"    -> "jo.runtime.js.RefArray.get",
    "jo.Array.RefArray.set"    -> "jo.runtime.js.RefArray.set",
    "jo.Array.RefArray.size"   -> "jo.runtime.js.RefArray.size",

    // Regex engine hooks
    "jo.regex.Engine.compilePattern" -> "jo.runtime.js.RegexEngine.compilePattern",
    "jo.regex.Engine.execPatternAt"  -> "jo.runtime.js.RegexEngine.execPatternAt",
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

      val runtimes =
        if Config.noRuntime.value then Config.linkLibPaths.value
        else Config.JSRuntimePath :: Config.linkLibPaths.value
      val units = FrontEnd.run(runtimes, sources, defaultLinkMappings) <| "Frontend"

      locally {
        given Definitions = lazyDefn.value

        val jsRuntime = new JSRuntime
        val contextParamsLower = new LowerContextParams(
            jsRuntime.paramKey,
            jsRuntime.emptyCtx,
            jsRuntime.getParam,
            jsRuntime.startBatch,
            jsRuntime.addBinding,
            jsRuntime.finishBatch)

        val closureConvert = new ElimCapture
        val viewMaterializer = new phases.MaterializeView
        val backend: Step[List[FileUnit], Unit] =
          Step("Backend", (units: List[FileUnit]) => {
            val codegen = new JSCodeGen(jsRuntime, FrontEnd.rewireMap.value)
            codegen.generate(units, outFile)
          })
        units               |>
        contextParamsLower  |>
        closureConvert      |>
        viewMaterializer    |>
        backend
      } <| "Backend"
