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

      case "compile" =>
        val flags = parseCompileFlags(args.drop(1))
        if flags.args.isEmpty then
          println("Error: 'compile' command requires a source file")
          System.exit(1)

        flags.backend match
          case Backend.JS =>
            js.compile(flags.args*)

          case Backend.LinuxX86Stack =>
            native.stack.StackMachine.main(flags.args)

          case Backend.LinuxX86Reg =>
            native.register.RegisterMachine.main(flags.args)

      case "help" | "--help" | "-h" =>
        printUsage()

      case file if file.endsWith(".stk") =>
        // Default to run if a .stk file is provided directly
        sast.Interpreter.main(args)

      case _ =>
        println(s"Error: Unknown command '${args(0)}'")
        printUsage()
        System.exit(1)

  enum Backend:
    case JS
    case LinuxX86Stack
    case LinuxX86Reg

  case class CompileFlags(backend: Backend, args: Array[String])

  def parseCompileFlags(args: Array[String]): CompileFlags =
    var backend: Backend = Backend.LinuxX86Reg
    var remaining = List.empty[String]
    var i = 0

    while i < args.length do
      args(i) match
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

    CompileFlags(backend, remaining.toArray)

  def printUsage(): Unit =
    println("""Usage:
      |  jo <source.stk>                    Run program (defaults to 'run')
      |  jo run <source.stk>                Run program with interpreter
      |  jo compile [options] <source.stk>  Compile program
      |  jo help                            Show this help message
      |
      |Compile options:
      |  -js        Compile to JavaScript
      |  -stack     Compile using stack machine
      |  -reg       Compile using register machine (default)
      |""".stripMargin)
