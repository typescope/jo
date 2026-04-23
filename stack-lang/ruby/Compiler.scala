package ruby

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
 * Main entry point for the Ruby compiler
 *
 ***********************************************************************/
object Compiler:
  // Default link mappings for Ruby runtime
  val defaultLinkMappings = Map(
    "jo.abort"      -> "jo.rb.runtime.abort",

    // IntArray operations
    "jo.Array.IntArray.create" -> "jo.rb.runtime.IntArray.create",
    "jo.Array.IntArray.get"    -> "jo.rb.runtime.IntArray.get",
    "jo.Array.IntArray.set"    -> "jo.rb.runtime.IntArray.set",
    "jo.Array.IntArray.size"   -> "jo.rb.runtime.IntArray.size",

    // FloatArray operations
    "jo.Array.FloatArray.create" -> "jo.rb.runtime.FloatArray.create",
    "jo.Array.FloatArray.get"    -> "jo.rb.runtime.FloatArray.get",
    "jo.Array.FloatArray.set"    -> "jo.rb.runtime.FloatArray.set",
    "jo.Array.FloatArray.size"   -> "jo.rb.runtime.FloatArray.size",

    // ByteArray operations
    "jo.Array.ByteArray.create" -> "jo.rb.runtime.ByteArray.create",
    "jo.Array.ByteArray.get"    -> "jo.rb.runtime.ByteArray.get",
    "jo.Array.ByteArray.set"    -> "jo.rb.runtime.ByteArray.set",
    "jo.Array.ByteArray.size"   -> "jo.rb.runtime.ByteArray.size",

    // RefArray operations
    "jo.Array.RefArray.create" -> "jo.rb.runtime.RefArray.create",
    "jo.Array.RefArray.get"    -> "jo.rb.runtime.RefArray.get",
    "jo.Array.RefArray.set"    -> "jo.rb.runtime.RefArray.set",
    "jo.Array.RefArray.size"   -> "jo.rb.runtime.RefArray.size",

    // Regex engine hooks
    "jo.regex.Engine.compilePattern" -> "jo.rb.runtime.RegexEngine.compilePattern",
    "jo.regex.Engine.execPatternAt"  -> "jo.rb.runtime.RegexEngine.execPatternAt",
  )

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, sources) = cli.OptionParser.parseConfig(args, Config.appOptions)

    if sources.isEmpty then
      println("Expect source file as input")
      return

    given Config = config
    config.setInternal(Config.postChecks, List(new RubyPostCheck))

    Config.useRuntimeApi.value match
      case Some(runtime) if runtime != "ruby" =>
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
      val units = FrontEnd.run(defaultRuntimePackages, sources, defaultLinkMappings) <| "Frontend"

      locally {
        given Definitions = lazyDefn.value

        val rubyRuntime = new RubyRuntime
        val contextParamsLower = new LowerContextParams(
            rubyRuntime.paramKey,
            rubyRuntime.emptyCtx,
            rubyRuntime.getParam,
            rubyRuntime.startBatch,
            rubyRuntime.addBinding,
            rubyRuntime.finishBatch)

        val closureConvert = new ElimCapture
        val viewMaterializer = new phases.MaterializeView
        val codeGen = new RubyCodeGen(rubyRuntime, FrontEnd.rewireMap.value)
        val backend: Step[List[FileUnit], Unit] =
          Step("Backend", (units: List[FileUnit]) =>
            codeGen.generate(units, outFile)
          )
        units               |>
        contextParamsLower  |>
        closureConvert      |>
        viewMaterializer    |>
        backend
      } <| "Backend"
