package js

import common.IO
import parsing.Parser
import ast.Ast
import sast.*
import typing.Namer
import phases.*
import reporting.Reporter

/***********************************************************************
 *
 * Main entry point for the JS compiler
 *
 ***********************************************************************/
@main
def compile(args: String*): Unit =
  val optionSpec = Map(
    "-o" -> true,
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
          IO.fileNameNoExt(sourceFiles.head) + ".js"
        else
          "out.js"

  Reporter.monitor:
    val rootNameTable = new NameTable
    val runtimeNameTable = new NameTable
    val stdlib = "lib/Predef.stk" :: Nil
    val runtime = "runtime/JS.stk" :: Nil

    val typeCheck = (nss: List[Ast.Namespace]) =>
      Namer.transform(nss, stdlib, runtime, rootNameTable, runtimeNameTable)

    val namespacesSAST =
      Parser.parse(sourceFiles)     |>
      typeCheck                     |+
      Printing.peek(enable = false)

    val jsRuntime = new JSRuntime(runtimeNameTable)
    val contextParamsLower = new LowerContextParams(
        jsRuntime.JS_hasParam,
        jsRuntime.JS_getParam,
        jsRuntime.JS_setParam,
        jsRuntime.JS_delParam,
        jsRuntime.JS_newPage,
        jsRuntime.JS_restorePage)

    val runtimeLowerer = new LowerRuntime(jsRuntime)

    val backend = new JSOptimized(outFile, jsRuntime)

    val mains = namespacesSAST.collect:
      case ns if ns.mainSymbol.nonEmpty => ns.mainSymbol.get

    mains match
      case main :: Nil =>
        namespacesSAST                |>
        Printing.peek(enable = false) |>
        ElimCapture.transform         |+
        TreeChecker.check             |>
        Printing.peek(enable = false) |>
        runtimeLowerer.transform      |+
        Printing.peek(enable = false) |>
        contextParamsLower.transform  |+
        Printing.peek(enable = false) |>
        ((nss: List[Sast.Namespace]) => backend.compile(nss, main))

      case _ =>
        if mains.isEmpty then
          Reporter.abortInternal("No main function found")
        else
          Reporter.abortInternal("Multiple main function detected: " + mains)
