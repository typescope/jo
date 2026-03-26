package tool

import java.nio.file.{Files, Path}
import tool.toml.{TomlError, TomlParser}

/** A local project discovered through path-dependency expansion. */
case class ProjectDep(
  name: String,
  link: DepLink,
  project: Project,
)

final class Project private (
  val dir: Path,
  val specPath: Path,
  private val spec: BuildSpec,
  val deps: List[ProjectDep],
  val testDeps: List[ProjectDep],
  val joVersion: Version,
  val joBin: Path,
):
  def name: String = spec.name

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

  def ffi: Option[String] = spec.pkg.flatMap(_.ffi)

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
    load(specPath, JoResolver.resolve)

  def load(specPath: Path, resolveJo: VersionSpec => Result[(Version, Path)]): Result[Project] =
    val absolutePath = specPath.toAbsolutePath
    val canonicalSpecPath = absolutePath.toRealPath()
    val specDir = canonicalSpecPath.getParent
    loadSpec(specDir, canonicalSpecPath.getFileName.toString).flatMap: spec =>
      resolveJo(spec.jo).flatMap: (joVersion, joBin) =>
        resolve(spec, canonicalSpecPath, joVersion, joBin)

  /** Resolve all path dependencies starting from rootSpec at rootDir.
   *  Registry deps are ignored at this stage.
   *  All path deps share the same joVersion/joBin as the root.
   */
  def resolve(rootSpec: BuildSpec, rootSpecPath: Path, joVersion: Version, joBin: Path): Result[Project] =
    val resolved = collection.mutable.Map.empty[Path, Project]
    val heights = collection.mutable.Map.empty[Path, Int]
    val inProgress = collection.mutable.Set.empty[Path]
    val inProgressNames = collection.mutable.ArrayBuffer.empty[String]

    def resolveDeps(specDir: Path, depEntries: List[(String, DepSpec)]): Result[List[ProjectDep]] =
      val ordered = collection.mutable.ListBuffer.empty[ProjectDep]
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
                visit(depName, depBuildSpec, depSpecPath).map: dep =>
                  if seen.add(dep.dir) then
                    ordered += ProjectDep(depName, depSpec.link, dep)

            case DepSource.Registry(_) =>
              Result.unit
      .map(_ => ordered.toList)

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
        resolveDeps(canonicalDir, spec.main.dependencies.toList).flatMap: deps =>
          val mainSet = deps.iterator.map(_.project.dir).toSet
          spec.test match
            case Some(test) =>
              resolveDeps(canonicalDir, test.dependencies.toList).map: testDeps0 =>
                val testDeps = testDeps0.filterNot(dep => mainSet.contains(dep.project.dir))
                val depHeights = deps.map(dep => heights(dep.project.dir))
                val height = depHeights.maxOption.map(_ + 1).getOrElse(0)
                val project = Project(canonicalDir, canonicalSpecPath, spec, deps, testDeps, joVersion, joBin)
                resolved(canonicalDir) = project
                heights(canonicalDir) = height
                project
            case None =>
              val depHeights = deps.map(dep => heights(dep.project.dir))
              val height = depHeights.maxOption.map(_ + 1).getOrElse(0)
              val project = Project(canonicalDir, canonicalSpecPath, spec, deps, Nil, joVersion, joBin)
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
      validateFfi(root.spec, allDeps(root)).map(_ => root)

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

  private def validateFfi(root: BuildSpec, deps: List[Project]): Result[Unit] =
    val rootFfi = root.pkg.flatMap(_.ffi)

    rootFfi match
      case Some("none") =>
        deps.find(dep => dep.ffi.exists(_ != "none")) match
          case Some(dep) =>
            Result.Err(
              s"FFI conflict: root asserts ffi=none but dependency '${dep.name}' has ffi=${dep.ffi.nn}"
            )
          case None =>
            Result.unit

      case _ =>
        Result.unit

/** Build tool error (user-facing, no stack trace needed). */
case class ToolError(message: String) extends Exception(message)
