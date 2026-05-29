package js

import common.IO

import sast.*
import sast.Trees.FileUnit
import sast.Universe
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

    "jo.Array.create" -> "jo.js.runtime.RefArray.create",

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
          config.setInternal(typing.PostCheck.postChecks, List(new JSPostCheck))
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

      val units = FrontEnd.run(defaultRuntimePackages, sources, defaultLinkMappings, "jo.js.runtime.RefArray") <| "Frontend"

      locally {
        given Definitions = lazyDefn.value

        val jsRuntime = new JSRuntime
        val contextParamsLower = new LowerContextParams(jsRuntime.ParamSupport)
        val erasure = new Erasure(primitiveTagged = true)
        val closureConvert = new ElimCapture
        val viewMaterializer = new phases.MaterializeView
        val backend: Step[List[FileUnit], Unit] =
          Step("Backend", (units: List[FileUnit]) => {
            val roots   = jsRuntime.start :: jsRuntime.extraRoots
            val codegen = new JSCodeGen(jsRuntime, FrontEnd.rewireMap.value)
            codegen.generate(Universe.filter(units, roots), outFile)
          })
        units               |>
        contextParamsLower  |>
        erasure             |>
        closureConvert      |>
        viewMaterializer    |>
        backend
      } <| "Backend"
