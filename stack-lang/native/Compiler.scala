package native

import ast.Ast
import sast.*
import parsing.Parser
import phases.*
import reporting.Reporter
import typing.Namer

import common.IO

import native.Assembly.Prog
import native.os.Linux
import native.cpu.X86

/***********************************************************************
 *
 * Main entry point for the compiler
 *
 ***********************************************************************/

def createBackend(options: Map[String, String], runtimeNameTable: NameTable): Backend =
  options.get("-p") match
    case Some(pf) =>
      if pf == "linux-x86-stack" then
        Linux.createX86StackMachine(runtimeNameTable)

      else if pf == "linux-x86-reg" then
        Linux.createX86RegisterMachine(runtimeNameTable)

      else
        throw new Exception("Unknow platform: " + pf)

    case None =>
      Linux.createX86RegisterMachine(runtimeNameTable)

@main
def compile(args: String*): Unit =
  val optionSpec = Map(
    "-o" -> true,
    "-p" -> true,
    "-layout" -> true,
  )

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
        val backend = createBackend(options, runtimeNameTable)

        val assembler = (prog: Prog) =>
          Linux.lower(prog, layout, outFile, X86, backend.runtime)

        namespacesSAST                |>
        Printing.peek(enable = false) |>
        new ExplicitInit().transform  |+
        Printing.peek(enable = false) |>
        ElimCapture.transform         |+
        Printing.peek(enable = false) |>
        ((nss: List[Sast.Namespace]) => backend.compile(nss, main)) |>
        assembler

      case _ =>
        if mains.isEmpty then
          Reporter.abortInternal("No main function found")
        else
          Reporter.abortInternal("Multiple main function detected: " + mains)
