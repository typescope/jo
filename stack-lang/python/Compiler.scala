package python

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
 * Main entry point for the Python compiler
 *
 ***********************************************************************/
object Compiler:
  // Default link mappings for Python runtime
  val defaultLinkMappings = Map(

    "jo.abort"      -> "jo.py.runtime.abort",

    "jo.Array.create" -> "jo.py.runtime.RefArray.create",

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

    Config.useRuntimeApi.value match
      case Some(runtime) =>
        if runtime == "python" then
          config.setInternal(typing.PostCheck.postChecks, List(new PythonPostCheck))

        else
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

      val units = FrontEnd.run(defaultRuntimePackages, sources, defaultLinkMappings, "jo.py.runtime.RefArray") <| "Frontend"

      locally {
        given Definitions = lazyDefn.value

        val pythonRuntime = new PythonRuntime
        val contextParamsLower = new LowerContextParams(pythonRuntime.ParamSupport)
        val erasure = new Erasure(Erasure.allTagged)
        val closureConvert = new ElimCapture
        val viewMaterializer = new phases.MaterializeView
        val rewire  = FrontEnd.rewireMap.value
        val codeGen = new PythonCodeGen(pythonRuntime, rewire)

        val backend: Step[List[FileUnit], Unit] =
          Step("Backend", (units: List[FileUnit]) =>
            codeGen.generate(Universe.filter(units, pythonRuntime.start, rewire, pythonRuntime.intrinsicDeps), outFile)
          )
        units               |>
        contextParamsLower  |>
        erasure             |>
        closureConvert      |>
        viewMaterializer    |>
        backend
      } <| "Backend"
