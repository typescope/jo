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
object Compiler:
  // Default link mappings for JS runtime
  val defaultLinkMappings = Map(
    "stk.Predef.abort"      -> "stk.runtime.JS.abort",
    "stk.Predef.byteToChar" -> "stk.runtime.JS.byteToChar",
    "stk.Predef.byteToInt"  -> "stk.runtime.JS.byteToInt",
    "stk.Predef.charToByte" -> "stk.runtime.JS.charToByte",
    "stk.Predef.charToInt"  -> "stk.runtime.JS.charToInt",
    "stk.Predef.charToStr"  -> "stk.runtime.JS.charToStr",
    "stk.Predef.intToByte"  -> "stk.runtime.JS.intToByte",
    "stk.Predef.intToChar"  -> "stk.runtime.JS.intToChar",
    "stk.Predef.intToStr"   -> "stk.runtime.JS.intToStr",
    "stk.Array.get"         -> "stk.runtime.JS.Array_get",
    "stk.Array.set"         -> "stk.runtime.JS.Array_set",
    "stk.Array.size"        -> "stk.runtime.JS.Array_size",
  )

  def main(args: Array[String]): Unit =
    given Reporter = Reporter.createReporter()

    val (config, sources) = cli.OptionParser.parseConfig(args, Config.appOptions)
    config.setInternal(Config.mode, Config.Mode.Application)

    if sources.isEmpty then
      println("Expect source file as input")
      return

    given Config = config


    Reporter.monitor():
      val outFile = Config.outFilePath.value.getOrElse{
        if sources.size == 1 then
          IO.fileNameNoExt(sources.head) + ".js"
        else
          "out.js"
      }

      val rootNameTable = new NameTable

      given lazyDefn: Definitions.Lazy = Definitions.Lazy(rootNameTable)

      val runtimes = Config.JSRuntimePath :: Nil
      val linkMappings = Config.linkMap.checkConflicts(Config.linkMap.value, defaultLinkMappings)
      val nss = FrontEnd.run(runtimes, sources, linkMappings) <| "Frontend"

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
