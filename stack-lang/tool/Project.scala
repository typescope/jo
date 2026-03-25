package tool

import java.nio.file.{Files, Path}
import tool.toml.{TomlError, TomlParser}

/** A local project discovered through path-dependency expansion. */
case class Project(
  dir: Path,
  spec: BuildSpec,
  deps: List[Project],
  testDeps: List[Project],
):
  def allDeps: List[Project] =
    val ordered = collection.mutable.ListBuffer.empty[Project]
    val seen = collection.mutable.LinkedHashSet.empty[Path]

    def collect(projects: List[Project]): Unit =
      for dep <- projects do
        collect(dep.deps)
        collect(dep.testDeps)

        if seen.add(dep.dir) then
          ordered += dep

    collect(deps)
    collect(testDeps)
    ordered.toList

  def mainDepsTopological: List[Project] =
    val ordered = collection.mutable.ListBuffer.empty[Project]
    val seen = collection.mutable.LinkedHashSet.empty[Path]

    def collect(projects: List[Project]): Unit =
      for dep <- projects do
        collect(dep.deps)

        if seen.add(dep.dir) then
          ordered += dep

    collect(deps)
    ordered.toList

  def testDepsTopological: List[Project] =
    val ordered = collection.mutable.ListBuffer.empty[Project]
    val seen = collection.mutable.LinkedHashSet.empty[Path]
    val mainSet = mainDepsTopological.iterator.map(_.dir).toSet

    def collect(projects: List[Project]): Unit =
      for dep <- projects do
        collect(dep.deps)
        collect(dep.testDeps)

        if !mainSet.contains(dep.dir) && seen.add(dep.dir) then
          ordered += dep

    collect(testDeps)
    ordered.toList

object Project:
  /** Resolve all path dependencies starting from rootSpec at rootDir.
   *  Registry deps are ignored at this stage.
   */
  def resolve(rootSpec: BuildSpec, rootDir: Path): Project =
    val resolved = collection.mutable.Map.empty[Path, Project]
    val heights = collection.mutable.Map.empty[Path, Int]
    val inProgress = collection.mutable.Set.empty[Path]
    val inProgressNames = collection.mutable.ArrayBuffer.empty[String]

    def resolveDeps(specDir: Path, depEntries: List[(String, DepSpec)]): List[Project] =
      val ordered = collection.mutable.ListBuffer.empty[Project]
      val seen = collection.mutable.LinkedHashSet.empty[Path]

      for (depName, depSpec) <- depEntries do
        depSpec.source match
          case DepSource.Path(relPath, specFile) =>
            val depDir = specDir.resolve(relPath).normalize().toRealPath()
            val depToml = specFile.getOrElse("jo.toml")
            val depBuildSpec = loadSpec(depDir, depToml)
            val dep = visit(depName, depBuildSpec, depDir)

            if seen.add(dep.dir) then
              ordered += dep

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
        val mainSet = deps.iterator.map(_.dir).toSet
        resolveDeps(canonicalDir, test.dependencies.toList).filterNot(dep => mainSet.contains(dep.dir))

      val depHeights = deps.map(dep => heights(dep.dir))
      val height = depHeights.maxOption.map(_ + 1).getOrElse(0)
      validateDepth(spec, height)

      val project = Project(canonicalDir, spec, deps, testDeps)
      resolved(canonicalDir) = project
      heights(canonicalDir) = height

      inProgress -= canonicalDir
      inProgressNames -= name

      project

    val root = visit(rootSpec.name, rootSpec, rootDir.toRealPath())
    validateFfi(root.spec, root.allDeps)
    root

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
          dep.spec.pkg.flatMap(_.ffi) match
            case Some(f) if f != "none" =>
              throw ToolError(
                s"FFI conflict: root asserts ffi=none but dependency '${dep.spec.name}' has ffi=$f"
              )

            case _ =>
              ()

      case _ =>
        ()

  private def validateDepth(spec: BuildSpec, actualDepth: Int): Unit =
    val allowedDepth = spec.depth.getOrElse(if spec.isLib then 0 else 1)

    if actualDepth > allowedDepth then
      throw ToolError(
        s"dependency depth exceeded for '${spec.name}': actual $actualDepth, allowed $allowedDepth"
      )

/** Build tool error (user-facing, no stack trace needed). */
case class ToolError(message: String) extends Exception(message)
