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
    "jo.abort"      -> "js.abort",

    // IntArray operations
    "jo.Array.IntArray.create" -> "js.IntArray.create",
    "jo.Array.IntArray.get"    -> "js.IntArray.get",
    "jo.Array.IntArray.set"    -> "js.IntArray.set",
    "jo.Array.IntArray.size"   -> "js.IntArray.size",

    // FloatArray operations
    "jo.Array.FloatArray.create" -> "js.FloatArray.create",
    "jo.Array.FloatArray.get"    -> "js.FloatArray.get",
    "jo.Array.FloatArray.set"    -> "js.FloatArray.set",
    "jo.Array.FloatArray.size"   -> "js.FloatArray.size",

    // ByteArray operations
    "jo.Array.ByteArray.create" -> "js.ByteArray.create",
    "jo.Array.ByteArray.get"    -> "js.ByteArray.get",
    "jo.Array.ByteArray.set"    -> "js.ByteArray.set",
    "jo.Array.ByteArray.size"   -> "js.ByteArray.size",

    // RefArray operations
    "jo.Array.RefArray.create" -> "js.RefArray.create",
    "jo.Array.RefArray.get"    -> "js.RefArray.get",
    "jo.Array.RefArray.set"    -> "js.RefArray.set",
    "jo.Array.RefArray.size"   -> "js.RefArray.size",

    // Regex engine hooks
    "jo.regex.Engine.compilePattern" -> "js.RegexEngine.compilePattern",
    "jo.regex.Engine.execPatternAt"  -> "js.RegexEngine.execPatternAt",
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
      val units = FrontEnd.run(runtimes, sources, defaultLinkMappings) <| "Frontend"

      locally {
        given Definitions = lazyDefn.value

        val jsRuntime = new JSRuntime
        val contextParamsLower = new LowerContextParams(
            jsRuntime.paramKey,
            jsRuntime.hasParam,
            jsRuntime.getParam,
            jsRuntime.setParam,
            jsRuntime.delParam)

        val closureConvert = new ElimCapture
        val viewMaterializer = new phases.MaterializeView
        val backend: Step[List[FileUnit], Unit] =
          Step("Backend", (units: List[FileUnit]) => {
            val codegen = new JSCodeGen(jsRuntime, FrontEnd.rewireMap.value)
            codegen.generate(units, outFile)
          })

        units               |>
        closureConvert      |>
        contextParamsLower  |>
        viewMaterializer    |>
        backend
      } <| "Backend"
