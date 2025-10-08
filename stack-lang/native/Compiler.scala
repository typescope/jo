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

/***********************************************************************
 *
 * Main entry point for the compiler
 *
 ***********************************************************************/
object Compiler:
  trait BackendBuilder:
    def createLinux86(main: Symbol)(using Reporter, Definitions): Backend

  val layout: Config.StringSetting = Config.StringSetting("-layout", "c1", "memory layout, c1 or c2")

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
      val namespacesSAST = FrontEnd.run(runtimes, sources, Config.linkMap.value) <| "Frontend"

      val mains = namespacesSAST.collect:
        case ns if ns.mainSymbol.nonEmpty => ns.mainSymbol.get

      mains match
        case main :: Nil => {
          given Definitions = lazyDefn.value

          val backend = backendBuilder.createLinux86(main)
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

        case _ =>
          if mains.isEmpty then
            Reporter.abortInternal("No main function found")
          else
            Reporter.abortInternal("Multiple main function detected: " + mains)
      end match
