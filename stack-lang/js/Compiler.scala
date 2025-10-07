package js

import common.IO

import sast.*
import phases.*

import reporting.Reporter
import reporting.Reporter.Step
import reporting.Config
import reporting.Mode

/***********************************************************************
 *
 * Main entry point for the JS compiler
 *
 ***********************************************************************/
@main
def compile(args: String*): Unit =
  val optionSpec = Config.commonOptionsSpec + ("-o" -> true)

  val (options, sources) = IO.parseOptions(args, optionSpec)

  if sources.isEmpty then
    println("Expect source file as input")
    return

  val outFile =
    options.get("-o") match
      case Some(file) => file
      case None =>
        if sources.size == 1 then
          IO.fileNameNoExt(sources.head) + ".js"
        else
          "out.js"

  given Config = Config(options, Mode.Application)

  Reporter.monitor:

    val rootNameTable = new NameTable

    given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

    val runtimes = Config.JSRuntimePath :: Nil
    val nss = FrontEnd.run(runtimes, sources) <| "Frontend"

    val mains = nss.collect:
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

        nss                 |>
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
