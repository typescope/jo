package tool

import java.nio.file.{Files, Path}
import tool.toml.{TomlError, TomlParser}

/** A local project discovered through path-dependency expansion. */
case class ProjectDep(
  name: String,
  link: DepLink,
  project: Project,
)

/** A git dependency resolved to a precompiled .joy release asset. */
case class GitPackageDep(
  depName: String,
  gitUrl:  String,
  version: Version,
  joyPath: Path,
  joyUrl:  String,
  sha512:  String,
  depInfo: PackageDependencyInfo,
)

/** A git dependency resolved by downloading and compiling source. */
case class GitSourceDep(
  depName: String,
  gitUrl:  String,
  rev:     String,
)

final class Project private (
  val dir: Path,
  val specPath: Path,
  private val spec: BuildSpec,
  val deps: List[ProjectDep],
  val testDeps: List[ProjectDep],
  val joVersion: Version,
  val joBin: Path,
  val gitPackageDeps: List[GitPackageDep] = Nil,
  val gitSourceDeps: List[GitSourceDep] = Nil,
):
  def name: String = spec.name

  def joVersionSpec: VersionSpec = spec.jo

  def pinning: Map[String, Version] = spec.pinning

  def defaultDepth: Int = spec.depth.getOrElse(if isLib then 0 else 1)

  def mainDepth: Int = spec.main.depth.getOrElse(defaultDepth)

  def testDepth: Int = spec.test.flatMap(_.depth).getOrElse(spec.depth.getOrElse(mainDepth))

  def depthOf(module: ModuleKind): Int = module match
    case ModuleKind.Main => mainDepth
    case ModuleKind.Test => testDepth

  def isLib: Boolean = spec.isLib

  def pkg: Option[PackageSpec] = spec.pkg

  def doc: Option[DocSpec] = spec.doc

  def main: ModuleSpec = spec.main

  def test: Option[ModuleSpec] = spec.test

  def runtime: Option[String] = spec.pkg.flatMap(_.runtime)

  /** Root of this project's build output: `<dir>/.build/<name>/`. */
  def buildDir: Path = dir.resolve(s".build/$name")

  /** Versioned build output root: `<dir>/.build/<name>/jo-<major>.<minor>/`. */
  def buildBaseDir: Path =
    buildDir.resolve(s"jo-${joVersion.major}.${joVersion.minor}")

  /** Main module sast output directory. */
  def mainSastDir: Path =
    buildBaseDir.resolve("sast")

  /** Test module sast output directory. */
  def testSastDir: Path =
    buildBaseDir.resolve("sast-test")

