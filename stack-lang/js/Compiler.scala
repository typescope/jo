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
    "jo.abort"      -> "jo.js.runtime.abort",

    // IntArray operations
    "jo.Array.IntArray.create" -> "jo.js.runtime.IntArray.create",
    "jo.Array.IntArray.get"    -> "jo.js.runtime.IntArray.get",
    "jo.Array.IntArray.set"    -> "jo.js.runtime.IntArray.set",
    "jo.Array.IntArray.size"   -> "jo.js.runtime.IntArray.size",

    // FloatArray operations
    "jo.Array.FloatArray.create" -> "jo.js.runtime.FloatArray.create",
    "jo.Array.FloatArray.get"    -> "jo.js.runtime.FloatArray.get",
    "jo.Array.FloatArray.set"    -> "jo.js.runtime.FloatArray.set",
    "jo.Array.FloatArray.size"   -> "jo.js.runtime.FloatArray.size",

    // ByteArray operations
    "jo.Array.ByteArray.create" -> "jo.js.runtime.ByteArray.create",
    "jo.Array.ByteArray.get"    -> "jo.js.runtime.ByteArray.get",
    "jo.Array.ByteArray.set"    -> "jo.js.runtime.ByteArray.set",
    "jo.Array.ByteArray.size"   -> "jo.js.runtime.ByteArray.size",

    // RefArray operations
    "jo.Array.RefArray.create" -> "jo.js.runtime.RefArray.create",
    "jo.Array.RefArray.get"    -> "jo.js.runtime.RefArray.get",
    "jo.Array.RefArray.set"    -> "jo.js.runtime.RefArray.set",
    "jo.Array.RefArray.size"   -> "jo.js.runtime.RefArray.size",

    // Regex engine hooks
    "jo.regex.Engine.compilePattern" -> "jo.js.runtime.RegexEngine.compilePattern",
    "jo.regex.Engine.execPatternAt"  -> "jo.js.runtime.RegexEngine.execPatternAt",
  )

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, sources) = cli.OptionParser.parseConfig(args, Config.appOptions)

    if sources.isEmpty then
      println("Expect source file as input")
      return

    given Config = config

    Config.useRuntimeApi.value match
      case Some(runtime) =>
        if runtime == "js" then
          config.setInternal(Config.postChecks, List(new JSPostCheck))
        else
          Reporter.error(s"--js does not support --use-runtime-api $runtime")

      case _ =>

    Reporter.monitor():
      val outFile = Config.outFilePath.value.getOrElse{
        if sources.size == 1 then
          IO.fileNameNoExt(sources.head) + ".js"
        else
          "out.js"
      }

      val rootNameTable = new NameTable

      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      val defaultRuntimePackages =
        if Config.useRuntimeApi.value.contains("js") then Nil
        else Config.JSRuntimePath :: Nil
      val units = FrontEnd.run(defaultRuntimePackages, sources, defaultLinkMappings) <| "Frontend"

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
