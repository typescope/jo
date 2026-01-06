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
    "jo.Predef.abort"      -> "jo.runtime.native.Core.abortImpl",
    "jo.Array.create"      -> "jo.runtime.native.Core.Array_create",
    "jo.Array.get"         -> "jo.runtime.native.Core.Array_get",
    "jo.Array.set"         -> "jo.runtime.native.Core.Array_set",
    "jo.Array.size"        -> "jo.runtime.native.Core.Array_size",

    // GC API wiring can be controlled via options
    "jo.runtime.native.GC.init" -> "jo.runtime.native.BumpAllocator.init",
    "jo.runtime.native.GC.alloc" -> "jo.runtime.native.BumpAllocator.alloc",
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

      val runtimes = Config.NativeRuntimePath :: Config.runtimePaths.value
      val namespacesSAST = FrontEnd.run(runtimes, sources, defaultLinkMappings) <| "Frontend"

      locally {
        given Definitions = lazyDefn.value

        val backend = backendBuilder.createLinux86(FrontEnd.rewireMap.value)
        val backendStep = Step("backend", backend.compile)

        val closureConvert = new ElimCapture
        val contextParamsLower = new native.LowerContextParams(backend.runtime)
        val runtimeLowerer = new native.LowerRuntime(backend.runtime)
        val encodeClass = new native.EncodeClass(backend.runtime)
        val boxing = new native.Boxing(backend.runtime)
        val explicitAlloc = new native.ExplicitAlloc(backend.runtime)

        val assembler = Step("assembler", (prog: Prog) =>
          // println(prog.show)
          Linux.lower(prog, layout.value, outFile, X86, backend.runtime)
        )

        namespacesSAST     |>
        closureConvert     |>
        contextParamsLower |>
        runtimeLowerer     |>
        boxing             |>
        encodeClass        |>
        explicitAlloc      |>
        backendStep        |>
        assembler
      } <| "Backend"
