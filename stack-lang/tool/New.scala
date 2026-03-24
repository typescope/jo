package tool

import java.nio.file.{Files, Path, Paths}

/** Scaffolds a new Jo project in a fresh directory. */
object New:
  def run(args: Array[String]): Unit =
    if args.isEmpty then
      System.err.println("error: 'jo new' requires a project name")
      sys.exit(1)

    val name  = args(0)
    val isLib = args.contains("--lib")
    scaffold(name, isLib, Paths.get("").toAbsolutePath) match
      case Result.Ok(msg)  => print(msg)
      case Result.Err(msg) => System.err.print(msg); sys.exit(1)

  def scaffold(name: String, isLib: Boolean, baseDir: Path): Result[String] =
    val dir = baseDir.resolve(name)
    val v   = Version.current
    val joConstraint = s">=${v.major}.${v.minor}"

    if Files.exists(dir) then
      return Result.Err(s"error: directory '$name' already exists\n")

    Files.createDirectories(dir.resolve("src"))
    Files.createDirectories(dir.resolve("tests"))

    if isLib then
      Files.writeString(dir.resolve("jo.toml"),
        s"""jo      = "$joConstraint"
           |name    = "$name"
           |
           |[package]
           |version = "0.1.0"
           |""".stripMargin)

      Result.Ok(
        s"""Created '$name'
           |
           |You can now:
           |  cd $name
           |  jo build
           |""".stripMargin)
    else
      Files.writeString(dir.resolve("jo.toml"),
        s"""jo   = "$joConstraint"
           |name = "$name"
           |
           |[main]
           |target = "python"
           |""".stripMargin)

      Files.writeString(dir.resolve("src/Main.jo"),
        s"""def main = println "Hello, $name!"
           |""".stripMargin)

      Result.Ok(
        s"""Created '$name'
           |
           |You can now:
           |  cd $name
           |  jo run
           |""".stripMargin)
