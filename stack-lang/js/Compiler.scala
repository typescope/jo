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

    "jo.Bytes.size"     -> "jo.js.runtime.RefBytes.size",
    "jo.Bytes.get"      -> "jo.js.runtime.RefBytes.get",
    "jo.Bytes.slice"    -> "jo.js.runtime.RefBytes.slice",
    "jo.Bytes.toBase64" -> "jo.js.runtime.RefBytes.toBase64",

    // Regex engine hooks
    "jo.regex.Engine.compilePattern" -> "jo.js.runtime.RegexEngine.compilePattern",
    "jo.regex.Engine.execPatternAt"  -> "jo.js.runtime.RegexEngine.execPatternAt",
  )

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, sources) = cli.OptionParser.parseConfig(args, Config.appOptions)

    given Config = config

    // Zero source files is allowed when an entry point is linked in from
    // libraries (e.g. an app that only pulls in packages and links jo.main).
    if sources.isEmpty && Config.linkMap.value.isEmpty then
      println("Expect source file as input")
      System.exit(1)

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
        val erasure = new Erasure(Erasure.allTagged)
        val closureConvert = new ElimCapture
        val viewMaterializer = new phases.MaterializeView
        val backend: Step[List[FileUnit], Unit] =
          Step("Backend", (units: List[FileUnit]) => {
            val rewire  = FrontEnd.rewireMap.value
            val codegen = new JSCodeGen(jsRuntime, rewire)
            codegen.generate(Universe.filter(units, jsRuntime.start, rewire, jsRuntime.intrinsicDeps), outFile)
          })
        units               |>
        contextParamsLower  |>
        erasure             |>
        closureConvert      |>
        viewMaterializer    |>
        backend
      } <| "Backend"
