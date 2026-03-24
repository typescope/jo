package tool

import java.nio.file.Paths
import java.nio.file.Path
import tool.toml.TomlError

/** Entry points for the `jo build`, `jo check`, and `jo run` commands. */
object Build:

  def build(args: Array[String])(using Logger): Unit =
    val (specFile, _) = parseArgs(args)
    Runner.run(makePlan(specFile)) match
      case Result.Err(msg) =>
        Logger.error(msg)
        sys.exit(1)

      case _ =>

  def check(args: Array[String])(using Logger): Unit =
    val (specFile, _) = parseArgs(args)
    Runner.check(makePlan(specFile)) match
      case Result.Err(msg) =>
        Logger.error(msg)
        sys.exit(1)

      case _ =>

  def test(args: Array[String])(using Logger): Unit =
    val (specFile, _) = parseArgs(args)
    Runner.test(makePlan(specFile)) match
      case Result.Err(msg) =>
        Logger.error(msg)
        sys.exit(1)

      case _ =>

  def run(args: Array[String])(using Logger): Unit =
    val (specFile, appArgs) = parseArgs(args)
    val plan = makePlan(specFile)
    Runner.run(plan) match
      case Result.Err(msg) =>
        Logger.error(msg)
        sys.exit(1)

      case _ =>
    plan.mainPlan match
      case app: CompilePlan.AppPlan =>
        Runner.execute(app, appArgs) match
          case Result.Err(msg) =>
            Logger.error(msg)
            sys.exit(1)

          case _ =>

      case _: CompilePlan.LibPlan =>
        die("'jo run' requires an app build (no [package] section)")

  // ---- Helpers ---------------------------------------------------------------

  private def makePlan(specFile: String): BuildPlan =
    try
      makePlan(specFile): constraint =>
        JoResolver.resolve(constraint) match
          case Result.Ok(v)    => v
          case Result.Err(msg) => die(msg)
    catch
      case e: TomlError => die(e.getMessage)

  def makePlan(specFile: String)(resolveJo: String => (Version, Path)): BuildPlan =
      val path    = Paths.get(specFile).toAbsolutePath
      val specDir = path.getParent
      val spec    = Graph.loadSpec(specDir, path.getFileName.toString)
      val (joVersion, joPath) = resolveJo(spec.jo)
      val graph   = Graph.resolve(spec, specDir)
      Planner.plan(graph, joVersion, joPath)

  /** Parse --spec <file> and collect args after -- as app arguments. */
  private def parseArgs(args: Array[String]): (String, List[String]) =
    var specFile = "jo.toml"
    var i = 0
    while i < args.length do
      args(i) match
        case "--spec" if i + 1 < args.length =>
          specFile = args(i + 1)
          i += 2
        case s if s.startsWith("--spec=") =>
          specFile = s.drop("--spec=".length)
          i += 1
        case "--" =>
          return (specFile, args.drop(i + 1).toList)
        case _ =>
          i += 1
    (specFile, Nil)

  private def die(msg: String): Nothing =
    System.err.println(s"error: $msg")
    sys.exit(1)
