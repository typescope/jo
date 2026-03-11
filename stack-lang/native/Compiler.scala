package native

import sast.*
import sast.Symbols.Symbol
import phases.*

import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config

import common.IO

import native.Assembly.Prog
import native.os.Linux
import native.arch.X86

import scala.language.implicitConversions

/***********************************************************************
 *
 * Main entry point for the compiler
 *
 ***********************************************************************/
object Compiler:
  trait BackendBuilder:
    def createLinux86(rewire: Map[Symbol, Symbol])(using Reporter, Definitions): Backend

  val layout: Config.StringSetting = Config.StringSetting("-layout", "c1", "memory layout, c1 or c2")

  // Default link mappings for native runtime
  val defaultLinkMappings = Map(
    "jo.abort"      -> "native.abortImpl",

    // IntArray operations
    "jo.Array.IntArray.create" -> "native.IntArray.create",
    "jo.Array.IntArray.get"    -> "native.IntArray.get",
    "jo.Array.IntArray.set"    -> "native.IntArray.set",
    "jo.Array.IntArray.size"   -> "native.IntArray.size",

    // FloatArray operations
    "jo.Array.FloatArray.create" -> "native.FloatArray.create",
    "jo.Array.FloatArray.get"    -> "native.FloatArray.get",
    "jo.Array.FloatArray.set"    -> "native.FloatArray.set",
    "jo.Array.FloatArray.size"   -> "native.FloatArray.size",

    // ByteArray operations
    "jo.Array.ByteArray.create" -> "native.ByteArray.create",
    "jo.Array.ByteArray.get"    -> "native.ByteArray.get",
    "jo.Array.ByteArray.set"    -> "native.ByteArray.set",
    "jo.Array.ByteArray.size"   -> "native.ByteArray.size",

    // RefArray operations
    "jo.Array.RefArray.create" -> "native.RefArray.create",
    "jo.Array.RefArray.get"    -> "native.RefArray.get",
    "jo.Array.RefArray.set"    -> "native.RefArray.set",
    "jo.Array.RefArray.size"   -> "native.RefArray.size",

    // Regex engine hooks
    "jo.regex.Engine.compilePattern" -> "native.regex.Regex.compilePattern",
    "jo.regex.Engine.execPatternAt"  -> "native.regex.Regex.execPatternAt",

    // GC API wiring can be controlled via options
    "native.GC.init" -> "native.BumpAllocator.init",
    "native.GC.alloc" -> "native.BumpAllocator.alloc",
  )

  def compile(backendBuilder: BackendBuilder, args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, sources) = cli.OptionParser.parseConfig(args, layout :: Config.appOptions)

    if sources.isEmpty then
      println("Expect source file as input")
      return

    given Config = config

    Reporter.monitor():
      val outFile = Config.outFilePath.value.getOrElse {
        if sources.size == 1 then
          IO.fileNameNoExt(sources.head)
        else
          "out"
      }

      val rootNameTable = new NameTable
      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      val runtimes =
        if Config.noRuntime.value then Config.runtimePaths.value
        else Config.NativeRuntimePath :: Config.runtimePaths.value
      val namespacesSAST = FrontEnd.run(runtimes, sources, defaultLinkMappings) <| "Frontend"

      locally {
        given Definitions = lazyDefn.value

        val backend = backendBuilder.createLinux86(FrontEnd.rewireMap.value)
        val backendStep = Step("backend", backend.compile)

        val closureConvert = new ElimCapture
        val contextParamsLower = new phases.LowerContextParams(
            backend.runtime.ParamSupport_paramKey,
            backend.runtime.ParamSupport_emptyCtx,
            backend.runtime.ParamSupport_getParam,
            backend.runtime.ParamSupport_bindParam)
        val runtimeLowerer = new native.LowerRuntime(backend.runtime)
        val encodeClass = new native.EncodeClass(backend.runtime)
        val boxing = new native.Boxing(backend.runtime)
        val explicitAlloc = new native.ExplicitAlloc(backend.runtime)

        val assembler = Step("assembler", (prog: Prog) =>
          // println(prog.show)
          Linux.lower(prog, layout.value, outFile, X86, backend.runtime)
        )
        namespacesSAST     |>
        contextParamsLower |>
        closureConvert     |>
        runtimeLowerer     |>
        boxing             |>
        encodeClass        |>
        explicitAlloc      |>
        backendStep        |>
        assembler
      } <| "Backend"
