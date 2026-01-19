package cli

object Main:
  def main(args: Array[String]): Unit =
    if args.isEmpty then
      printUsage()
      System.exit(1)

    args(0) match
      case "run" =>
        if args.length < 2 then
          println("Error: 'run' command requires a source file")
          System.exit(1)
        sast.Interpreter.main(args.drop(1))

      case "build" =>
        val flags = parseBuildFlags(args.drop(1))
        if flags.args.isEmpty then
          println("Error: 'build' command requires a source file")
          System.exit(1)

        flags.backend match
          case Backend.Ruby =>
            ruby.Compiler.main(flags.args)

          case Backend.JS =>
            js.Compiler.main(flags.args)

          case Backend.LinuxX86Stack =>
            native.stack.StackMachine.main(flags.args)

          case Backend.LinuxX86Reg =>
            native.register.RegisterMachine.main(flags.args)

      case "build-lib" =>
        if args.length < 2 then
          println("Error: 'build-lib' command requires a source file")
          System.exit(1)

        pickle.Compiler.main(args.drop(1))

      case "help" | "--help" | "-h" =>
        printUsage()

      case file if file.endsWith(".jo") =>
        // Default to run if a .jo file is provided directly
        sast.Interpreter.main(args)

      case _ =>
        println(s"Error: Unknown command '${args(0)}'")
        printUsage()
        System.exit(1)

  enum Backend:
    case Ruby
    case JS
    case LinuxX86Stack
    case LinuxX86Reg

  case class BuildFlags(backend: Backend, args: Array[String])

  def parseBuildFlags(args: Array[String]): BuildFlags =
    var backend: Backend = Backend.Ruby
    var remaining = List.empty[String]
    var i = 0

    while i < args.length do
      args(i) match
        case "-ruby" =>
          backend = Backend.Ruby
          i += 1

        case "-js" =>
          backend = Backend.JS
          i += 1

        case "-stack" =>
          backend = Backend.LinuxX86Stack
          i += 1

        case "-reg" =>
          backend = Backend.LinuxX86Reg
          i += 1

        case other =>
          remaining = remaining :+ other
          i += 1

    BuildFlags(backend, remaining.toArray)

  def printUsage(): Unit =
    println("""Usage:
      |  jo <source.jo>                      Run program (defaults to 'run')
      |  jo run <source.jo>                  Run program with interpreter
      |  jo build [options] <source.jo>      Build application
      |  jo build-lib [options] <source.jo>  Build library (generate .sast files)
      |  jo help                             Show this help message
      |
      |Build options:
      |  -ruby           Build Ruby application (default)
      |  -js             Build JavaScript application
      |  -stack          Build linux-x86 native application using stack machine
      |  -reg            Build linux-x86 native application using register machine
      |  -o <out>        Output file path
      |  -lib <dirs>     Use precompiled libraries (colon-separated, in dependency order)
      |                  Example: -lib build/core:build/utils
      |  -link <src=tgt> Redirect symbol references (can be specified multiple times)
      |                  Example: -link jo.Predef.entry=Test.main
      |
      |Build-lib options:
      |  -d <dir>        Output directory for .sast files (optional, defaults to current dir)
      |  -lib <dirs>     Use precompiled libraries (colon-separated, in dependency order)
      |""".stripMargin)
