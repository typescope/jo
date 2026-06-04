package tool

import java.nio.file.{Files, Path, Paths}

/** Scaffolds a new Jo project in a fresh directory. */
object New:
  private val libOpt = CommandLine.BooleanSetting("--lib", "create a library project")

  case class Args(name: String, isLib: Boolean)

  def run(args: Array[String]): Unit =
    parseArgs(args) match
      case Result.Ok(parsed) =>
        scaffold(parsed.name, parsed.isLib, Paths.get("").toAbsolutePath) match
          case Result.Ok(msg)  => print(msg)
          case Result.Err(msg) => System.err.println(msg); sys.exit(1)
      case Result.Err(msg) => System.err.println(msg); sys.exit(1)

  def parseArgs(args: Array[String]): Result[Args] =
    CommandLine.parse(args, List(libOpt, CommandLine.verboseOpt)).flatMap: parsed =>
      parsed.positional match
        case name :: Nil =>
          Result.Ok(Args(name, parsed.value(libOpt)))
        case Nil =>
          Result.Err("error: 'jo new' requires a project name")
        case arg :: _ =>
          Result.Err(s"error: unexpected argument '$arg'")

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
        s"""jo      = "$joConstraint"
           |name    = "$name"
           |
           |[package]
           |version = "0.1.0"
           |""".stripMargin)

      Result.Ok(
        s"""${Ansi.green("Created")} ${Ansi.blue("'" + name + "'")}
           |
           |${Ansi.dim("You can now:")}
           |  ${Ansi.blue("cd")} $name
           |  ${Ansi.blue("jo")} build
           |""".stripMargin)
    else
      Files.writeString(dir.resolve("jo.toml"),
        s"""jo   = "$joConstraint"
           |name = "$name"
           |
           |[main]
           |target = "${Target.Python.flag}"
           |""".stripMargin)

      Files.writeString(dir.resolve("src/Main.jo"),
        s"""def main = println "Hello, $name!"
           |""".stripMargin)

      Result.Ok(
        s"""${Ansi.green("Created")} ${Ansi.blue("'" + name + "'")}
           |
           |${Ansi.dim("You can now:")}
           |  ${Ansi.blue("cd")} $name
           |  ${Ansi.blue("jo")} run
           |""".stripMargin)
