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

  val optionSpec = Config.commonOptionsSpec ++ Map(
    "-o" -> true,
    "-layout" -> true,
  )

  def compile(backendBuilder: BackendBuilder, args: Array[String]): Unit =
    val (options, sources) = IO.parseOptions(args, optionSpec)

    if sources.isEmpty then
      println("Expect source file as input")
      return

    val outFile =
      options.get("-o") match
        case Some(file) => file
        case None =>
          if sources.size == 1 then
            IO.fileNameNoExt(sources.head)
          else
            "out"

    val layout = options.getOrElse("-layout", "c1")

    val rootNameTable = new NameTable


    given Config = Config(options, Mode.Application)

    Reporter.monitor:
      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      val runtimes = Config.NativeRuntimePath :: Nil
      val namespacesSAST = FrontEnd.run(runtimes, sources) <| "Frontend"

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
