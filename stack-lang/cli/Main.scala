package cli

object Main:
  def main(args: Array[String]): Unit =
    if args.isEmpty then
      printUsage()
      System.exit(1)

    args(0) match
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

        if flags.sast then
          pickle.Compiler.main(flags.args)
        else
          flags.backend.getOrElse(Backend.Ruby) match
            case Backend.Ruby =>
              ruby.Compiler.main(flags.args)

            case Backend.Python =>
              python.Compiler.main(flags.args)

            case Backend.JS =>
              js.Compiler.main(flags.args)

            case Backend.LinuxX86Stack =>
              native.stack.StackMachine.main(flags.args)

            case Backend.LinuxX86Reg =>
              native.register.RegisterMachine.main(flags.args)

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

  case class CompileFlags(backend: Option[Backend], sast: Boolean, args: Array[String])

  def parseCompileFlags(args: Array[String]): CompileFlags =
    var backend: Option[Backend] = None
    var sast = false
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

        case "--sast" =>
          sast = true
          i += 1

        case other =>
          remaining = remaining :+ other
          i += 1

    CompileFlags(backend, sast, remaining.toArray)

  def printUsage(): Unit =
    println("""Usage:
      |  jo <source.jo>                         Run program (defaults to 'eval')
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
      |Compile options (library — requires --sast):
      |  --sast          Compile to .sast files instead of an application
      |  -d <dir>        Output directory for .sast files (optional, defaults to current dir)
      |  --lib <dir>     Use a precompiled library (can be specified multiple times)
      |
      |Doc options:
      |  -d <dir>        Output directory (default: docs)
      |  -title <name>   Project title for documentation
      |  --include-private Include private symbols
      |""".stripMargin)
