package native

import sast.*
import sast.Symbols.Symbol
import phases.*

import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config
import reporting.Mode

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

  val additionalOptions = Map(
    "-layout" -> cli.OptionParser.OptionSpec.Single,
  )

  def compile(backendBuilder: BackendBuilder, args: Array[String]): Unit =
    val opts = cli.OptionParser.parseCompilerOptions(args, Mode.Application, additionalOptions)

    if opts.sources.isEmpty then
      println("Expect source file as input")
      return

    val outFile = opts.outFile.getOrElse {
      if opts.sources.size == 1 then
        IO.fileNameNoExt(opts.sources.head)
      else
        "out"
    }

    val layout = cli.OptionParser.getOption(opts.options, "-layout").getOrElse("c1")

    val rootNameTable = new NameTable

    given Config = opts.config

    Reporter.monitor:
      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      val runtimes = Config.NativeRuntimePath :: Nil
      val namespacesSAST = FrontEnd.run(runtimes, opts.sources, opts.linkMappings) <| "Frontend"

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
            Linux.lower(prog, layout, outFile, X86, backend.runtime)
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
