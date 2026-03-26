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
    "jo.abort"      -> "jo.runtime.rb.abort",

    // IntArray operations
    "jo.Array.IntArray.create" -> "jo.runtime.rb.IntArray.create",
    "jo.Array.IntArray.get"    -> "jo.runtime.rb.IntArray.get",
    "jo.Array.IntArray.set"    -> "jo.runtime.rb.IntArray.set",
    "jo.Array.IntArray.size"   -> "jo.runtime.rb.IntArray.size",

    // FloatArray operations
    "jo.Array.FloatArray.create" -> "jo.runtime.rb.FloatArray.create",
    "jo.Array.FloatArray.get"    -> "jo.runtime.rb.FloatArray.get",
    "jo.Array.FloatArray.set"    -> "jo.runtime.rb.FloatArray.set",
    "jo.Array.FloatArray.size"   -> "jo.runtime.rb.FloatArray.size",

    // ByteArray operations
    "jo.Array.ByteArray.create" -> "jo.runtime.rb.ByteArray.create",
    "jo.Array.ByteArray.get"    -> "jo.runtime.rb.ByteArray.get",
    "jo.Array.ByteArray.set"    -> "jo.runtime.rb.ByteArray.set",
    "jo.Array.ByteArray.size"   -> "jo.runtime.rb.ByteArray.size",

    // RefArray operations
    "jo.Array.RefArray.create" -> "jo.runtime.rb.RefArray.create",
    "jo.Array.RefArray.get"    -> "jo.runtime.rb.RefArray.get",
    "jo.Array.RefArray.set"    -> "jo.runtime.rb.RefArray.set",
    "jo.Array.RefArray.size"   -> "jo.runtime.rb.RefArray.size",

    // Regex engine hooks
    "jo.regex.Engine.compilePattern" -> "jo.runtime.rb.RegexEngine.compilePattern",
    "jo.regex.Engine.execPatternAt"  -> "jo.runtime.rb.RegexEngine.execPatternAt",
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
          IO.fileNameNoExt(sources.head) + ".rb"
        else
          "out.rb"
      }

      val rootNameTable = new NameTable

      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      val runtimes =
        if Config.noRuntime.value then Config.linkLibPaths.value
        else Config.RubyRuntimePath :: Config.linkLibPaths.value
      val units = FrontEnd.run(runtimes, sources, defaultLinkMappings) <| "Frontend"

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
