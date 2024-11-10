/***********************************************************************
 *
 * Main entry point for the compiler
 *
 ***********************************************************************/
@main
def compile(args: String*) =
  val optionSpec = Map(
    "-o" -> true,
    "-p" -> true,
    "-layout" -> true, // only valid for linux-x86 platform
  )

  val (options, rest) = IO.parseOptions(args, optionSpec)

  assert(rest.size == 1, "Expect a single source file as input, found = " + rest)
  val sourceFile = rest.head

  val outFile =
    options.get("-o") match
      case Some(file) => file
      case None =>
        val tokens = sourceFile.split("\\.(?=[^\\.]+$)")
        tokens(0)

  val layout = options.getOrElse("-layout", "c1")

  val backend: (Sast.Namespace, Symbols.Symbol) => Unit =
    options.get("-p") match
      case Some(pf) =>
        if pf == "linux-x86-stack" then
          Linux.createX86StackMachine(outFile, layout).compile

        else if pf == "linux-x86-reg" then
          Linux.createX86RegisterMachine(outFile, layout).compile

        else if pf == "js" then
          new JSOptimized(outFile).compile

        else
          throw new Exception("Unknow platform: " + pf)

      case None =>
        Linux.createX86RegisterMachine(outFile, layout).compile

  Reporter.monitor(sourceFile):
    val namespace =
      IO.fileContent(sourceFile)    |>
      Parser.parse                  |>
      Namer.transform               |>
      Debug.peek(enable = false)

    namespace.mainSymbol match
      case Some(main) =>
        namespace                     |>
        Debug.peek(enable = false)    |>
        new ExplicitInit().transform  |+
        Debug.peek(enable = false)    |>
        ElimCapture.transform         |+
        Debug.peek(enable = false)    |>
        ((ns: Sast.Namespace) => backend(ns, main))

      case None =>
        Reporter.abortInternal("No main function found")
