package js

import common.IO

import sast.*
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
    "-fatal-warnings" -> false,
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
        if sources.size == 1 then
          IO.fileNameNoExt(sources.head) + ".js"
        else
          "out.js"

  Reporter.monitor:
    given Reporter.Config = Reporter.Config(options.contains("-fatal-warnings"))

    val rootNameTable = new NameTable

    given lazyDefn: Definitions.Lazy = new Definitions.Lazy(rootNameTable)

    val runtime = "runtime/JS.stk" :: Nil
    val namespacesSAST = FrontEnd.run(runtime, sources)

    val mains = namespacesSAST.collect:
      case ns if ns.mainSymbol.nonEmpty => ns.mainSymbol.get

    mains match
      case main :: Nil =>
        given Definitions = lazyDefn.value

        val jsRuntime = new JSRuntime(rootNameTable, main)
        val contextParamsLower = new LowerContextParams(
            jsRuntime.JS_hasParam,
            jsRuntime.JS_getParam,
            jsRuntime.JS_setParam,
            jsRuntime.JS_delParam)

        val closureConvert = new ElimCapture
        val runtimeLowerer = new LowerRuntime(jsRuntime)
        val backend = new JSOptimized(outFile, jsRuntime)

        namespacesSAST                |>
        closureConvert.transform      |+
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
