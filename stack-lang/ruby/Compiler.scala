package ruby

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
 * Main entry point for the Ruby compiler
 *
 ***********************************************************************/
object Compiler:
  // Default link mappings for Ruby runtime
  val defaultLinkMappings = Map(
    "jo.abort"      -> "jo.rb.runtime.abort",

    "jo.Array.create" -> "jo.rb.runtime.RefArray.create",

    "jo.Bytes.size"     -> "jo.rb.runtime.RawBytes.size",
    "jo.Bytes.get"      -> "jo.rb.runtime.RawBytes.get",
    "jo.Bytes.slice"    -> "jo.rb.runtime.RawBytes.slice",
    "jo.Bytes.toBase64" -> "jo.rb.runtime.RawBytes.toBase64",
    "jo.Bytes.fill"     -> "jo.rb.runtime.RawBytes.fill",

    // Regex engine hooks
    "jo.regex.Engine.compilePattern" -> "jo.rb.runtime.RegexEngine.compilePattern",
    "jo.regex.Engine.execPatternAt"  -> "jo.rb.runtime.RegexEngine.execPatternAt",
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
        if runtime == "ruby" then
          config.setInternal(typing.PostCheck.postChecks, List(new RubyPostCheck))
        else
          Reporter.error(s"--ruby does not support --use-runtime-api $runtime")
      case _ =>

    Reporter.monitor():
      val outFile = Config.outFilePath.value.getOrElse{
        if sources.size == 1 then
          IO.fileNameNoExt(sources.head) + ".rb"
        else
          "out.rb"
      }

      val rootNameTable = new NameTable

      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      val defaultRuntimePackages =
        if Config.useRuntimeApi.value.contains("ruby") then Nil
        else Config.RubyRuntimePath :: Nil

      val units = FrontEnd.run(defaultRuntimePackages, sources, defaultLinkMappings, "jo.rb.runtime.RefArray") <| "Frontend"

      locally {
        given Definitions = lazyDefn.value

        val rubyRuntime = new RubyRuntime
        val contextParamsLower = new LowerContextParams(rubyRuntime.ParamSupport)
        val erasure = new Erasure(Erasure.allTagged)
        val closureConvert = new ElimCapture
        val viewMaterializer = new phases.MaterializeView
        val rewire  = FrontEnd.rewireMap.value
        val codeGen = new RubyCodeGen(rubyRuntime, rewire)

        val backend: Step[List[FileUnit], Unit] =
          Step("Backend", (units: List[FileUnit]) =>
            codeGen.generate(Universe.filter(units, rubyRuntime.start, rewire, rubyRuntime.intrinsicDeps), outFile)
          )
        units               |>
        contextParamsLower  |>
        erasure             |>
        closureConvert      |>
        viewMaterializer    |>
        backend
      } <| "Backend"
