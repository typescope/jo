package cli

object Main:
  given tool.Logger = tool.Logger.stderr

  def main(args: Array[String]): Unit =
    if args.isEmpty then
      printUsage()
      System.exit(1)

    args(0) match
      case "new" =>
        tool.New.run(args.drop(1))

      case "build" =>
        tool.Build.build(args.drop(1))

      case "check" =>
        tool.Build.check(args.drop(1))

      case "test" =>
        tool.Build.test(args.drop(1))

      case "run" =>
        tool.Build.run(args.drop(1))

      case "package" =>
        tool.Build.buildPackage(args.drop(1))

      case "lock" =>
        tool.Build.lock(args.drop(1))

      case "eval" =>
        if args.length < 2 then
          println("Error: 'eval' command requires a source file")
          System.exit(1)
        sast.Interpreter.main(args.drop(1))

      case "compile" =>
        val flags = parseCompileFlags(args.drop(1))
        if flags.args.isEmpty then
          println("Error: 'compile' command requires a source file")
          System.exit(1)

        flags.backend match
          case None =>
            typing.Typer.main(flags.args)

          case Some(backend) =>
            backend match
              case Backend.Ruby         => ruby.Compiler.main(flags.args)
              case Backend.Python       => python.Compiler.main(flags.args)
              case Backend.JS           => js.Compiler.main(flags.args)
              case Backend.LinuxX86Stack => native.stack.StackMachine.main(flags.args)
              case Backend.LinuxX86Reg  => native.register.RegisterMachine.main(flags.args)

      case "doc" =>
        if args.length < 2 then
          println("Error: 'doc' command requires source files")
          System.exit(1)

        doc.Compiler.main(args.drop(1))

      case "help" | "--help" | "-h" =>
        printUsage()

      case file if file.endsWith(".jo") =>
        // Default to eval if a .jo file is provided directly
        sast.Interpreter.main(args)

      case _ =>
        println(s"Error: Unknown command '${args(0)}'")
        printUsage()
        System.exit(1)

  enum Backend:
    case Ruby
    case Python
    case JS
    case LinuxX86Stack
    case LinuxX86Reg

  case class CompileFlags(backend: Option[Backend], args: Array[String])

  def parseCompileFlags(args: Array[String]): CompileFlags =
    var backend: Option[Backend] = None
    var remaining = List.empty[String]
    var i = 0

    while i < args.length do
      args(i) match
        case "--ruby" =>
          backend = Some(Backend.Ruby)
          i += 1

        case "--python" =>
          backend = Some(Backend.Python)
          i += 1

        case "--js" =>
          backend = Some(Backend.JS)
          i += 1

        case "--stack" =>
          backend = Some(Backend.LinuxX86Stack)
          i += 1

        case "--reg" =>
          backend = Some(Backend.LinuxX86Reg)
          i += 1

        case other =>
          remaining = remaining :+ other
          i += 1

    CompileFlags(backend, remaining.toArray)

  def printUsage(): Unit =
    println("""Usage:
      |  jo <source.jo>                         Run program (defaults to 'eval')
      |  jo new <name>                           Create a new project
      |  jo build [--spec <file.toml>]          Build the project
      |  jo check [--spec <file.toml>]          Type-check and compile to sast, skip executable
      |  jo test  [--spec <file.toml>]          Build and run tests
      |  jo run   [--spec <file.toml>] [-- ...]  Build and run the application
      |  jo package [--spec <file.toml>]        Build a distributable package for a library
      |  jo lock [--spec <file.toml>]           Resolve dependencies and rewrite the lock file
      |  jo eval <source.jo>                    Run program with interpreter
      |  jo compile [options] <source.jo>       Compile application or library
      |  jo doc [options] <files...>            Generate API documentation
      |  jo help                                Show this help message
      |
      |Compile options (application — default backend is Ruby):
      |  --ruby          Compile Ruby application (default)
      |  --python        Compile Python application
      |  --js            Compile JavaScript application
      |  --stack         Compile linux-x86 native application using stack machine (experimental)
      |  --reg           Compile linux-x86 native application using register machine (experimental)
      |  -o <out>        Output file path
      |  --lib <dir>      Use a precompiled library (can be specified multiple times)
      |                   Example: --lib build/core --lib build/utils
      |  --link-lib <dir> Use a link library (resolved at link time, can be specified multiple times)
      |                   Example: --link-lib build/runtime
      |  --link <src=tgt> Redirect symbol references (can be specified multiple times)
      |                   Example: --link jo.Predef.entry=Test.main
      |
      |Compile options (library):
      |  --sast <dir>    Compile to .sast files; if no backend flag, this is the only output
      |  --lib <dir>     Use a precompiled library (can be specified multiple times)
      |
      |Doc options:
      |  -d <dir>        Output directory (default: docs)
      |  -title <name>   Project title for documentation
      |  --include-private Include private symbols
      |""".stripMargin)
