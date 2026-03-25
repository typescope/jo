package tool

import java.nio.file.Paths
import java.nio.file.Path
import tool.toml.TomlError

/** Entry points for the `jo build`, `jo check`, and `jo run` commands. */
object Build:
  def build(args: Array[String])(using Logger): Unit =
    val specFile = parseSpecFile(args)
    withDefaultPackageProvider:
      makePlanResult(specFile) match
        case Result.Err(msg) =>
          Logger.error(msg)
          sys.exit(1)

        case Result.Ok(plan) =>
          Runner.run(plan) match
            case Result.Err(msg) =>
              Logger.error(msg)
              sys.exit(1)

            case _ =>

  def check(args: Array[String])(using Logger): Unit =
    val specFile = parseSpecFile(args)
    withDefaultPackageProvider:
      makePlanResult(specFile) match
        case Result.Err(msg) =>
          Logger.error(msg)
          sys.exit(1)

        case Result.Ok(plan) =>
          Runner.check(plan) match
            case Result.Err(msg) =>
              Logger.error(msg)
              sys.exit(1)

            case _ =>

  def test(args: Array[String])(using Logger): Unit =
    val specFile = parseSpecFile(args)
    withDefaultPackageProvider:
      makePlanResult(specFile) match
        case Result.Err(msg) =>
          Logger.error(msg)
          sys.exit(1)

        case Result.Ok(plan) =>
          Runner.test(plan) match
            case Result.Err(msg) =>
              Logger.error(msg)
              sys.exit(1)

            case _ =>

  def run(args: Array[String])(using Logger): Unit =
    val (specFile, appArgs) = parseRunArgs(args)
    withDefaultPackageProvider:
      makePlanResult(specFile) match
        case Result.Err(msg) =>
          Logger.error(msg)
          sys.exit(1)

        case Result.Ok(plan) =>
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

  def makePlanResult(specFile: String)(using PackageProvider): Result[BuildPlan] =
    makePlanResult(specFile)(JoResolver.resolve)

  def makePlanResult(specFile: String)(resolveJo: VersionSpec => Result[(Version, Path)])(using PackageProvider): Result[BuildPlan] =
    try
      val path    = Paths.get(specFile).toAbsolutePath
      val specDir = path.getParent
      val spec    = Graph.loadSpec(specDir, path.getFileName.toString)
      resolveJo(spec.jo).flatMap: (joVersion, joPath) =>
        val graph = Graph.resolve(spec, specDir)
        resolveRegistryLibs(graph, specDir).map: registryLibs =>
          Planner.plan(graph, joVersion, joPath, registryLibs)
    catch
      case e: ToolError => Result.Err(e.getMessage)
      case e: TomlError => Result.Err(e.getMessage)

  private def resolveRegistryLibs(graph: ResolvedGraph, rootDir: Path)(using PackageProvider): Result[Map[String, Path]] =
    DependencyResolver.resolve(graph).map: pkgs =>
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
