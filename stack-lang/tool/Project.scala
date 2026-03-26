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
  def load(specPath: Path): Project =
    val absolutePath = specPath.toAbsolutePath
    val specDir = absolutePath.getParent
    val spec = loadSpec(specDir, absolutePath.getFileName.toString)
    val (joVersion, joBin) = JoResolver.resolve(spec.jo) match
      case Result.Ok(v)    => v
      case Result.Err(msg) => throw ToolError(msg)
    resolve(spec, specDir, joVersion, joBin)

  /** Resolve all path dependencies starting from rootSpec at rootDir.
   *  Registry deps are ignored at this stage.
   *  All path deps share the same joVersion/joBin as the root.
   */
  def resolve(rootSpec: BuildSpec, rootDir: Path, joVersion: Version, joBin: Path): Project =
    val resolved = collection.mutable.Map.empty[Path, Project]
    val heights = collection.mutable.Map.empty[Path, Int]
    val inProgress = collection.mutable.Set.empty[Path]
    val inProgressNames = collection.mutable.ArrayBuffer.empty[String]

    def resolveDeps(specDir: Path, depEntries: List[(String, DepSpec)]): List[ProjectDep] =
      val ordered = collection.mutable.ListBuffer.empty[ProjectDep]
      val seen = collection.mutable.LinkedHashSet.empty[Path]

      for (depName, depSpec) <- depEntries do
        depSpec.source match
          case DepSource.Path(relPath, specFile) =>
            val depDir = specDir.resolve(relPath).normalize().toRealPath()
            val depToml = specFile.getOrElse("jo.toml")
            val depBuildSpec = loadSpec(depDir, depToml)
            val dep = visit(depName, depBuildSpec, depDir)

            if seen.add(dep.dir) then
              ordered += ProjectDep(depName, depSpec.link, dep)

          case DepSource.Registry(_) =>
            ()

      ordered.toList

    def visit(name: String, spec: BuildSpec, specDir: Path): Project =
      val canonicalDir = specDir.toRealPath()

      resolved.get(canonicalDir) match
        case Some(project) => return project
        case None =>

      if inProgress.contains(canonicalDir) then
        val cycle = (inProgressNames.dropWhile(_ != name) :+ name).mkString(" -> ")
        throw ToolError(s"circular dependency detected: $cycle")

      inProgress += canonicalDir
      inProgressNames += name

      val deps = resolveDeps(canonicalDir, spec.main.dependencies.toList)
      val testDeps = spec.test.toList.flatMap: test =>
        val mainSet = deps.iterator.map(_.project.dir).toSet
        resolveDeps(canonicalDir, test.dependencies.toList).filterNot(dep => mainSet.contains(dep.project.dir))

      val depHeights = deps.map(dep => heights(dep.project.dir))
      val height = depHeights.maxOption.map(_ + 1).getOrElse(0)

      val project = Project(canonicalDir, spec, deps, testDeps, joVersion, joBin)
      resolved(canonicalDir) = project
      heights(canonicalDir) = height

      inProgress -= canonicalDir
      inProgressNames -= name

      project

    val root = visit(rootSpec.name, rootSpec, rootDir.toRealPath())
    validateFfi(root.spec, allDeps(root))
    root

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

  def loadSpec(dir: Path, tomlFile: String = "jo.toml"): BuildSpec =
    val file = dir.resolve(tomlFile)

    if !Files.exists(file) then
      throw ToolError(s"spec file not found: $file")

    val src = Files.readString(file)

    try BuildSpec.decode(TomlParser.parse(src))
    catch case e: TomlError =>
      throw ToolError(s"in $file: ${e.getMessage}")

  private def validateFfi(root: BuildSpec, deps: List[Project]): Unit =
    val rootFfi = root.pkg.flatMap(_.ffi)

    rootFfi match
      case Some("none") =>
        for dep <- deps do
          dep.ffi match
            case Some(f) if f != "none" =>
              throw ToolError(
                s"FFI conflict: root asserts ffi=none but dependency '${dep.name}' has ffi=$f"
              )

            case _ =>
              ()

      case _ =>
        ()

/** Build tool error (user-facing, no stack trace needed). */
case class ToolError(message: String) extends Exception(message)
