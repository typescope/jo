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

  val layout: Config.StringSetting = Config.StringSetting("--layout", "c1", "memory layout, c1 or c2")

  // Default link mappings for native runtime
  val defaultLinkMappings = Map(
    "jo.abort"      -> "jo.runtime.native.abortImpl",

    "jo.Array.create" -> "jo.runtime.native.RefArray.create",

    "jo.Bytes.size"     -> "jo.runtime.native.RawBytes.size",
    "jo.Bytes.get"      -> "jo.runtime.native.RawBytes.get",
    "jo.Bytes.slice"    -> "jo.runtime.native.RawBytes.slice",
    "jo.Bytes.toBase64" -> "jo.runtime.native.RawBytes.toBase64",
    "jo.Bytes.fill"     -> "jo.runtime.native.RawBytes.fill",

    // Regex engine hooks
    "jo.regex.Engine.compilePattern" -> "jo.runtime.native.regex.Regex.compilePattern",
    "jo.regex.Engine.execPatternAt"  -> "jo.runtime.native.regex.Regex.execPatternAt",

    // GC API wiring can be controlled via options
    "jo.runtime.native.GC.init" -> "jo.runtime.native.BumpAllocator.init",
    "jo.runtime.native.GC.alloc" -> "jo.runtime.native.BumpAllocator.alloc",
  )

  def compile(backendBuilder: BackendBuilder, args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, sources) = cli.OptionParser.parseConfig(args, layout :: Config.appOptions)

    given Config = config

    // Zero source files is allowed when an entry point is linked in from
    // libraries (e.g. an app that only pulls in packages and links jo.main).
    if sources.isEmpty && Config.linkMap.value.isEmpty then
      println("Expect source file as input")
      System.exit(1)

    Config.useRuntimeApi.value match
      case Some(runtime) if runtime != "native" =>
        Reporter.error(s"native backends do not support --use-runtime-api $runtime")
      case _ =>

    Reporter.monitor():
      val outFile = Config.outFilePath.value.getOrElse {
        if sources.size == 1 then
          IO.fileNameNoExt(sources.head)
        else
          "out"
      }

      val rootNameTable = new NameTable
      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      val defaultRuntimePackages =
        if Config.useRuntimeApi.value.contains("native") then Nil
        else Config.NativeRuntimePath :: Nil

      val namespacesSAST = FrontEnd.run(defaultRuntimePackages, sources, defaultLinkMappings, "jo.runtime.native.RefArray") <| "Frontend"

      locally {
        given defn: Definitions = lazyDefn.value

        val backend = backendBuilder.createLinux86(FrontEnd.rewireMap.value)
        val backendStep = Step("backend", backend.compile)

        val untaggedTypes =
          Set(
            defn.Bool_type,
            defn.Byte_type,
            defn.Char_type,
            defn.Int_type,
            defn.Float_type,
          )
        val erasure = new Erasure(Erasure.untaggedTypes(untaggedTypes))
        val closureConvert = new ElimCapture
        val contextParamsLower = new phases.LowerContextParams(backend.runtime.ParamSupport)
        val encodeClass = new native.EncodeClass(backend.runtime)
        val boxing = new native.Boxing(backend.runtime)
        val explicitAlloc = new native.ExplicitAlloc(backend.runtime)

        val assembler = Step("assembler", (prog: Prog) =>
          // println(prog.show)
          Linux.lower(prog, layout.value, outFile, X86, backend.runtime)
        )
        namespacesSAST     |>
        contextParamsLower |>
        erasure            |>
        closureConvert     |>
        boxing             |>
        encodeClass        |>
        explicitAlloc      |>
        backendStep        |>
        assembler
      } <| "Backend"
