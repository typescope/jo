package native

import sast.*
import sast.Symbols.Symbol
import phases.*

import reporting.Reporter

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
  type BackendBuilder = (NameTable, Symbol) => Backend

  val optionSpec = Map(
    "-o" -> true,
    "-layout" -> true,
  )

  def compile(backendBuilder: BackendBuilder, args: Array[String]): Unit =

    val (options, rest) = IO.parseOptions(args, optionSpec)

    if rest.isEmpty then
      println("Expect source file as input")
      return

    val sources = rest

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
    val runtimeNameTable = new NameTable
    val stdlib = "lib/Predef.stk" :: Nil
    val runtime = List(
      "runtime/native/Core.stk",
      "runtime/native/GC.stk",
      "runtime/native/ParamSupport.stk",
      "runtime/native/Syscall.stk",
      "runtime/native/BumpAllocator.stk",
    )

    Reporter.monitor:
      val namespacesSAST =
        FrontEnd.run(stdlib, runtime, sources, rootNameTable, runtimeNameTable)

      val mains = namespacesSAST.collect:
        case ns if ns.mainSymbol.nonEmpty => ns.mainSymbol.get

      mains match
        case main :: Nil =>
          val backend = backendBuilder(runtimeNameTable, main)

          val contextParamsLower = new native.LowerContextParams(backend.runtime)
          val runtimeLowerer = new native.LowerRuntime(backend.runtime)
          val explicitAlloc = new native.ExplicitAlloc(backend.runtime)

          val assembler = (prog: Prog) =>
            // println(prog.show)
            Linux.lower(prog, layout, outFile, X86, backend.runtime)

          namespacesSAST                |>
          ElimCapture.transform         |+
          TreeChecker.check             |>
          Printing.peek(enable = false) |>
          contextParamsLower.transform  |+
          TreeChecker.check             |>
          Printing.peek(enable = false) |>
          runtimeLowerer.transform      |+
          TreeChecker.check             |>
          Printing.peek(enable = false) |>
          explicitAlloc.transform       |+
          TreeChecker.check             |>
          Printing.peek(enable = false) |>
          backend.compile               |>
          assembler

        case _ =>
          if mains.isEmpty then
            Reporter.abortInternal("No main function found")
          else
            Reporter.abortInternal("Multiple main function detected: " + mains)
      end match
