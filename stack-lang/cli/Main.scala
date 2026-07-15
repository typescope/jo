package cli

import java.nio.file.Paths

object Main:
  val Version = tool.JoVersion.current.toString

  def main(rawArgs: Array[String]): Unit =
    // Accept `-v` as an alias for `--verbose` (but not after the `--` that
    // separates application arguments).
    val sep = rawArgs.indexOf("--")
    val args = rawArgs.zipWithIndex.map: (a, i) =>
      if a == "-v" && (sep < 0 || i < sep) then "--verbose" else a

    val verbose = args.contains("--verbose")
    val command = args.headOption.getOrElse("")

    // `jo run` is quiet by default — it just runs the app (build steps are
    // incidental). Other commands show their build steps. `--verbose` / `-v`
    // shows the full trace everywhere. Warnings and errors always print.
    given tool.Logger =
      if verbose then tool.Logger(tool.LogLevel.Log)
      else if command == "run" then tool.Logger(tool.LogLevel.Warn)
      else tool.Logger.stderr

    given tool.PackageProvider = tool.PackageProvider.default()

    if args.isEmpty then
      printUsage()
      System.exit(1)

    args(0) match
      case "--version" | "version" =>
        println(Version)
        return

      case "new" =>
        tool.New.run(args.drop(1))

      case "clean" =>
        val specFile = tool.Build.parseProjectArgs(args.drop(1)).map(_.specFile).orExit
        val project = loadProject(specFile).orExit
        tool.Build.clean(project).orExit

      case "build" =>
        val parsed = tool.Build.parseProjectArgs(args.drop(1)).orExit
        val project = loadProject(parsed.specFile).orExit
        val module = tool.Build.selectedModule(project, parsed)
        tool.Build.build(project, module).orExit

      case "check" =>
        val parsed = tool.Build.parseProjectArgs(args.drop(1)).orExit
        val project = loadProject(parsed.specFile).orExit
        val module = tool.Build.selectedModule(project, parsed)
        tool.Build.check(project, module).orExit

      case "test" =>
        System.err.println("Error: 'jo test' was removed; define a test app module and run it with 'jo run <module>'")
        System.exit(1)

      case "run" =>
        val parsed = tool.Build.parseRunArgs(args.drop(1)).orExit
        val project = loadProject(parsed.specFile).orExit
        val module = tool.Build.selectedModule(project, parsed)
        tool.Build.run(project, module, parsed.appArgs).orExit

      case "package" =>
        val parsed = tool.Build.parseProjectArgs(args.drop(1)).orExit
        val project = loadProject(parsed.specFile).orExit
        val module = tool.Build.selectedModule(project, parsed)
        tool.Release.buildPackage(project, module).orExit

      case "deps" =>
        val parsed = tool.Build.parseProjectArgs(args.drop(1)).orExit
        val project = loadProject(parsed.specFile).orExit
        val module = tool.Build.selectedModule(project, parsed)
        tool.Build.deps(project, module).orExit

      case "lock" =>
        val parsed = tool.Build.parseProjectArgs(args.drop(1)).orExit
        if parsed.module.isDefined then
          System.err.println("Error: 'jo lock' resolves all modules and does not take a module argument")
          System.exit(1)
        val project = loadProject(parsed.specFile).orExit
        tool.Build.lock(project).orExit

      case "info" =>
        tool.Info.run(args.drop(1))

      case "eval" =>
        if args.length < 2 then
          println("Error: 'eval' command requires a source file")
          System.exit(1)
        sast.Interpreter.main(args.drop(1))

      case "compile" =>
        val flags = parseCompileFlags(args.drop(1))
        if flags.args.isEmpty then
          val command =
            if flags.backend.contains(Backend.Doc) then "'compile --doc'"
            else "'compile'"
          println(s"Error: $command command requires a source file")
          System.exit(1)

        flags.backend match
          case None =>
            given reporting.Reporter = reporting.Reporter.createReporter()
            val (config, sources) = cli.OptionParser.parseConfig(flags.args, reporting.Config.commonOptions)
            given reporting.Config = config
            reporting.Config.useRuntimeApi.value match
              case Some("python") =>
                config.setInternal(typing.PostCheck.postChecks, List(new python.PythonPostCheck))
              case Some("ruby") =>
                config.setInternal(typing.PostCheck.postChecks, List(new ruby.RubyPostCheck))
              case Some("js") =>
                config.setInternal(typing.PostCheck.postChecks, List(new js.JSPostCheck))
              case _ =>
            typing.Typer.runConfig(config, sources)

          case Some(backend) =>
            backend match
              case Backend.Doc            => doc.Compiler.main(flags.args)
              case Backend.Ruby           => ruby.Compiler.main(flags.args)
              case Backend.Python         => python.Compiler.main(flags.args)
              case Backend.JS             => js.Compiler.main(flags.args)
              case Backend.LinuxX86Stack  => native.stack.StackMachine.main(flags.args)
              case Backend.LinuxX86Reg    => native.register.RegisterMachine.main(flags.args)

      case "doc" =>
        val parsed = tool.Build.parseProjectArgs(args.drop(1)).orExit
        val project = loadProject(parsed.specFile).orExit
        val module = tool.Build.selectedModule(project, parsed)
        tool.Build.buildDoc(project, module).orExit

      case "versions" =>
        tool.Versions.run(args.drop(1), tool.HttpInstaller.default()) match
          case tool.Result.Ok(_)    => ()
          case tool.Result.Err(msg) => System.err.println(s"${tool.Ansi.red("error:")} $msg"); sys.exit(1)

      case "help" | "--help" | "-h" =>
        printUsage()

      case "exec" =>
        // Always run a project-defined command, bypassing builtins.
        val rest = args.drop(1)
        if rest.isEmpty then
          println("Error: 'exec' requires a command name")
          System.exit(1)
        else
          runProjectCommand(rest(0), rest.drop(1), forced = true)

      case file if file.endsWith(".jo") =>
        // Default to eval if a .jo file is provided directly
        sast.Interpreter.main(args)

      case name =>
        // Not a builtin: as a last resort, try a project-defined [commands] entry.
        runProjectCommand(name, args.drop(1), forced = false)

  /** Run a `[commands]` entry from the current project's jo.toml, passing any
   *  extra args through. `forced` marks the explicit `jo exec` form (builtins
   *  already ruled out); when false this is the `jo <name>` fallthrough, so a miss
   *  reports an unknown command. Exits with the command's status.
   */
  private def runProjectCommand(name: String, extra: Array[String], forced: Boolean): Unit =
    tool.Project.loadSpec(Paths.get("").toAbsolutePath) match
      case tool.Result.Ok(spec) =>
        spec.commands.get(name) match
          case Some(cmd) =>
            // The command string is run through the shell, so `&&`, pipes, etc.
            // in the *definition* work. Extra CLI args are appended as literal
            // positional parameters via "$@" — never spliced into the shell text
            // — so a metacharacter in an argument (`jo dev "&& rm -rf /"`) is
            // passed as data, not executed.
            //
            // In `sh -c <script> A B C`, the argument after the script becomes
            // `$0`, and only the rest become `$1`, `$2`, … (what "$@" expands
            // to). So a placeholder must occupy `$0`, or the first real arg would
            // be swallowed and dropped from "$@". We use "jo" as that `$0` so any
            // shell diagnostics are attributed to `jo` (e.g. `jo: line 1: …`).
            val argv =
              if extra.isEmpty then List("sh", "-c", cmd)
              else List("sh", "-c", cmd + " \"$@\"", "jo") ++ extra.toList
            val code = ProcessBuilder(argv*).inheritIO().start().waitFor()
            System.exit(code)

          case None =>
            System.err.println(s"Error: unknown command '$name'")
            if spec.commands.nonEmpty then
              System.err.println(s"Defined commands: ${spec.commands.keys.toSeq.sorted.mkString(", ")}")
            else if !forced then
              printUsage()
            System.exit(1)

      case tool.Result.Err(msg) =>
        // No (or invalid) project here: `exec` surfaces why; the bare fallthrough
        // just reports an unknown command as before.
        if forced then System.err.println(s"Error: cannot run '$name': $msg")
        else
          println(s"Error: Unknown command '$name'")
          printUsage()
        System.exit(1)

  enum Backend:
    case Doc
    case Ruby
    case Python
    case JS
    case LinuxX86Stack
    case LinuxX86Reg

  case class CompileFlags(backend: Option[Backend], args: Array[String])

  private def loadProject(specFile: String): tool.Result[tool.Project] =
    val specPath = Paths.get(specFile).toAbsolutePath
    tool.Project.load(specPath)

  def parseCompileFlags(args: Array[String]): CompileFlags =
    var backend: Option[Backend] = None
    var remaining = List.empty[String]
    var i = 0

    while i < args.length do
      args(i) match
        case "--doc" =>
          backend = Some(Backend.Doc)
          i += 1

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
      |  jo clean                                Remove this project's build artifacts (not path dependencies)
      |  jo build [module]                       Build a module (default module if omitted)
      |  jo check [module]                       Type-check and compile to sast, skip executable
      |  jo run   [module] [-- ...]
      |                                           Build and run an app module
      |  jo package [module]
      |                                           Build a distributable package for a module
      |  jo deps [module]                       Print the resolved dependency tree
      |  jo lock                                Resolve dependencies and rewrite the lock file
      |  jo info <pkg>[@<version>]              Show package metadata and available versions
      |  jo eval <source.jo>                    Run program with interpreter
      |  jo <name> [args...]                    Run a project command from [commands] (builtins win)
      |  jo exec <name> [args...]               Run a [commands] entry, bypassing builtins
      |  jo versions                             List installed and available compiler versions
      |  jo versions install <version>          Download and install a compiler version
      |  jo versions use <version>              Switch the active compiler version
      |  jo versions remove <version>           Remove an installed compiler version
      |  jo compile [options] <source.jo>       Compile application or library
      |  jo compile --doc [options] <files...>  Generate documentation from source files
      |  jo doc [module]                        Generate module documentation
      |  jo help                                Show this help message
      |
      |Project options:
      |  --spec <file.toml>  Use a build spec other than jo.toml
      |
      |Compile options (application — default backend is Ruby):
      |  --ruby          Compile Ruby application (default)
      |  --python        Compile Python application
      |  --js            Compile JavaScript application (experimental, no build tool support)
      |  -o <out>        Output file path
      |  --lib <dir>      Use a precompiled library (can be specified multiple times)
      |                   Example: --lib build/core --lib build/utils
      |  --link-lib <dir> Use a link library (resolved at link time, can be specified multiple times)
      |                   Example: --link-lib build/runtime
      |  --link <src=tgt> Redirect symbol references (can be specified multiple times)
      |                   Example: --link jo.Predef.entry=Test.main
      |  --use-runtime-api <python|ruby|js>
      |                   Make a runtime API available as a check library; when it matches the
      |                   selected app backend, suppress the backend's default runtime link lib
      |                   (js is experimental)
      |
      |Compile options (library):
      |  --sast <dir>    Compile to .sast files; if no backend flag, this is the only output
      |  --lib <dir>     Use a precompiled library (can be specified multiple times)
      |  --use-runtime-api <python|ruby|js>
      |                   Make a runtime API available as a check library (js is experimental)
      |
      |Doc options for 'jo compile --doc':
      |  --out <dir>           Output directory (default: docs)
      |  --title <name>        Project title for documentation
      |  --include-private     Include private symbols
      |  --include-source      Embed source code in output
      |""".stripMargin)
