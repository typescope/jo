package tool

import java.nio.file.Paths
import java.nio.file.Path
import tool.toml.TomlError

/** Entry points for the `jo build`, `jo check`, `jo run`, and `jo test` commands. */
object Build:
  def lock(args: Array[String]): Unit =
    withDefaultPackageProvider:
      lockResult(parseSpecFile(args)).orExit

  def build(args: Array[String])(using Logger): Unit =
    withDefaultPackageProvider:
      val (plans, joBin) = makePlanResult(parseSpecFile(args)).orExit
      Runner.run(plans.main, joBin).orExit

  def check(args: Array[String])(using Logger): Unit =
    withDefaultPackageProvider:
      val (plans, joBin) = makePlanResult(parseSpecFile(args)).orExit
      Runner.check(plans.main, joBin).orExit

  def test(args: Array[String])(using Logger): Unit =
    withDefaultPackageProvider:
      val (plans, joBin) = makePlanResult(parseSpecFile(args)).orExit
      Runner.test(plans.test, joBin).orExit

  def run(args: Array[String])(using Logger): Unit =
    val (specFile, appArgs) = parseRunArgs(args)
    withDefaultPackageProvider:
      val (plans, joBin) = makePlanResult(specFile).orExit
      val main = plans.main
      Runner.run(main, joBin).orExit
      main.task match
        case app: CompileTask.AppTask =>
          Runner.execute(app, appArgs).orExit

        case _: CompileTask.LibTask =>
          die("'jo run' requires an app build (no [package] section)")

  def buildPackage(args: Array[String])(using Logger): Unit =
    withDefaultPackageProvider:
      try Release.buildPackage(args)
      catch
        case e: ToolError =>
          Logger.error(s"error: ${e.getMessage}\n")
          sys.exit(1)

  // ---- Helpers ---------------------------------------------------------------

  def makePlanResult(specFile: String)(using PackageProvider): Result[(List[ModulePlan], Path)] =
    makePlanResult(specFile)(JoResolver.resolve)

  def makePlanResult(specFile: String)(resolveJo: VersionSpec => Result[(Version, Path)])(using PackageProvider): Result[(List[ModulePlan], Path)] =
    try
      val path    = Paths.get(specFile).toAbsolutePath
      val specDir = path.getParent
      val spec    = Project.loadSpec(specDir, path.getFileName.toString)
      val lockPath = lockPathFor(path)
      resolveJo(spec.jo).flatMap: (joVersion, joPath) =>
        val project = Project.resolve(spec, specDir)
        materializeRegistryLibs(project, specDir, lockPath, useExistingLock = true).map: registrySastDirs =>
          (Planner.plan(project, joVersion, registrySastDirs), joPath)
    catch
      case e: ToolError => Result.Err(e.getMessage)
      case e: TomlError => Result.Err(e.getMessage)

  def lockResult(specFile: String)(using PackageProvider): Result[Unit] =
    try
      val path = Paths.get(specFile).toAbsolutePath
      val specDir = path.getParent
      val spec = Project.loadSpec(specDir, path.getFileName.toString)
      val project = Project.resolve(spec, specDir)
      val lockPath = lockPathFor(path)
      resolvePackages(project, lockPath, useExistingLock = false).flatMap: pkgs =>
        writeLock(lockPath, pkgs)
    catch
      case e: ToolError => Result.Err(e.getMessage)
      case e: TomlError => Result.Err(e.getMessage)

  private def materializeRegistryLibs(
    project: Project,
    rootDir: Path,
    lockPath: Path,
    useExistingLock: Boolean,
  )(using PackageProvider): Result[Map[String, Path]] =
    resolvePackages(project, lockPath, useExistingLock).flatMap: pkgs =>
      writeLock(lockPath, pkgs).map: _ =>
        pkgs.map: pkg =>
          pkg.name -> materializePackage(pkg, rootDir, project.spec.name)
        .toMap

  private def resolvePackages(
    project: Project,
    lockPath: Path,
    useExistingLock: Boolean,
  )(using PackageProvider): Result[List[ResolvedPackage]] =
    if !useExistingLock then
      DependencyResolver.resolveProject(project)
    else
      loadLock(lockPath).flatMap: lockOpt =>
        lockOpt match
          case Some(lock) => DependencyResolver.resolveProject(project, lock)
          case None       => DependencyResolver.resolveProject(project)

  private def loadLock(path: Path): Result[Option[LockFile]] =
    LockFile.load(path)

  private def writeLock(path: Path, pkgs: List[ResolvedPackage]): Result[Unit] =
    if pkgs.isEmpty then
      if java.nio.file.Files.exists(path) then
        java.nio.file.Files.delete(path)
      Result.unit
    else
      val locked = pkgs
        .sortBy(_.name)
        .map: pkg =>
        LockedPackage(pkg.name, pkg.version.toString, Digest.sha512Hex(pkg.path))
      LockFile.write(path, LockFile(locked))

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

  private def lockPathFor(specPath: Path): Path =
    val fileName = specPath.getFileName.toString
    val stem = fileName.lastIndexOf('.') match
      case -1 => fileName
      case i  => fileName.take(i)
    specPath.resolveSibling(s"$stem.lock")

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
