package js

import common.IO
import parsing.Parser
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
          IO.fileNameNoExt(sourceFiles.head)
        else
          "out.js"

  val backend = new JSOptimized(outFile)

  Reporter.monitor:
    val namespacesSAST =
      Parser.parse(sourceFiles)     |>
      Namer.transform               |+
      Printing.peek(enable = false)

    val mains = namespacesSAST.collect:
      case ns if ns.mainSymbol.nonEmpty => ns.mainSymbol.get

    mains match
      case main :: Nil =>
        namespacesSAST                |>
        Printing.peek(enable = false) |>
        new ExplicitInit().transform  |+
        Printing.peek(enable = false) |>
        ElimCapture.transform         |+
        Printing.peek(enable = false) |>
        ((nss: List[Sast.Namespace]) => backend.compile(nss, main))

      case _ =>
        if mains.isEmpty then
          Reporter.abortInternal("No main function found")
        else
          Reporter.abortInternal("Multiple main function detected: " + mains)
