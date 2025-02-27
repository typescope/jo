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

    val noramlizer = new phases.NormalizeParams

    val namespacesSAST =
      Parser.parse(sourceFiles)     |>
      typeCheck                     |+
      Printing.peek(enable = false) |>
      noramlizer.transform          |+
      Printing.peek(enable = false)

    val mains = namespacesSAST.collect:
      case ns if ns.mainSymbol.nonEmpty => ns.mainSymbol.get

    mains match
      case main :: Nil =>
        val jsRuntime = new JSRuntime(runtimeNameTable, main)
        val contextParamsLower = new LowerContextParams(
            jsRuntime.JS_hasParam,
            jsRuntime.JS_getParam,
            jsRuntime.JS_setParam,
            jsRuntime.JS_delParam)

        val runtimeLowerer = new LowerRuntime(jsRuntime)
        val backend = new JSOptimized(outFile, jsRuntime)

        namespacesSAST                |>
        Printing.peek(enable = false) |>
        ElimCapture.transform         |+
        TreeChecker.check             |>
        Printing.peek(enable = false) |>
        runtimeLowerer.transform      |+
        TreeChecker.check             |>
        Printing.peek(enable = false) |>
        contextParamsLower.transform  |+
        TreeChecker.check             |>
        Printing.peek(enable = false) |>
        backend.compile

      case _ =>
        if mains.isEmpty then
          Reporter.abortInternal("No main function found")
        else
          Reporter.abortInternal("Multiple main function detected: " + mains)
