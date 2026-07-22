package tool

import java.io.IOException
import java.nio.file.Path
import scala.collection.mutable

import tool.toml.TomlError

/** Helpers for the `jo build`, `jo check`, `jo run`, and related project commands. */
object Build:
  private val specOpt = CommandLine.OptionStringSetting("--spec", "project spec file")

  case class ProjectCommandArgs(specFile: String, module: Option[ModuleId])
  case class RunCommandArgs(specFile: String, module: Option[ModuleId], appArgs: List[String])

  def clean(project: Project, module: Option[ModuleId])(using Logger): Result[Unit] =
    module match
      case Some(id) =>
        project.requireModule(id).flatMap: _ =>
          cleanDir(project.buildDir(id))

      case None =>
        cleanDir(project.dir.resolve(".build"))

  private def cleanDir(buildDir: Path)(using Logger): Result[Unit] =
    try
      if java.nio.file.Files.exists(buildDir) then
        deleteDir(buildDir)
        Logger.info(s"[clean] removed ${LogFormat.path(buildDir)}\n")
        Result.unit
      else
        Logger.info(s"[clean] nothing to clean\n")
        Result.unit
    catch
      case e: IOException => Result.Err(s"error: ${e.getMessage}")

  def buildDoc(project: Project, module: ModuleId)(using Logger, PackageProvider): Result[Unit] =
    makePlanResult(project, List(module)).flatMap: plans =>
      val plan = plans.modules.head
      val outDir = project.buildDir(module).resolve("doc")
      val withDoc = plan.copy(
        task = plan.task match
          case lib: CompileTask.LibTask =>
            lib.copy(compileOptions = lib.compileOptions ++ docOptions(project, module))

          case app: CompileTask.AppTask =>
            CompileTask.LibTask(app.sources, app.checkLibs, app.sastDir, docOptions(project, module))
      )
      Runner.doc(withDoc, outDir).map: _ =>
        Logger.info(s"[output] ${LogFormat.path(outDir)}\n")

  def deps(project: Project, module: ModuleId)(using Logger, PackageProvider): Result[Unit] =
    depsResult(project, module).map: output =>
      print(output)

  def lock(project: Project)(using Logger, PackageProvider): Result[Unit] =
    lockResult(project)

  def build(project: Project, module: ModuleId)(using Logger, PackageProvider): Result[Unit] =
    makePlanResult(project, List(module)).flatMap: plans =>
      Runner.run(plans.modules.head)

  def check(project: Project, module: ModuleId)(using Logger, PackageProvider): Result[Unit] =
    makePlanResult(project, List(module)).flatMap: plans =>
      Runner.check(plans.modules.head, "check")

  def run(project: Project, module: ModuleId, appArgs: List[String])(using Logger, PackageProvider): Result[Unit] =
    makePlanResult(project, List(module)).flatMap: plans =>
      val plan = plans.modules.head
      Runner.run(plan).flatMap: _ =>
        plan.task match
          case app: CompileTask.AppTask =>
            Logger.info(s"[run] ${module.value}\n")
            Runner.runInteractive(app, appArgs)

          case _: CompileTask.LibTask =>
            Result.Err(s"error: 'jo run' requires an app module, but '${module.value}' is kind = \"lib\"")

  // ---- Helpers ---------------------------------------------------------------

  def selectedModule(project: Project, parsed: ProjectCommandArgs): ModuleId =
    parsed.module.getOrElse(project.defaultModuleId)

  def selectedModule(project: Project, parsed: RunCommandArgs): ModuleId =
    parsed.module.getOrElse(project.defaultModuleId)

  def makePlanResult(project: Project, modules: List[ModuleId])(using Logger, PackageProvider): Result[ProjectPlan] =
    try
      materializeRegistryLibs(project, modules).flatMap: registrySastDirs =>
        Planner.plan(project, modules, registrySastDirs)
    catch
      case e: ArchiveError => Result.Err(e.getMessage)
      case e: TomlError => Result.Err(e.getMessage)

  def lockResult(project: Project)(using Logger, PackageProvider): Result[Unit] =
    try
      val lockPath = LockFile.pathForSpec(project.specPath)
      resolvePackages(project, project.moduleIds, lockPath, useExistingLock = false).flatMap: resolved =>
        warnUnusedPinning(resolved)
        validatePackageDepths(project, resolved, project.moduleIds).flatMap: _ =>
          writeLock(lockPath, project.joVersion, resolved.packages)
    catch
      case e: ArchiveError => Result.Err(e.getMessage)
      case e: TomlError => Result.Err(e.getMessage)

  def depsResult(project: Project, module: ModuleId)(using Logger, PackageProvider): Result[String] =
    try
      val lockPath = LockFile.pathForSpec(project.specPath)
      resolvePackages(project, List(module), lockPath, useExistingLock = true, requireLockCoverage = true).flatMap: resolved =>
        warnUnusedPinning(resolved)
        validatePackageDepths(project, resolved, List(module)).map: _ =>
          DepsPrinter.render(project, List(module), resolved)
    catch
      case e: ArchiveError => Result.Err(e.getMessage)
      case e: TomlError => Result.Err(e.getMessage)

  private def materializeRegistryLibs(
    project: Project,
    modules: List[ModuleId],
  )(using logger: Logger, provider: PackageProvider): Result[Planner.RegistrySastDirs] =
    val lockPath = LockFile.pathForSpec(project.specPath)
    resolvePackagesForBuild(project, modules, lockPath).flatMap: resolved =>
      warnUnusedPinning(resolved)
      validatePackageDepths(project, resolved, modules).flatMap: _ =>
        resolved.packages.foldLeft(Result.Ok(Map.empty[String, Path]): Result[Planner.RegistrySastDirs]): (pkgAcc, pkg) =>
          pkgAcc.flatMap: currentPaths =>
            provider.materialize(pkg.name, pkg.version).map: unpacked =>
              currentPaths + (pkg.name -> unpacked)

  private def resolvePackagesForBuild(
    project: Project,
    modules: List[ModuleId],
    lockPath: Path,
  )(using PackageProvider): Result[ResolutionResult] =
    loadLock(lockPath).flatMap:
      case Some(lock) =>
        DependencyResolver.resolveProject(project, modules, lock).flatMap: resolved =>
          requireLockCoverage(lock, resolved).map(_ => resolved)

      case None =>
        DependencyResolver.resolveProject(project, project.moduleIds).flatMap: resolvedAll =>
          validatePackageDepths(project, resolvedAll, modules).flatMap: _ =>
            makeLock(project.joVersion, resolvedAll.packages).flatMap: lock =>
              LockFile.write(lockPath, lock).flatMap: _ =>
                DependencyResolver.resolveProject(project, modules, lock)

  private def resolvePackages(
    project: Project,
    modules: List[ModuleId],
    lockPath: Path,
    useExistingLock: Boolean,
    requireLockCoverage: Boolean = false,
  )(using PackageProvider): Result[ResolutionResult] =
    if !useExistingLock then
      DependencyResolver.resolveProject(project, modules)
    else
      loadLock(lockPath).flatMap:
        case Some(lock) =>
          DependencyResolver.resolveProject(project, modules, lock).flatMap: resolved =>
            if requireLockCoverage then this.requireLockCoverage(lock, resolved).map(_ => resolved)
            else Result.Ok(resolved)
        case None       => DependencyResolver.resolveProject(project, modules)

  private def validatePackageDepths(
    project: Project,
    resolved: ResolutionResult,
    modules: List[ModuleId],
  ): Result[Unit] =
    modules.distinct.foldLeft(Result.unit): (acc, module) =>
      acc.flatMap: _ =>
        val info = resolved.packageDepthByModule.getOrElse(module, DepthInfo(0, Nil))
        val allowedDepth = project.depthOf(module)

        if info.depth > allowedDepth then
          Result.Err(
            s"""package dependency depth exceeded for module '${module.value}': actual ${info.depth}, allowed $allowedDepth
               |
               |  Path: ${(project.moduleLabel(project, module) :: info.deepestPath).mkString(" -> ")}""".stripMargin
          )
        else
          Result.unit

  private def loadLock(path: Path): Result[Option[LockFile]] =
    LockFile.load(path)

  private def requireLockCoverage(lock: LockFile, resolved: ResolutionResult): Result[Unit] =
    val locked = lock.packages.map(_.name).toSet
    val missing = resolved.packages.map(_.name).filterNot(locked).sorted
    if missing.isEmpty then
      Result.unit
    else
      Result.Err(s"lock file is missing package entries for: ${missing.mkString(", ")}; run 'jo lock'")

  private def makeLock(joVersion: Version, pkgs: List[ResolvedPackage])(using provider: PackageProvider): Result[LockFile] =
    val locked = new mutable.ArrayBuffer[LockedPackage]
    val sorted = pkgs.sortBy(_.name)
    val it = sorted.iterator
    var error: String | Null = null

    while it.hasNext && error == null do
      val pkg = it.next()
      provider.digest(pkg.name, pkg.version) match
        case Result.Ok(digest) =>
          locked += LockedPackage(pkg.name, pkg.version.toString, digest)

        case Result.Err(msg) =>
          error = msg

    if error != null then
      Result.Err(error)
    else
      Result.Ok(LockFile(Some(joVersion), locked.toList))

  private def writeLock(path: Path, joVersion: Version, pkgs: List[ResolvedPackage])(using provider: PackageProvider): Result[Unit] =
    makeLock(joVersion, pkgs).flatMap(LockFile.write(path, _))

  private def docOptions(project: Project, module: ModuleId): List[String] =
    val docSpec = project.doc.getOrElse(DocSpec())
    val options = mutable.ArrayBuffer[String](
      "--doc",
      "--out",
      project.buildDir(module).resolve("doc").toString,
      "--title",
      docSpec.title.getOrElse(module.value),
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
          Result.Ok(ProjectCommandArgs(parsed.value(specOpt).getOrElse("jo.toml"), None))
        case module :: Nil =>
          Result.Ok(ProjectCommandArgs(parsed.value(specOpt).getOrElse("jo.toml"), Some(ModuleId(module))))
        case arg :: _ =>
          Result.Err(s"error: unexpected argument '$arg'")

  def parseRunArgs(args: Array[String]): Result[RunCommandArgs] =
    CommandLine.parse(args, List(CommandLine.verboseOpt, specOpt)).flatMap: parsed =>
      parsed.positional match
        case Nil =>
          Result.Ok(RunCommandArgs(parsed.value(specOpt).getOrElse("jo.toml"), None, parsed.trailing))
        case module :: Nil =>
          Result.Ok(RunCommandArgs(parsed.value(specOpt).getOrElse("jo.toml"), Some(ModuleId(module)), parsed.trailing))
        case arg :: _ =>
          Result.Err(s"error: unexpected argument '$arg' (use '--' to pass app arguments)")

private[tool] def deleteDir(dir: Path): Unit =
  if java.nio.file.Files.exists(dir) then
    java.nio.file.Files.walk(dir)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(java.nio.file.Files.delete)
