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
    "jo.abort"      -> "jo.runtime.Ruby.abort",

    // IntArray operations
    "jo.Array.IntArray.create" -> "jo.runtime.Ruby.IntArray.create",
    "jo.Array.IntArray.get"    -> "jo.runtime.Ruby.IntArray.get",
    "jo.Array.IntArray.set"    -> "jo.runtime.Ruby.IntArray.set",
    "jo.Array.IntArray.size"   -> "jo.runtime.Ruby.IntArray.size",

    // FloatArray operations
    "jo.Array.FloatArray.create" -> "jo.runtime.Ruby.FloatArray.create",
    "jo.Array.FloatArray.get"    -> "jo.runtime.Ruby.FloatArray.get",
    "jo.Array.FloatArray.set"    -> "jo.runtime.Ruby.FloatArray.set",
    "jo.Array.FloatArray.size"   -> "jo.runtime.Ruby.FloatArray.size",

    // ByteArray operations
    "jo.Array.ByteArray.create" -> "jo.runtime.Ruby.ByteArray.create",
    "jo.Array.ByteArray.get"    -> "jo.runtime.Ruby.ByteArray.get",
    "jo.Array.ByteArray.set"    -> "jo.runtime.Ruby.ByteArray.set",
    "jo.Array.ByteArray.size"   -> "jo.runtime.Ruby.ByteArray.size",

    // ObjectArray operations
    "jo.Array.ObjectArray.create" -> "jo.runtime.Ruby.ObjectArray.create",
    "jo.Array.ObjectArray.get"    -> "jo.runtime.Ruby.ObjectArray.get",
    "jo.Array.ObjectArray.set"    -> "jo.runtime.Ruby.ObjectArray.set",
    "jo.Array.ObjectArray.size"   -> "jo.runtime.Ruby.ObjectArray.size",
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

      val runtimes = Config.RubyRuntimePath :: Config.runtimePaths.value
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
