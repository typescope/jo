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
    "jo.abort"      -> "rb.abort",

    // IntArray operations
    "jo.Array.IntArray.create" -> "rb.IntArray.create",
    "jo.Array.IntArray.get"    -> "rb.IntArray.get",
    "jo.Array.IntArray.set"    -> "rb.IntArray.set",
    "jo.Array.IntArray.size"   -> "rb.IntArray.size",

    // FloatArray operations
    "jo.Array.FloatArray.create" -> "rb.FloatArray.create",
    "jo.Array.FloatArray.get"    -> "rb.FloatArray.get",
    "jo.Array.FloatArray.set"    -> "rb.FloatArray.set",
    "jo.Array.FloatArray.size"   -> "rb.FloatArray.size",

    // ByteArray operations
    "jo.Array.ByteArray.create" -> "rb.ByteArray.create",
    "jo.Array.ByteArray.get"    -> "rb.ByteArray.get",
    "jo.Array.ByteArray.set"    -> "rb.ByteArray.set",
    "jo.Array.ByteArray.size"   -> "rb.ByteArray.size",

    // RefArray operations
    "jo.Array.RefArray.create" -> "rb.RefArray.create",
    "jo.Array.RefArray.get"    -> "rb.RefArray.get",
    "jo.Array.RefArray.set"    -> "rb.RefArray.set",
    "jo.Array.RefArray.size"   -> "rb.RefArray.size",

    // Regex engine hooks
    "jo.regex.Engine.compilePattern" -> "rb.RegexEngine.compilePattern",
    "jo.regex.Engine.execPatternAt"  -> "rb.RegexEngine.execPatternAt",
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
        if Config.noRuntime.value then Config.runtimePaths.value
        else Config.RubyRuntimePath :: Config.runtimePaths.value
      val units = FrontEnd.run(runtimes, sources, defaultLinkMappings) <| "Frontend"

      locally {
        given Definitions = lazyDefn.value

        val rubyRuntime = new RubyRuntime
        val contextParamsLower = new LowerContextParams(
            rubyRuntime.paramKey,
            rubyRuntime.hasParam,
            rubyRuntime.getParam,
            rubyRuntime.setParam,
            rubyRuntime.delParam)

        val closureConvert = new ElimCapture
        val viewMaterializer = new phases.MaterializeView
        val codeGen = new RubyCodeGen(rubyRuntime, FrontEnd.rewireMap.value)
        val backend: Step[List[FileUnit], Unit] =
          Step("Backend", (units: List[FileUnit]) =>
            codeGen.generate(units, outFile)
          )

        units               |>
        closureConvert      |>
        contextParamsLower  |>
        viewMaterializer    |>
        backend
      } <| "Backend"
