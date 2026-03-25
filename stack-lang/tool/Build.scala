package tool

import java.nio.file.Paths
import java.nio.file.Path
import tool.toml.TomlError

/** Entry points for the `jo build`, `jo check`, and `jo run` commands. */
object Build:
  def build(args: Array[String])(using Logger): Unit =
    val specFile = parseSpecFile(args)
    withDefaultPackageProvider:
      Runner.run(makePlan(specFile)) match
      case Result.Err(msg) =>
        Logger.error(msg)
        sys.exit(1)

      case _ =>

  def check(args: Array[String])(using Logger): Unit =
    val specFile = parseSpecFile(args)
    withDefaultPackageProvider:
      Runner.check(makePlan(specFile)) match
      case Result.Err(msg) =>
        Logger.error(msg)
        sys.exit(1)

      case _ =>

  def test(args: Array[String])(using Logger): Unit =
    val specFile = parseSpecFile(args)
    withDefaultPackageProvider:
      Runner.test(makePlan(specFile)) match
      case Result.Err(msg) =>
        Logger.error(msg)
        sys.exit(1)

      case _ =>

  def run(args: Array[String])(using Logger): Unit =
    val (specFile, appArgs) = parseRunArgs(args)
    withDefaultPackageProvider:
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

  def buildPackage(args: Array[String])(using Logger): Unit =
    withDefaultPackageProvider:
      try Release.buildPackage(args)
      catch
        case e: ToolError =>
          Logger.error(s"error: ${e.getMessage}\n")
          sys.exit(1)

  // ---- Helpers ---------------------------------------------------------------

  def makePlan(specFile: String)(using PackageProvider): BuildPlan =
    try
      makePlan(specFile): constraint =>
        JoResolver.resolve(constraint) match
          case Result.Ok(v)    => v
          case Result.Err(msg) => die(msg)
    catch
      case e: TomlError => die(e.getMessage)

  def makePlan(specFile: String)(resolveJo: VersionSpec => (Version, Path))(using PackageProvider): BuildPlan =
      val path    = Paths.get(specFile).toAbsolutePath
      val specDir = path.getParent
      val spec    = Graph.loadSpec(specDir, path.getFileName.toString)
      val (joVersion, joPath) = resolveJo(spec.jo)
      val graph   = Graph.resolve(spec, specDir)
      val registryLibs = resolveRegistryLibs(graph, specDir)
      Planner.plan(graph, joVersion, joPath, registryLibs)

  private def resolveRegistryLibs(graph: ResolvedGraph, rootDir: Path)(using PackageProvider): Map[String, Path] =
    DependencyResolver.resolve(graph) match
      case Result.Err(msg) =>
        die(msg)

      case Result.Ok(pkgs) =>
        pkgs.map: pkg =>
          pkg.name -> materializePackage(pkg, rootDir, graph.root.name)
        .toMap

  private def materializePackage(pkg: ResolvedPackage, rootDir: Path, rootName: String): Path =
    val outDir = rootDir
      .resolve(s".build/$rootName/packages")
      .resolve(pkg.name)
      .resolve(pkg.version.toString)

    if !isMaterialized(pkg.path, outDir) then
      if java.nio.file.Files.exists(outDir) then deleteDir(outDir)
      JoyArchive.unpack(pkg.path, outDir)
      java.nio.file.Files.writeString(outDir.resolve(".source-archive"), pkg.path.toString)

    outDir

  private def isMaterialized(archive: Path, outDir: Path): Boolean =
    val marker = outDir.resolve(".source-archive")
    java.nio.file.Files.isDirectory(outDir) &&
    java.nio.file.Files.exists(marker) &&
    java.nio.file.Files.readString(marker) == archive.toString

  private def deleteDir(dir: Path): Unit =
    if java.nio.file.Files.exists(dir) then
      java.nio.file.Files.walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(java.nio.file.Files.delete)

  /** Parse --spec <file>. */
  def parseSpecFile(args: Array[String]): String =
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
          return specFile
        case _ =>
          i += 1
    specFile

  /** Parse --spec <file> and collect args after -- as app arguments. */
  def parseRunArgs(args: Array[String]): (String, List[String]) =
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

  private def withDefaultPackageProvider[A](body: PackageProvider ?=> A): A =
    given PackageProvider = PackageProvider.default()
    body
