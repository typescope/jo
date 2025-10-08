package native

import sast.*
import phases.*

import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config

import common.IO

import native.Assembly.Prog
import native.os.Linux
import native.arch.X86

/***********************************************************************
 *
 * Main entry point for the compiler
 *
 ***********************************************************************/
object Compiler:
  trait BackendBuilder:
    def createLinux86()(using Reporter, Definitions): Backend

  val layout: Config.StringSetting = Config.StringSetting("-layout", "c1", "memory layout, c1 or c2")

  // Default link mappings for native runtime
  val defaultLinkMappings = Map(
    "stk.Predef.abort"      -> "stk.runtime.native.Core.abortImpl",
    "stk.Predef.byteToChar" -> "stk.runtime.native.Core.byteToChar",
    "stk.Predef.byteToInt"  -> "stk.runtime.native.Core.byteToInt",
    "stk.Predef.charToByte" -> "stk.runtime.native.Core.charToByte",
    "stk.Predef.charToInt"  -> "stk.runtime.native.Core.charToInt",
    "stk.Predef.charToStr"  -> "stk.runtime.native.Core.charToStr",
    "stk.Predef.intToByte"  -> "stk.runtime.native.Core.intToByte",
    "stk.Predef.intToChar"  -> "stk.runtime.native.Core.intToChar",
    "stk.Predef.intToStr"   -> "stk.runtime.native.Core.intToStr",
    "stk.Array.create"      -> "stk.runtime.native.Core.Array_create",
    "stk.Array.get"         -> "stk.runtime.native.Core.Array_get",
    "stk.Array.set"         -> "stk.runtime.native.Core.Array_set",
    "stk.Array.size"        -> "stk.runtime.native.Core.Array_size",
  )

  def compile(backendBuilder: BackendBuilder, args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, sources) = cli.OptionParser.parseConfig(args, layout :: Config.appOptions)
    config.setInternal(Config.mode, Config.Mode.Application)

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

      val runtimes = Config.NativeRuntimePath :: Nil
      val namespacesSAST = FrontEnd.run(runtimes, sources, defaultLinkMappings) <| "Frontend"

      locally {
        given Definitions = lazyDefn.value

        val backend = backendBuilder.createLinux86()
        val backendStep = Step("backend", backend.compile)

        val closureConvert = new ElimCapture
        val contextParamsLower = new native.LowerContextParams(backend.runtime)
        val runtimeLowerer = new native.LowerRuntime(backend.runtime)
        val encodeClass = new native.EncodeClass
        val explicitAlloc = new native.ExplicitAlloc(backend.runtime)

        val assembler = Step("assembler", (prog: Prog) =>
          // println(prog.show)
          Linux.lower(prog, layout.value, outFile, X86, backend.runtime)
        )

        namespacesSAST     |>
        closureConvert     |>
        contextParamsLower |>
        runtimeLowerer     |>
        encodeClass        |>
        explicitAlloc      |>
        backendStep        |>
        assembler
      } <| "Backend"
