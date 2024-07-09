import Reporter.*

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

  val backend =
    options.get("-p") match
      case Some(pf) =>
        if pf == "linux-x86" then
          Linux.createX86StackMachine(outFile, layout)

        else if pf == "linux-x86-fast" then
          Linux.createX86RegisterMachine(outFile, layout)

        else if pf == "js" then
          new JSBackend(outFile)

        else if pf == "js-opt" then
          new JSOptimized(outFile)

        else
          throw new Exception("Unknow platform: " + pf)

      case None =>
        Linux.createX86StackMachine(outFile, layout)

  Reporter.monitor(sourceFile):
    IO.fileContent(sourceFile)    |>
    Parsing.parse                 |>
    Namer.transform               |>
    new ExplicitInit().transform  |>
    ElimCapture.transform         |>
    Debug.peek(enable = false)     |>
    backend.compile
