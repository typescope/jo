package tool

import java.nio.file.{Path, Files}
import tool.toml.{TomlParser, TomlError}

/** A resolved dependency node. */
case class ResolvedDep(
  name: String,
  spec: BuildSpec,
  specDir: Path,        // directory containing the spec file
  link: DepLink,        // Check or Link
)

/** Fully resolved dependency graph for a build. Deps are in topological order (leaves first). */
case class ResolvedGraph(
  root: BuildSpec,
  rootDir: Path,
  deps: List[ResolvedDep],          // main deps (topological)
  testDeps: List[ResolvedDep],      // test-only deps not in main deps (topological)
):
  def allDeps: List[ResolvedDep] = deps ++ testDeps

object Graph:

  /** Resolve all path dependencies starting from rootSpec at rootDir.
   *  Registry deps are ignored at this stage (Step 2 covers path deps only).
   *  Errors on FFI inconsistency or missing spec files. */
  def resolve(rootSpec: BuildSpec, rootDir: Path): ResolvedGraph =
    // visited: canonicalSpecPath → (ResolvedDep already added, or in progress)
    val visited = collection.mutable.LinkedHashMap.empty[Path, ResolvedDep]
    val inProgress = collection.mutable.Set.empty[Path]
    val order = collection.mutable.ListBuffer.empty[ResolvedDep]
    val heights = collection.mutable.Map.empty[Path, Int]

    val inProgressNames = collection.mutable.ArrayBuffer.empty[String]

    def visit(name: String, spec: BuildSpec, specDir: Path, link: DepLink): Int =
      val canonicalDir = specDir.toRealPath()

      if inProgress.contains(canonicalDir) then
        val cycle = (inProgressNames.dropWhile(_ != name) :+ name).mkString(" -> ")
        throw ToolError(s"circular dependency detected: $cycle")

      heights.get(canonicalDir) match
        case Some(height) => return height
        case None =>

      inProgress += canonicalDir
      inProgressNames += name

      val depHeights = spec.main.dependencies.toList.flatMap: (depName, depSpec) =>
        depSpec.source match
          case DepSource.Path(relPath, specFile) =>
            val depDir  = specDir.resolve(relPath).normalize()
            val tomlFile = specFile.getOrElse("jo.toml")
            val depBuildSpec = loadSpec(depDir, tomlFile)
            Some(visit(depName, depBuildSpec, depDir, depSpec.link))
          case DepSource.Registry(_) =>
            None // skip registry deps in Step 2

      val height = depHeights.maxOption.map(_ + 1).getOrElse(0)
      validateDepth(spec, height)

      inProgress -= canonicalDir
      inProgressNames -= name

      if !visited.contains(canonicalDir) then
        val dep = ResolvedDep(name, spec, specDir, link)
        visited(canonicalDir) = dep
        order += dep
      heights(canonicalDir) = height
      height

    val rootMainHeights = rootSpec.main.dependencies.toList.flatMap: (depName, depSpec) =>
      depSpec.source match
        case DepSource.Path(relPath, specFile) =>
          val depDir = rootDir.resolve(relPath).normalize()
          val tomlFile = specFile.getOrElse("jo.toml")
          val depBuildSpec = loadSpec(depDir, tomlFile)
          Some(visit(depName, depBuildSpec, depDir, depSpec.link))
        case DepSource.Registry(_) => None

    validateDepth(rootSpec, rootMainHeights.maxOption.map(_ + 1).getOrElse(0))

    val mainCount = order.length

    // Visit test-only deps (deduplicated against main deps via visited map)
    for (depName, depSpec) <- rootSpec.test.toList.flatMap(_.dependencies) do
      depSpec.source match
        case DepSource.Path(relPath, specFile) =>
          val depDir = rootDir.resolve(relPath).normalize()
          val tomlFile = specFile.getOrElse("jo.toml")
          val depBuildSpec = loadSpec(depDir, tomlFile)
          visit(depName, depBuildSpec, depDir, depSpec.link)
        case DepSource.Registry(_) => ()

    val allOrder = order.toList
    validateFfi(rootSpec, allOrder)

    ResolvedGraph(rootSpec, rootDir, allOrder.take(mainCount), allOrder.drop(mainCount))

  // ---- Helpers -------------------------------------------------------------

  def loadSpec(dir: Path, tomlFile: String = "jo.toml"): BuildSpec =
    val file = dir.resolve(tomlFile)

    if !Files.exists(file) then
      throw ToolError(s"spec file not found: $file")

    val src = Files.readString(file)

    try BuildSpec.decode(TomlParser.parse(src))
    catch case e: TomlError =>
      throw ToolError(s"in $file: ${e.getMessage}")

  def stemOf(spec: BuildSpec): String = spec.name

  private def validateFfi(root: BuildSpec, deps: List[ResolvedDep]): Unit =
    val rootFfi = root.pkg.flatMap(_.ffi)

    // If root asserts ffi=none, none of its deps may have ffi != none
    rootFfi match
      case Some("none") =>
        for dep <- deps do
          dep.spec.pkg.flatMap(_.ffi) match
            case Some(f) if f != "none" =>
              throw ToolError(
                s"FFI conflict: root asserts ffi=none but dependency '${dep.name}' has ffi=$f"
              )
            case _ => ()
      case _ => ()

  private def validateDepth(spec: BuildSpec, actualDepth: Int): Unit =
    val allowedDepth = spec.depth.getOrElse(if spec.isLib then 0 else 1)
    if actualDepth > allowedDepth then
      throw ToolError(
        s"dependency depth exceeded for '${spec.name}': actual $actualDepth, allowed $allowedDepth"
      )

/** Build tool error (user-facing, no stack trace needed). */
case class ToolError(message: String) extends Exception(message)
