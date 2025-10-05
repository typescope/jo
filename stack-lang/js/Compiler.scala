package js

import common.IO

import sast.*
import phases.*
import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config

/***********************************************************************
 *
 * Main entry point for the JS compiler
 *
 ***********************************************************************/
@main
def compile(args: String*): Unit =
  val optionSpec = Config.commonOptionsSpec + ("-o" -> true)

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

  given Config = Config(options)

  Reporter.monitor:

    val rootNameTable = new NameTable

    given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

    val lib = typing.Typer.stdLib
    val runtime = "runtime/JS.stk" :: Nil
    val namespacesSAST = FrontEnd.run(lib, runtime, sources) <| "Frontend"

    val mains = namespacesSAST.collect:
      case ns if ns.mainSymbol.nonEmpty => ns.mainSymbol.get

    mains match
      case main :: Nil => {
        given Definitions = lazyDefn.value

        val jsRuntime = new JSRuntime(rootNameTable, main)
        val contextParamsLower = new LowerContextParams(
            jsRuntime.JS_hasParam,
            jsRuntime.JS_getParam,
            jsRuntime.JS_setParam,
            jsRuntime.JS_delParam)

        val closureConvert = new ElimCapture
        val runtimeLowerer = new LowerRuntime(jsRuntime)
        val backend: Step[List[Trees.Namespace], Unit] =
          Step("Backend", new JSOptimized(outFile, jsRuntime).compile)

        namespacesSAST      |>
        closureConvert      |>
        runtimeLowerer      |>
        contextParamsLower  |>
        backend
      } <| "Backend"

      case _ =>
        if mains.isEmpty then
          Reporter.abortInternal("No main function found")
        else
          Reporter.abortInternal("Multiple main function detected: " + mains)
