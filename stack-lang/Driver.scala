//> using file Ast.scala
//> using file Sast.scala
//> using file Namer.scala
//> using file Checker.scala
//> using file Parser.scala
//> using file Reporter.scala
//> using file Assembly.scala
//> using file Platform.scala
//> using file Compiler.scala
//> using file IO.scala
//> using file Assembler.scala
//> using file Linux.scala
//> using file X86.scala
//> using file ELF32.scala
//> using file UniqueName.scala
//> using file JSPlatform.scala
//> using file JSPlatformOpt.scala

import Reporter.*

/***********************************************************************
 *
 * Main entry point for the compiler
 *
 ***********************************************************************/
@main
def run(args: String*) =
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

  given Platform =
    options.get("-p") match
      case Some(pf) =>
        if pf == "linux-x86" then
          Linux.createX86Platform(outFile, layout)
        else if pf == "js" then
          new JSPlatform(outFile)
        else if pf == "js-opt" then
          new JSPlatformOpt(outFile)
        else
          throw new Exception("Unknow platform: " + pf)

      case None =>
        Linux.createX86Platform(outFile, layout)

  Reporter.monitor:
    given Reporter = Reporter.withSource(sourceFile)

    IO.fileContent(sourceFile)    |>
    Parsing.parse                 |>
    new Namer().transform         |>
    Compiler.compile
