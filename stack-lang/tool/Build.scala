package tool

import java.nio.file.Paths
import java.nio.file.Path
import tool.toml.TomlError

/** Entry points for the `jo build`, `jo check`, `jo run`, and `jo test` commands. */
object Build:
  def clean(args: Array[String])(using Logger): Unit =
    try
      val path = Paths.get(parseSpecFile(args)).toAbsolutePath
      val project = Project.load(path)
      val buildDir = project.buildDir

      if java.nio.file.Files.exists(buildDir) then
        deleteDir(buildDir)
        Logger.info(s"[clean] removed $buildDir\n")
      else
        Logger.info(s"[clean] nothing to clean (use 'jo clean' in each path dependency to clean those separately)\n")
    catch
      case e: ToolError => Logger.error(s"error: ${e.getMessage}\n"); sys.exit(1)
      case e: toml.TomlError => Logger.error(s"error: ${e.getMessage}\n"); sys.exit(1)

  def deps(args: Array[String])(using PackageProvider): Unit =
    print(depsResult(parseSpecFile(args)).orExit)

  def lock(args: Array[String])(using PackageProvider): Unit =
    lockResult(parseSpecFile(args)).orExit

  def build(args: Array[String])(using Logger, PackageProvider): Unit =
    val (plans, joBin) = makePlanResult(parseSpecFile(args), List(ModuleKind.Main)).orExit
    Runner.run(plans.main, joBin).orExit

  def check(args: Array[String])(using Logger, PackageProvider): Unit =
    val (plans, joBin) = makePlanResult(parseSpecFile(args), List(ModuleKind.Main)).orExit
    Runner.check(plans.main, joBin).orExit

  def test(args: Array[String])(using Logger, PackageProvider): Unit =
    val (plans, joBin) = makePlanResult(parseSpecFile(args), List(ModuleKind.Main, ModuleKind.Test)).orExit
    Runner.test(plans.test, joBin).orExit

  def run(args: Array[String])(using Logger, PackageProvider): Unit =
    val (specFile, appArgs) = parseRunArgs(args)
    val (plans, joBin) = makePlanResult(specFile, List(ModuleKind.Main)).orExit
    val main = plans.main
    Runner.run(main, joBin).orExit
    main.task match
      case app: CompileTask.AppTask =>
        Runner.runInteractive(app, appArgs).orExit

      case _: CompileTask.LibTask =>
        die("'jo run' requires an app build (no [package] section)")

  def buildPackage(args: Array[String])(using Logger, PackageProvider): Unit =
    try Release.buildPackage(args)
    catch
      case e: ToolError =>
        Logger.error(s"error: ${e.getMessage}\n")
        sys.exit(1)

  // ---- Helpers ---------------------------------------------------------------

  def makePlanResult(specFile: String, modules: List[ModuleKind] = List(ModuleKind.Main))(using PackageProvider): Result[(ProjectPlan, Path)] =
    try
      val path = Paths.get(specFile).toAbsolutePath
      val project = Project.load(path)
      val lockPath = lockPathFor(path)
      materializeRegistryLibs(project, lockPath, useExistingLock = true, modules).map: registrySastDirs =>
        (Planner.plan(project, registrySastDirs), project.joBin)
    catch
      case e: ToolError => Result.Err(e.getMessage)
      case e: TomlError => Result.Err(e.getMessage)

  def lockResult(specFile: String)(using PackageProvider): Result[Unit] =
    try
      val path = Paths.get(specFile).toAbsolutePath
      val project = Project.load(path)
      val lockPath = lockPathFor(path)
      resolvePackages(project, lockPath, useExistingLock = false).flatMap: resolved =>
        validatePackageDepths(project, resolved, List(ModuleKind.Main, ModuleKind.Test)).flatMap: _ =>
          writeLock(lockPath, resolved.packages)
    catch
      case e: ToolError => Result.Err(e.getMessage)
      case e: TomlError => Result.Err(e.getMessage)

  def depsResult(specFile: String)(using PackageProvider): Result[String] =
    try
      val path = Paths.get(specFile).toAbsolutePath
      val project = Project.load(path)
      val lockPath = lockPathFor(path)
      val modules =
        if project.test.isDefined then List(ModuleKind.Main, ModuleKind.Test)
        else List(ModuleKind.Main)

      resolvePackages(project, lockPath, useExistingLock = true).flatMap: resolved =>
        validatePackageDepths(project, resolved, modules).map: _ =>
          DepsPrinter.render(project, resolved)
    catch
      case e: ToolError => Result.Err(e.getMessage)
      case e: TomlError => Result.Err(e.getMessage)

  private def materializeRegistryLibs(
    project: Project,
    lockPath: Path,
    useExistingLock: Boolean,
    modules: List[ModuleKind],
  )(using PackageProvider): Result[Map[String, Path]] =
    resolvePackages(project, lockPath, useExistingLock).flatMap: resolved =>
      validatePackageDepths(project, resolved, modules).flatMap: _ =>
        writeLock(lockPath, resolved.packages).map: _ =>
          resolved.packages.map: pkg =>
            pkg.name -> materializePackage(pkg)
          .toMap

  private def resolvePackages(
    project: Project,
    lockPath: Path,
    useExistingLock: Boolean,
  )(using PackageProvider): Result[ResolutionResult] =
    if !useExistingLock then
      DependencyResolver.resolveProject(project)
    else
      loadLock(lockPath).flatMap: lockOpt =>
        lockOpt match
          case Some(lock) => DependencyResolver.resolveProject(project, lock)
          case None       => DependencyResolver.resolveProject(project)

  private def validatePackageDepths(
    project: Project,
    resolved: ResolutionResult,
    modules: List[ModuleKind],
  ): Result[Unit] =
    modules.distinct.foldLeft(Result.unit): (acc, module) =>
      acc.flatMap: _ =>
        val (actualDepth, deepestPath) = module match
          case ModuleKind.Main => (resolved.mainPackageDepth, resolved.mainDeepestPath)
          case ModuleKind.Test => (resolved.testPackageDepth, resolved.testDeepestPath)
        val allowedDepth = project.depthOf(module)

        if actualDepth > allowedDepth then
          val moduleName = module match
            case ModuleKind.Main => "main"
            case ModuleKind.Test => "test"

          Result.Err(
            s"""package dependency depth exceeded for '${project.name}' $moduleName module: actual $actualDepth, allowed $allowedDepth
               |
               |  Path: ${(project.name :: deepestPath).mkString(" -> ")}""".stripMargin
          )
        else
          Result.unit

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

  private def materializePackage(pkg: ResolvedPackage): Path =
    val outDir = Config.packageDir(pkg.name, pkg.version.toString)

    if !isMaterialized(pkg.path, outDir) then
      if java.nio.file.Files.exists(outDir) then deleteDir(outDir)
      JoyArchive.unpack(pkg.path, outDir)
      java.nio.file.Files.writeString(outDir.resolve(".digest"), Digest.sha512Hex(pkg.path))

    outDir

  private def isMaterialized(archive: Path, outDir: Path): Boolean =
    val marker = outDir.resolve(".digest")
    java.nio.file.Files.isDirectory(outDir) &&
    java.nio.file.Files.exists(marker) &&
    java.nio.file.Files.readString(marker) == Digest.sha512Hex(archive)

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

private[tool] def deleteDir(dir: Path): Unit =
  if java.nio.file.Files.exists(dir) then
    java.nio.file.Files.walk(dir)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(java.nio.file.Files.delete)
