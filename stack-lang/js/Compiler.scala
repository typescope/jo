package js

import common.IO
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

  val sources = rest

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

    val namespacesSAST =
      FrontEnd.run(stdlib, runtime, sources, rootNameTable, runtimeNameTable)

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
