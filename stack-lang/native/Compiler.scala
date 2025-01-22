package native

import ast.Ast
import sast.*
import sast.Symbols.Symbol
import parsing.Parser
import phases.*
import reporting.Reporter
import typing.Namer

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

    val sourceFiles = rest

    val outFile =
      options.get("-o") match
        case Some(file) => file
        case None =>
          if sourceFiles.size == 1 then
            IO.fileNameNoExt(sourceFiles.head)
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
      val typeCheck = (nss: List[Ast.Namespace]) =>
        Namer.transform(nss, stdlib, runtime, rootNameTable, runtimeNameTable)

      val namespacesSAST =
        Parser.parse(sourceFiles)     |>
        typeCheck                     |+
        Printing.peek(enable = false)


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
          Printing.peek(enable = false) |>
          ElimCapture.transform         |+
          TreeChecker.check             |>
          Printing.peek(enable = false) |>
          contextParamsLower.transform  |+
          Printing.peek(enable = false) |>
          runtimeLowerer.transform      |+
          Printing.peek(enable = false) |>
          explicitAlloc.transform       |+
          Printing.peek(enable = false) |>
          backend.compile               |>
          assembler

        case _ =>
          if mains.isEmpty then
            Reporter.abortInternal("No main function found")
          else
            Reporter.abortInternal("Multiple main function detected: " + mains)
      end match
