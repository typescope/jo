package tool

import java.nio.file.{Files, Path, Paths}

import tool.template.{TemplateManifest, TemplateProvider, TemplateRef}

/** Scaffolds a new Jo project in a fresh directory, either from a built-in
 *  scaffold (app / `--lib`) or from a third-party template repo
 *  (`--template`, see templates.md).
 */
object New:
  private val libOpt      = CommandLine.BooleanSetting("--lib", "create a library project")
  private val templateOpt = CommandLine.OptionStringSetting("--template", "scaffold from a template ref, e.g. 'gh:owner/repo:name'")
  private val listOpt     = CommandLine.BooleanSetting("--list", "list templates declared by --template's repo instead of scaffolding")

  enum Args:
    case Scaffold(name: String, isLib: Boolean, template: Option[TemplateRef])
    case ListTemplates(ref: TemplateRef)

  def run(args: Array[String]): Unit =
    val baseDir = Paths.get("").toAbsolutePath

    val result = parseArgs(args).flatMap:
      case Args.Scaffold(name, isLib, None) =>
        scaffold(name, isLib, baseDir)

      case Args.Scaffold(name, isLib, Some(ref)) =>
        scaffoldFromTemplate(name, ref, baseDir, TemplateProvider.forHost)

      case Args.ListTemplates(ref) =>
        listTemplates(ref, TemplateProvider.forHost)

    result match
      case Result.Ok(msg)  => print(msg)
      case Result.Err(msg) => System.err.println(msg); sys.exit(1)

  def parseArgs(args: Array[String]): Result[Args] =
    CommandLine.parse(args, List(libOpt, templateOpt, listOpt, CommandLine.verboseOpt)).flatMap: parsed =>
      val isLib       = parsed.value(libOpt)
      val list        = parsed.value(listOpt)
      val templateRaw = parsed.value(templateOpt)

      (templateRaw, isLib, list) match
        case (None, _, true) =>
          Result.Err("error: '--list' requires '--template'")

        case (Some(_), true, _) =>
          Result.Err("error: '--template' cannot be combined with '--lib'")

        case (None, _, false) =>
          requireName(parsed.positional).map(name => Args.Scaffold(name, isLib, None))

        case (Some(raw), false, true) =>
          TemplateRef.parse(raw).flatMap: ref =>
            if parsed.positional.nonEmpty then
              Result.Err("error: '--list' does not take a project name")
            else
              Result.Ok(Args.ListTemplates(ref))

        case (Some(raw), false, false) =>
          TemplateRef.parse(raw).flatMap: ref =>
            requireName(parsed.positional).map(name => Args.Scaffold(name, isLib, Some(ref)))

  private def requireName(positional: List[String]): Result[String] =
    positional match
      case name :: Nil => Result.Ok(name)
      case Nil          => Result.Err("error: 'jo new' requires a project name")
      case arg :: _     => Result.Err(s"error: unexpected argument '$arg'")

  /** Scaffolds `name` from a third-party template.
   *
   *  `resolveProvider` is injectable (defaults to `TemplateProvider.forHost`
   *  in [[run]]) so tests can substitute a `LocalTemplateProvider` fixture
   *  without going through host parsing or the network at all.
   */
  def scaffoldFromTemplate(
    name: String,
    ref: TemplateRef,
    baseDir: Path,
    resolveProvider: String => Result[TemplateProvider],
  ): Result[String] =
    val dir = baseDir.resolve(name)

    if Files.exists(dir) then
      return Result.Err(s"error: directory '$name' already exists")

    for
      provider <- resolveProvider(ref.host)
      entries  <- provider.manifest(ref.identifier, ref.gitref)
      entry    <- TemplateManifest.resolve(entries, ref.name, ref.identifier)
      _        <- provider.fetch(ref.identifier, ref.gitref, entry.path, dir)
    yield
      val source = ref.identifier + ref.name.map(n => s":$n").getOrElse("")

      s"""${Ansi.green("Created")} ${Ansi.blue("'" + name + "'")} ${Ansi.dim(s"from $source")}
         |
         |${Ansi.dim("You can now:")}
         |  ${Ansi.blue("cd")} $name
         |""".stripMargin

  /** Lists the templates declared by the repo at `ref`, without scaffolding anything. */
  def listTemplates(ref: TemplateRef, resolveProvider: String => Result[TemplateProvider]): Result[String] =
    for
      provider <- resolveProvider(ref.host)
      entries  <- provider.manifest(ref.identifier, ref.gitref)
    yield
      if entries.isEmpty then
        s"${ref.identifier} declares no templates\n"
      else
        entries
          .map: entry =>
            val desc = entry.description.map(d => s" - $d").getOrElse("")
            s"  ${entry.name}$desc"
          .mkString(s"Templates in ${ref.identifier}:\n", "\n", "\n")

  def scaffold(name: String, isLib: Boolean, baseDir: Path): Result[String] =
    val dir = baseDir.resolve(name)
    val v   = JoVersion.current
    val joConstraint = s"${v.major}.${v.minor}"

    if Files.exists(dir) then
      return Result.Err(s"error: directory '$name' already exists")

    Files.createDirectories(dir.resolve("src"))
    Files.createDirectories(dir.resolve("tests"))
    Files.writeString(dir.resolve(".gitignore"), ".build/\n")

    if isLib then
      Files.writeString(dir.resolve("jo.toml"),
        s"""jo = "$joConstraint"
           |
           |[module.lib]
           |kind = "lib"
           |src = ["src/"]
           |
           |[module.lib.package]
           |name = "$name"
           |version = "0.1.0"
           |
           |[module.test]
           |kind = "app"
           |src = ["tests/"]
           |platform = "${Target.Python.flag}"
           |modules = ["lib"]
           |""".stripMargin)

      Files.writeString(dir.resolve("tests/Main.jo"),
        s"""namespace Test
           |
           |def main = println "OK"
           |""".stripMargin)

      Result.Ok(
        s"""${Ansi.green("Created")} ${Ansi.blue("'" + name + "'")}
           |
           |${Ansi.dim("You can now:")}
           |  ${Ansi.blue("cd")} $name
           |  ${Ansi.blue("jo")} build
           |  ${Ansi.blue("jo")} run test
           |""".stripMargin)
    else
      Files.writeString(dir.resolve("jo.toml"),
        s"""jo = "$joConstraint"
           |
           |[module.app]
           |kind = "app"
           |src = ["src/"]
           |platform = "${Target.Python.flag}"
           |
           |[module.test]
           |kind = "app"
           |src = ["tests/"]
           |platform = "${Target.Python.flag}"
           |modules = ["app"]
           |""".stripMargin)

      Files.writeString(dir.resolve("src/Main.jo"),
        s"""def main = println "Hello, $name!"
           |""".stripMargin)
      Files.writeString(dir.resolve("tests/Main.jo"),
        s"""namespace Test
           |
           |def main = println "OK"
           |""".stripMargin)

      Result.Ok(
        s"""${Ansi.green("Created")} ${Ansi.blue("'" + name + "'")}
           |
           |${Ansi.dim("You can now:")}
           |  ${Ansi.blue("cd")} $name
           |  ${Ansi.blue("jo")} run
           |  ${Ansi.blue("jo")} run test
           |""".stripMargin)
