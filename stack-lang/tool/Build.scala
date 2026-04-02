package tool

import java.io.IOException
import java.nio.file.Path
import tool.toml.TomlError

/** Helpers for the `jo build`, `jo check`, `jo run`, and `jo test` commands. */
object Build:
  private val specOpt = CommandLine.OptionStringSetting("--spec", "project spec file")

  case class ProjectCommandArgs(specFile: String)
  case class RunCommandArgs(specFile: String, appArgs: List[String])

  def clean(project: Project)(using Logger): Result[Unit] =
    try
      val buildDir = project.buildDir

      if java.nio.file.Files.exists(buildDir) then
        deleteDir(buildDir)
        Logger.info(s"[clean] removed ${LogFormat.path(buildDir)}\n")
        Result.unit
      else
        Logger.info(s"[clean] nothing to clean (use 'jo clean' in each path dependency to clean those separately)\n")
        Result.unit
    catch
      case e: IOException => Result.Err(s"error: ${e.getMessage}")

  def buildDoc(project: Project)(using Logger, PackageProvider): Result[Unit] =
    makePlanResult(project, List(ModuleKind.Main)).flatMap: (plans, joBin) =>
      val outDir = project.buildDir.resolve("doc")
      val mainWithDoc = plans.main.copy(
        task = plans.main.task match
          case lib: CompileTask.LibTask =>
            lib.copy(compileOptions = lib.compileOptions ++ docOptions(project))

          case app: CompileTask.AppTask =>
            CompileTask.LibTask(app.sources, app.checkLibs, app.sastDir, docOptions(project))
      )
      Runner.doc(mainWithDoc, joBin, outDir).map: _ =>
        Logger.info(s"[output] ${LogFormat.path(outDir)}\n")

  def deps(project: Project)(using Logger, PackageProvider): Result[Unit] =
    depsResult(project).map: output =>
      print(output)

  def lock(project: Project)(using Logger, PackageProvider): Result[Unit] =
    lockResult(project)

  def build(project: Project)(using Logger, PackageProvider): Result[Unit] =
    makePlanResult(project, List(ModuleKind.Main)).flatMap: (plans, joBin) =>
      Runner.run(plans.main, joBin)

  def check(project: Project)(using Logger, PackageProvider): Result[Unit] =
    makePlanResult(project, List(ModuleKind.Main)).flatMap: (plans, joBin) =>
      Runner.check(plans.main, joBin, "check")

  def test(project: Project)(using Logger, PackageProvider): Result[Unit] =
    makePlanResult(project, List(ModuleKind.Main, ModuleKind.Test)).flatMap: (plans, joBin) =>
      Runner.test(plans.test, joBin)

  def run(project: Project, appArgs: List[String])(using Logger, PackageProvider): Result[Unit] =
    makePlanResult(project, List(ModuleKind.Main)).flatMap: (plans, joBin) =>
      val main = plans.main
      Runner.run(main, joBin).flatMap: _ =>
        main.task match
          case app: CompileTask.AppTask =>
            Logger.info(s"[run] ${project.name}\n")
            Runner.runInteractive(app, appArgs)

          case _: CompileTask.LibTask =>
            Result.Err("error: 'jo run' requires an app build (no [package] section)")

  // ---- Helpers ---------------------------------------------------------------

  def makePlanResult(project: Project, modules: List[ModuleKind])(using Logger, PackageProvider): Result[(ProjectPlan, Path)] =
    try
      val lockPath = LockFile.pathForSpec(project.specPath)
      materializeRegistryLibs(project, lockPath, useExistingLock = true, modules).map: registrySastDirs =>
        (Planner.plan(project, registrySastDirs), project.joBin)
    catch
      case e: ArchiveError => Result.Err(e.getMessage)
      case e: TomlError => Result.Err(e.getMessage)

  def lockResult(project: Project)(using Logger, PackageProvider): Result[Unit] =
    try
      val lockPath = LockFile.pathForSpec(project.specPath)
      resolvePackages(project, lockPath, useExistingLock = false).flatMap: resolved =>
        warnUnusedPinning(resolved)
        validatePackageDepths(project, resolved, List(ModuleKind.Main, ModuleKind.Test)).flatMap: _ =>
          writeLock(lockPath, project.joVersion, resolved.packages)
    catch
      case e: ArchiveError => Result.Err(e.getMessage)
      case e: TomlError => Result.Err(e.getMessage)

  def depsResult(project: Project)(using Logger, PackageProvider): Result[String] =
    try
      val lockPath = LockFile.pathForSpec(project.specPath)
      val modules =
        if project.test.isDefined then List(ModuleKind.Main, ModuleKind.Test)
        else List(ModuleKind.Main)

      resolvePackages(project, lockPath, useExistingLock = true).flatMap: resolved =>
        warnUnusedPinning(resolved)
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
  )(using logger: Logger, provider: PackageProvider): Result[Map[String, Path]] =
    resolvePackages(project, lockPath, useExistingLock).flatMap: resolved =>
      warnUnusedPinning(resolved)
      validatePackageDepths(project, resolved, modules).flatMap: _ =>
        writeLock(lockPath, project.joVersion, resolved.packages).flatMap: _ =>
          resolved.packages.foldLeft(Result.Ok(Map.empty[String, Path])): (acc, pkg) =>
            acc.flatMap: paths =>
              provider.materialize(pkg.name, pkg.version).map: unpacked =>
                paths + (pkg.name -> unpacked)

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

  private def writeLock(path: Path, joVersion: Version, pkgs: List[ResolvedPackage]): Result[Unit] =
    val locked = pkgs.sortBy(_.name).map: pkg =>
      LockedPackage(pkg.name, pkg.version.toString, Digest.sha512Hex(pkg.path))
    LockFile.write(path, LockFile(Some(joVersion), locked))

  private def docOptions(project: Project): List[String] =
    val docSpec = project.doc.getOrElse(DocSpec())
    val options = collection.mutable.ListBuffer[String](
      "--doc",
      "--out",
      project.buildDir.resolve("doc").toString,
      "--title",
      docSpec.title.getOrElse(project.name),
    )
    docSpec.readme.foreach(r => options ++= List("--readme", project.dir.resolve(r).toString))
    if docSpec.includePrivate then options += "--include-private"
    if docSpec.includeSource then options += "--include-source"
    options.toList

  private def warnUnusedPinning(resolved: ResolutionResult)(using Logger): Unit =
    resolved.unusedPins.foreach: (name, version) =>
      Logger.warn(s"warning: unused [pinning] entry $name = \"$version\"\n")

  def parseProjectArgs(args: Array[String]): Result[ProjectCommandArgs] =
    CommandLine.parse(args, List(CommandLine.verboseOpt, specOpt)).flatMap: parsed =>
      parsed.positional match
        case Nil =>
          Result.Ok(ProjectCommandArgs(parsed.value(specOpt).getOrElse("jo.toml")))
        case arg :: _ =>
          Result.Err(s"error: unexpected argument '$arg'")

  def parseRunArgs(args: Array[String]): Result[RunCommandArgs] =
    CommandLine.parse(args, List(CommandLine.verboseOpt, specOpt)).flatMap: parsed =>
      parsed.positional match
        case Nil =>
          Result.Ok(RunCommandArgs(parsed.value(specOpt).getOrElse("jo.toml"), parsed.trailing))
        case arg :: _ =>
          Result.Err(s"error: unexpected argument '$arg' (use '--' to pass app arguments)")
private[tool] def deleteDir(dir: Path): Unit =
  if java.nio.file.Files.exists(dir) then
    java.nio.file.Files.walk(dir)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(java.nio.file.Files.delete)
