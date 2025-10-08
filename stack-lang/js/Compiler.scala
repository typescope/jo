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
def compile(args: Array[String]): Unit =
  val opts = cli.OptionParser.parseCompilerOptions(args, Mode.Application)

  if opts.sources.isEmpty then
    println("Expect source file as input")
    return

  val outFile = opts.outFile.getOrElse {
    if opts.sources.size == 1 then
      IO.fileNameNoExt(opts.sources.head) + ".js"
    else
      "out.js"
  }

  given Config = opts.config

  Reporter.monitor:

    val rootNameTable = new NameTable

    given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

    val runtimes = Config.JSRuntimePath :: Nil
    val nss = FrontEnd.run(runtimes, opts.sources, opts.linkMappings) <| "Frontend"

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
