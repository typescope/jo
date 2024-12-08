package native

import sast.*
import phases.*
import reporting.Reporter

/***********************************************************************
 *
 * Main entry point for the compiler
 *
 ***********************************************************************/
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

  val backend: (List[Sast.Namespace], Symbols.Symbol) => Unit =
    options.get("-p") match
      case Some(pf) =>
        if pf == "linux-x86-stack" then
          Linux.createX86StackMachine(outFile, layout).compile

        else if pf == "linux-x86-reg" then
          Linux.createX86RegisterMachine(outFile, layout).compile

        else
          throw new Exception("Unknow platform: " + pf)

      case None =>
        Linux.createX86RegisterMachine(outFile, layout).compile

  Reporter.monitor:

    val namespacesSAST =
      Parser.parse(sourceFiles)     |>
      Namer.transform               |+
      Debug.peek(enable = false)

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
        ((nss: List[Sast.Namespace]) => backend(nss, main))

      case _ =>
        if mains.isEmpty then
          Reporter.abortInternal("No main function found")
        else
          Reporter.abortInternal("Multiple main function detected: " + mains)
