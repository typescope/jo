package tool

import java.nio.file.Paths
import tool.toml.TomlError

/** Entry points for the `jo build`, `jo check`, and `jo run` commands. */
object Build:

  def build(args: Array[String]): Unit =
    val (specFile, _) = parseArgs(args)
    Runner.run(makePlan(specFile))

  def check(args: Array[String]): Unit =
    val (specFile, _) = parseArgs(args)
    Runner.check(makePlan(specFile))

  def test(args: Array[String]): Unit =
    val (specFile, _) = parseArgs(args)
    Runner.test(makePlan(specFile))

  def run(args: Array[String]): Unit =
    val (specFile, appArgs) = parseArgs(args)
    val plan = makePlan(specFile)
    Runner.run(plan)
    plan.mainPlan match
      case app: CompilePlan.AppPlan => Runner.execute(app, appArgs)
      case _: CompilePlan.LibPlan   => die("'jo run' requires an app build (no [package] section)")

  // ---- Helpers ---------------------------------------------------------------

  private def makePlan(specFile: String): BuildPlan =
    try
      val path    = Paths.get(specFile).toAbsolutePath
      val specDir = path.getParent
      val stem    = path.getFileName.toString.stripSuffix(".toml")
      val spec    = Graph.loadSpec(specDir, path.getFileName.toString)
      val joBin   = JoResolver.resolve(spec.jo)
      val graph   = Graph.resolve(spec, specDir)
      Planner.plan(graph, stem, joBin)
    catch
      case e: ToolError => die(e.getMessage)
      case e: TomlError => die(e.getMessage)

  /** Parse --spec <file> and collect args after -- as app arguments. */
  private def parseArgs(args: Array[String]): (String, List[String]) =
    var specFile = "jo.toml"
    var i = 0
    while i < args.length do
      args(i) match
        case "--spec" if i + 1 < args.length =>
          specFile = args(i + 1); i += 2
        case s if s.startsWith("--spec=") =>
          specFile = s.drop("--spec=".length); i += 1
        case "--" =>
          return (specFile, args.drop(i + 1).toList)
        case _ =>
          i += 1
    (specFile, Nil)

  private def die(msg: String): Nothing =
    System.err.println(s"error: $msg")
    sys.exit(1)