object Project:
  def load(specPath: Path): Result[Project] =
    load(specPath, JoResolver.resolve, JoResolver.resolveExact)

  def load(specPath: Path, resolveJo: VersionSpec => Result[(Version, Path)]): Result[Project] =
    load(specPath, resolveJo, version => resolveJo(VersionSpec(Version(version.major, version.minor, 0))).map(_._2))

  def load(
    specPath: Path,
    resolveJo: VersionSpec => Result[(Version, Path)],
    resolveExactJo: Version => Result[Path],
  ): Result[Project] =
    val absolutePath = specPath.toAbsolutePath
    val canonicalSpecPath = absolutePath.toRealPath()
    val specDir = canonicalSpecPath.getParent
    loadSpec(specDir, canonicalSpecPath.getFileName.toString).flatMap: spec =>
      val lockPath = LockFile.pathForSpec(canonicalSpecPath)
      LockFile.load(lockPath).flatMap: lockOpt =>
        resolveCompilerFromLock(spec.jo, lockOpt, resolveJo, resolveExactJo).flatMap: (joVersion, joBin) =>
          val lockedGitDeps = lockOpt.map(_.gitDeps.map(d => d.name -> d).toMap).getOrElse(Map.empty)
          resolve(spec, canonicalSpecPath, joVersion, joBin, lockedGitDeps)

  private def resolveCompilerFromLock(
    constraint: VersionSpec,
    lockOpt: Option[LockFile],
    resolveJo: VersionSpec => Result[(Version, Path)],
    resolveExactJo: Version => Result[Path],
  ): Result[(Version, Path)] =
    lockOpt match
      case Some(lock) =>
        lock.jo match
          case Some(version) =>
            if constraint.contains(version) then
              resolveExactJo(version).map(version -> _)
            else
              resolveJo(constraint)

          case None =>
            resolveJo(constraint)

      case None =>
        resolveJo(constraint)

  /** Resolve all path and git dependencies starting from rootSpec at rootDir.
   *  Registry deps are ignored at this stage.
   *  All path deps share the same joVersion/joBin as the root.
   */
  def resolve(
    rootSpec: BuildSpec,
    rootSpecPath: Path,
    joVersion: Version,
    joBin: Path,
    lockedGitDeps: Map[String, LockedGitDep] = Map.empty,
  ): Result[Project] =
    val resolved = collection.mutable.Map.empty[Path, Project]
    val heights = collection.mutable.Map.empty[Path, Int]
    val inProgress = collection.mutable.Set.empty[Path]
    val inProgressNames = collection.mutable.ArrayBuffer.empty[String]

    case class DepResolution(
      projectDeps: List[ProjectDep],
      gitPackageDeps: List[GitPackageDep],
      gitSourceDeps: List[GitSourceDep],
    )

    def resolveDeps(specDir: Path, depEntries: List[(String, DepSpec)]): Result[DepResolution] =
      val orderedProject  = collection.mutable.ListBuffer.empty[ProjectDep]
      val orderedPackages = collection.mutable.ListBuffer.empty[GitPackageDep]
      val orderedSource   = collection.mutable.ListBuffer.empty[GitSourceDep]
      val seen = collection.mutable.LinkedHashSet.empty[Path]

      depEntries.foldLeft(Result.Ok(()): Result[Unit]): (acc, entry) =>
        acc.flatMap: _ =>
          val (depName, depSpec) = entry
          depSpec.source match
            case DepSource.Path(relPath, specFile) =>
              val depDir = specDir.resolve(relPath).normalize().toRealPath()
              val depToml = specFile.getOrElse("jo.toml")
              val depSpecPath = depDir.resolve(depToml).toRealPath()
              loadSpec(depDir, depToml).flatMap: depBuildSpec =>
                if !depBuildSpec.jo.contains(joVersion) then
                  Result.Err(
                    s"path dependency '$depName' requires Jo ${depBuildSpec.jo.show}, but the selected compiler is $joVersion"
                  )
                else
                  visit(depName, depBuildSpec, depSpecPath).map: dep =>
                    if seen.add(dep.dir) then
                      orderedProject += ProjectDep(depName, depSpec.link, dep)

            case DepSource.Registry(_) =>
              Result.unit

            case DepSource.Git(url, ref) =>
              GitSource.resolve(url, ref, lockedGitDeps.get(depName), joVersion, Config.cache).flatMap:
                case GitResolution.Precompiled(joyPath, joyUrl, sha512, version, depInfo) =>
                  orderedPackages += GitPackageDep(depName, url, version, joyPath, joyUrl, sha512, depInfo)
                  Result.unit

                case GitResolution.Source(sourceDir, rev) =>
                  orderedSource += GitSourceDep(depName, url, rev)
                  val depToml    = "jo.toml"
                  val depSpecPath = sourceDir.resolve(depToml)
                  loadSpec(sourceDir, depToml).flatMap: depBuildSpec =>
                    if !depBuildSpec.jo.contains(joVersion) then
                      Result.Err(
                        s"git dependency '$depName' requires Jo ${depBuildSpec.jo.show}, but the selected compiler is $joVersion"
                      )
                    else
                      visit(depName, depBuildSpec, depSpecPath).map: dep =>
                        if seen.add(dep.dir) then
                          orderedProject += ProjectDep(depName, depSpec.link, dep)
      .map(_ => DepResolution(orderedProject.toList, orderedPackages.toList, orderedSource.toList))

    def visit(name: String, spec: BuildSpec, specPath: Path): Result[Project] =
      val canonicalSpecPath = specPath.toRealPath()
      val canonicalDir = canonicalSpecPath.getParent

      resolved.get(canonicalDir) match
        case Some(project) => return Result.Ok(project)
        case None =>

      if inProgress.contains(canonicalDir) then
        val cycle = (inProgressNames.dropWhile(_ != name) :+ name).mkString(" -> ")
        return Result.Err(s"circular dependency detected: $cycle")

      inProgress += canonicalDir
      inProgressNames += name

      val result =
        resolveDeps(canonicalDir, spec.main.dependencies.toList).flatMap: mainResolved =>
          val mainSet = mainResolved.projectDeps.iterator.map(_.project.dir).toSet
          spec.test match
            case Some(test) =>
              resolveDeps(canonicalDir, test.dependencies.toList).map: testResolved =>
                val testDeps = testResolved.projectDeps.filterNot(dep => mainSet.contains(dep.project.dir))
                val allGitPackageDeps = mainResolved.gitPackageDeps ++ testResolved.gitPackageDeps
                val allGitSourceDeps  = mainResolved.gitSourceDeps  ++ testResolved.gitSourceDeps
                val depHeights = mainResolved.projectDeps.map(dep => heights(dep.project.dir))
                val height = depHeights.maxOption.map(_ + 1).getOrElse(0)
                val project = Project(
                  canonicalDir, canonicalSpecPath, spec,
                  mainResolved.projectDeps, testDeps, joVersion, joBin,
                  allGitPackageDeps, allGitSourceDeps,
                )
                resolved(canonicalDir) = project
                heights(canonicalDir) = height
                project
            case None =>
              val depHeights = mainResolved.projectDeps.map(dep => heights(dep.project.dir))
              val height = depHeights.maxOption.map(_ + 1).getOrElse(0)
              val project = Project(
                canonicalDir, canonicalSpecPath, spec,
                mainResolved.projectDeps, Nil, joVersion, joBin,
                mainResolved.gitPackageDeps, mainResolved.gitSourceDeps,
              )
              resolved(canonicalDir) = project
              heights(canonicalDir) = height
              Result.Ok(project)

      result match
        case ok @ Result.Ok(_) =>
          inProgress -= canonicalDir
          inProgressNames -= name
          ok
        case err @ Result.Err(_) =>
          inProgress -= canonicalDir
          inProgressNames -= name
          err

    visit(rootSpec.name, rootSpec, rootSpecPath.toRealPath()).flatMap: root =>
      validateRuntime(root.spec, allDeps(root)).map(_ => root)

  def allDeps(root: Project): List[Project] =
    val ordered = collection.mutable.ListBuffer.empty[Project]
    val seen = collection.mutable.LinkedHashSet.empty[Path]

    def collect(edges: List[ProjectDep]): Unit =
      for dep <- edges do
        collect(dep.project.deps)
        collect(dep.project.testDeps)

        if seen.add(dep.project.dir) then
          ordered += dep.project

    collect(root.deps)
    collect(root.testDeps)
    ordered.toList

  def mainDepsTopological(root: Project): List[Project] =
    val ordered = collection.mutable.ListBuffer.empty[Project]
    val seen = collection.mutable.LinkedHashSet.empty[Path]

    def collect(edges: List[ProjectDep]): Unit =
      for dep <- edges do
        collect(dep.project.deps)

        if seen.add(dep.project.dir) then
          ordered += dep.project

    collect(root.deps)
    ordered.toList

  def testDepsTopological(root: Project): List[Project] =
    val ordered = collection.mutable.ListBuffer.empty[Project]
    val seen = collection.mutable.LinkedHashSet.empty[Path]
    val mainSet = mainDepsTopological(root).iterator.map(_.dir).toSet

    def collect(edges: List[ProjectDep]): Unit =
      for dep <- edges do
        collect(dep.project.deps)
        collect(dep.project.testDeps)

        if !mainSet.contains(dep.project.dir) && seen.add(dep.project.dir) then
          ordered += dep.project

    collect(root.testDeps)
    ordered.toList

  def loadSpec(dir: Path, tomlFile: String = "jo.toml"): Result[BuildSpec] =
    val file = dir.resolve(tomlFile)

    if !Files.exists(file) then
      return Result.Err(s"spec file not found: $file")

    val src = Files.readString(file)

    try Result.Ok(BuildSpec.decode(TomlParser.parse(src)))
    catch case e: TomlError =>
      Result.Err(s"in $file: ${e.getMessage}")

  private def validateRuntime(root: BuildSpec, deps: List[Project]): Result[Unit] =
    val constrainedDeps = deps.flatMap(dep => dep.runtime.filter(_ != "pure").map(dep.name -> _))
    val distinctRuntime = constrainedDeps.map(_._2).distinct

    if distinctRuntime.length > 1 then
      val summary = constrainedDeps.map((n, r) => s"'$n' ($r)").mkString(", ")
      return Result.Err(s"runtime conflict: path dependencies require different runtimes: $summary")

    val rootRuntime = root.pkg.flatMap(_.runtime)

    rootRuntime match
      case Some("pure") =>
        deps.find(dep => dep.runtime.exists(_ != "pure")) match
          case Some(dep) =>
            Result.Err(
              s"runtime conflict: root asserts runtime=pure but dependency '${dep.name}' requires runtime=${dep.runtime.nn}"
            )
          case None =>
            Result.unit

      case _ =>
        Result.unit
