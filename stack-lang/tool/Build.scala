package tool

import java.io.IOException
import java.nio.file.Path
import tool.toml.TomlError

/** Helpers for the `jo build`, `jo check`, `jo run`, and `jo test` commands. */
object Build:
  def clean(project: Project)(using Logger): Result[Unit] =
    try
      val buildDir = project.buildDir

      if java.nio.file.Files.exists(buildDir) then
        deleteDir(buildDir)
        Logger.info(s"[clean] removed $buildDir\n")
        Result.unit
      else
        Logger.info(s"[clean] nothing to clean (use 'jo clean' in each path dependency to clean those separately)\n")
        Result.unit
    catch
      case e: IOException => Result.Err(s"error: ${e.getMessage}\n")

  def buildDoc(project: Project)(using Logger, PackageProvider): Result[Unit] =
    makePlanResult(project, List(ModuleKind.Main)).flatMap: (plans, joBin) =>
      val mainWithDoc = plans.main.copy(
        task = plans.main.task match
          case lib: CompileTask.LibTask =>
            lib.copy(compileOptions = lib.compileOptions ++ docOptions(project))

          case app: CompileTask.AppTask =>
            CompileTask.LibTask(app.sources, app.checkLibs, app.sastDir, docOptions(project))
      )
      Runner.check(mainWithDoc, joBin)

  def deps(project: Project)(using PackageProvider): Result[Unit] =
    depsResult(project).map: output =>
      print(output)

  def lock(project: Project)(using PackageProvider): Result[Unit] =
    lockResult(project)

  def build(project: Project)(using Logger, PackageProvider): Result[Unit] =
    makePlanResult(project, List(ModuleKind.Main)).flatMap: (plans, joBin) =>
      Runner.run(plans.main, joBin)

  def check(project: Project)(using Logger, PackageProvider): Result[Unit] =
    makePlanResult(project, List(ModuleKind.Main)).flatMap: (plans, joBin) =>
      Runner.check(plans.main, joBin)

  def test(project: Project)(using Logger, PackageProvider): Result[Unit] =
    makePlanResult(project, List(ModuleKind.Main, ModuleKind.Test)).flatMap: (plans, joBin) =>
      Runner.test(plans.test, joBin)

  def run(project: Project, appArgs: List[String])(using Logger, PackageProvider): Result[Unit] =
    makePlanResult(project, List(ModuleKind.Main)).flatMap: (plans, joBin) =>
      val main = plans.main
      Runner.run(main, joBin).flatMap: _ =>
        main.task match
          case app: CompileTask.AppTask =>
            Runner.runInteractive(app, appArgs)

          case _: CompileTask.LibTask =>
            Result.Err("error: 'jo run' requires an app build (no [package] section)\n")

  // ---- Helpers ---------------------------------------------------------------

  def makePlanResult(project: Project, modules: List[ModuleKind])(using PackageProvider): Result[(ProjectPlan, Path)] =
    try
      val lockPath = lockPathFor(project.specPath)
      materializeRegistryLibs(project, lockPath, useExistingLock = true, modules).map: registrySastDirs =>
        (Planner.plan(project, registrySastDirs), project.joBin)
    catch
      case e: ArchiveError => Result.Err(e.getMessage)
      case e: TomlError => Result.Err(e.getMessage)

  def lockResult(project: Project)(using PackageProvider): Result[Unit] =
    try
      val lockPath = lockPathFor(project.specPath)
      resolvePackages(project, lockPath, useExistingLock = false).flatMap: resolved =>
        validatePackageDepths(project, resolved, List(ModuleKind.Main, ModuleKind.Test)).flatMap: _ =>
          writeLock(lockPath, resolved.packages)
    catch
      case e: ArchiveError => Result.Err(e.getMessage)
      case e: TomlError => Result.Err(e.getMessage)

  def depsResult(project: Project)(using PackageProvider): Result[String] =
    try
      val lockPath = lockPathFor(project.specPath)
      val modules =
        if project.test.isDefined then List(ModuleKind.Main, ModuleKind.Test)
        else List(ModuleKind.Main)

      resolvePackages(project, lockPath, useExistingLock = true).flatMap: resolved =>
        validatePackageDepths(project, resolved, modules).map: _ =>
          DepsPrinter.render(project, resolved)
    catch
      case e: ArchiveError => Result.Err(e.getMessage)
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

  private def docOptions(project: Project): List[String] =
    val docSpec = project.doc.getOrElse(DocSpec())
    val options = collection.mutable.ListBuffer[String](
      "--doc",
      "--out",
      project.buildDir.resolve("doc").toString,
      "--title",
      docSpec.title.getOrElse(project.name),
    )
    if docSpec.includePrivate then options += "--include-private"
    if docSpec.includeSource then options += "--include-source"
    options.toList

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
private[tool] def deleteDir(dir: Path): Unit =
  if java.nio.file.Files.exists(dir) then
    java.nio.file.Files.walk(dir)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(java.nio.file.Files.delete)
