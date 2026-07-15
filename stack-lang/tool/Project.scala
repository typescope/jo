package tool

import java.nio.file.{Files, Path}
import scala.collection.mutable
import tool.toml.{TomlError, TomlParser}

/** A source module dependency. `project = None` means same project. */
case class ModuleDep(
  module: ModuleId,
  link: DepLink,
  project: Option[Project],
  sourcePath: Option[String] = None,
)

final class Project private (
  val dir: Path,
  val specPath: Path,
  private val spec: BuildSpec,
  val moduleDeps: Map[ModuleId, List[ModuleDep]],
  val joVersion: Version,
  val joBin: Path,
):
  private val platformByModule: mutable.Map[ModuleId, Platform] = mutable.LinkedHashMap.empty

  def joVersionSpec: VersionSpec = spec.jo

  def pinning: Map[String, Version] = spec.pinning

  def modules: List[ModuleDef] = spec.modules

  def moduleIds: List[ModuleId] = modules.map(_.id)

  def defaultModuleId: ModuleId = spec.defaultModuleId

  def module(id: ModuleId): Option[ModuleSpec] = spec.module(id)

  def requireModule(id: ModuleId): Result[ModuleSpec] =
    module(id) match
      case Some(spec) => Result.Ok(spec)
      case None       => Result.Err(s"module '${id.value}' is not defined in ${LogFormat.path(specPath)}")

  def moduleDepsOf(id: ModuleId): List[ModuleDep] =
    moduleDeps.getOrElse(id, Nil)

  def moduleDepOf(id: ModuleId, depModule: ModuleId, sourcePath: Option[String]): Option[ModuleDep] =
    moduleDepsOf(id).find(dep => dep.module == depModule && dep.sourcePath == sourcePath)

  def defaultDepth(id: ModuleId): Int =
    module(id) match
      case Some(m) =>
        m.kind match
          case ModuleKind.Lib => 0
          case ModuleKind.App => 1
      case None =>
        1

  def depthOf(id: ModuleId): Int =
    module(id).flatMap(_.depth).getOrElse(defaultDepth(id))

  def pkg(id: ModuleId): Option[PackageSpec] =
    module(id).flatMap(_.pkg)

  def doc: Option[DocSpec] = spec.doc

  def commands: Map[String, String] = spec.commands

  def declaredPlatform(id: ModuleId): Platform =
    module(id).flatMap(_.platform).getOrElse(Platform.Pure)

  def platform(id: ModuleId): Platform =
    platformByModule(id)

  /** Root of this module's build output: `<dir>/.build/<module-id>/`. */
  def buildDir(id: ModuleId): Path =
    dir.resolve(s".build/${id.value}")

  /** Versioned module build output root: `<dir>/.build/<module-id>/jo-<major>.<minor>/`. */
  def buildBaseDir(id: ModuleId): Path =
    buildDir(id).resolve(s"jo-${joVersion.major}.${joVersion.minor}")

  /** Module sast output directory. */
  def sastDir(id: ModuleId): Path =
    buildBaseDir(id).resolve("sast")

  def appOutFile(id: ModuleId, target: Target): Path =
    buildBaseDir(id).resolve(s"target/${id.value}${target.ext}")

  def relativeProjectPath(root: Project): String =
    Project.relativeProjectPath(root.dir, dir)

  def moduleLabel(root: Project, module: ModuleId): String =
    Project.moduleLabel(root.dir, dir, module)

object Project:
  def moduleLabelFromSpec(rootSpecPath: Path, specPath: Path, module: ModuleId): String =
    moduleLabel(rootSpecPath.getParent, specPath.getParent, module)

  private[tool] def moduleLabel(rootDir: Path, projectDir: Path, module: ModuleId): String =
    val projectPath = relativeProjectPath(rootDir, projectDir)
    if projectPath == "." then s"[${module.value}]"
    else s"$projectPath [${module.value}]"

  private[tool] def relativeProjectPath(rootDir: Path, projectDir: Path): String =
    val root = rootDir.toAbsolutePath.normalize()
    val project = projectDir.toAbsolutePath.normalize()
    if root == project then "."
    else
      try root.relativize(project).toString
      catch case _: IllegalArgumentException => project.toString

  private[tool] def validateModuleAcyclic(root: Project, roots: List[ModuleId]): Result[Unit] =
    val visited = mutable.Set.empty[(Path, ModuleId)]
    val stack = mutable.ArrayBuffer.empty[(Project, ModuleId)]

    def walk(project: Project, module: ModuleId): Result[Unit] =
      val key = project.specPath -> module
      val cycleStart = stack.indexWhere(sameModule(_, project, module))
      if cycleStart >= 0 then
        return Result.Err(formatModuleCycle(root, stack.drop(cycleStart).toList :+ ((project, module))))

      if visited.contains(key) then
        Result.unit
      else
        stack += ((project, module))
        val result = project.moduleDepsOf(module).foldLeft(Result.unit): (acc, dep) =>
          acc.flatMap: _ =>
            walk(dep.project.getOrElse(project), dep.module)
        stack.remove(stack.length - 1)
        result.map: _ =>
          visited += key
          ()

    roots.foldLeft(Result.unit): (acc, module) =>
      acc.flatMap(_ => walk(root, module))

  private[tool] def formatModuleCycle(root: Project, cycle: List[(Project, ModuleId)]): String =
    val path = cycle.map((project, module) => project.moduleLabel(root, module)).mkString(" -> ")
    s"circular module dependency detected: $path"

  private def sameModule(entry: (Project, ModuleId), project: Project, module: ModuleId): Boolean =
    entry._1.specPath == project.specPath && entry._2 == module

  def load(specPath: Path): Result[Project] =
    load(specPath, JoResolver.resolve, JoResolver.resolveExact)

  def load(specPath: Path, resolveJo: VersionSpec => Result[(Version, Path)]): Result[Project] =
    load(specPath, resolveJo, version => resolveJo(VersionSpec(Version(version.major, version.minor, 0))).map(_._2))

  def load(
    specPath: Path,
    resolveJo: VersionSpec => Result[(Version, Path)],
    resolveExactJo: Version => Result[Path],
  ): Result[Project] =
    val resolved = mutable.Map.empty[Path, Project]
    val inProgress = mutable.Set.empty[Path]
    val stack = mutable.ArrayBuffer.empty[Path]

    def loadAt(path: Path, inheritedCompiler: Option[(Version, Path)] = None): Result[Project] =
      val canonicalSpecPath = path.toAbsolutePath.toRealPath()
      val specDir = canonicalSpecPath.getParent

      resolved.get(canonicalSpecPath) match
        case Some(project) => return Result.Ok(project)
        case None =>

      if inProgress.contains(canonicalSpecPath) then
        val cycle = (stack.dropWhile(_ != canonicalSpecPath) :+ canonicalSpecPath)
          .map(p => LogFormat.path(p))
          .mkString(" -> ")
        return Result.Err(s"circular project dependency detected: $cycle")

      inProgress += canonicalSpecPath
      stack += canonicalSpecPath

      val result =
        loadSpec(specDir, canonicalSpecPath.getFileName.toString).flatMap: spec =>
          val compiler =
            inheritedCompiler match
              case Some((version, joBin)) =>
                if spec.jo.contains(version) then
                  Result.Ok(version -> joBin)
                else
                  Result.Err(
                    s"source project ${LogFormat.path(canonicalSpecPath)} requires Jo ${spec.jo.show}, but the root project selected Jo $version"
                  )

              case None =>
                resolveCompiler(canonicalSpecPath, spec.jo, resolveJo, resolveExactJo)

          compiler.flatMap: (joVersion, joBin) =>
            resolveModuleDeps(specDir, spec, depPath => loadAt(depPath, Some(joVersion -> joBin))).flatMap: deps =>
              val project = Project(specDir, canonicalSpecPath, spec, deps, joVersion, joBin)
              populatePlatformCache(project).map: _ =>
                resolved(canonicalSpecPath) = project
                project

      inProgress -= canonicalSpecPath
      if stack.nonEmpty then stack.remove(stack.length - 1)

      result

    loadAt(specPath)

  private def resolveCompiler(
    specPath: Path,
    constraint: VersionSpec,
    resolveJo: VersionSpec => Result[(Version, Path)],
    resolveExactJo: Version => Result[Path],
  ): Result[(Version, Path)] =
    LockFile.load(LockFile.pathForSpec(specPath)).flatMap:
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

  private def resolveModuleDeps(
    specDir: Path,
    spec: BuildSpec,
    loadAt: Path => Result[Project],
  ): Result[Map[ModuleId, List[ModuleDep]]] =
    spec.modules.foldLeft(Result.Ok(Map.empty[ModuleId, List[ModuleDep]]): Result[Map[ModuleId, List[ModuleDep]]]): (acc, moduleDef) =>
      acc.flatMap: byModule =>
        val deps = mutable.ListBuffer.empty[ModuleDep]
        val module = moduleDef.id
        val result = moduleDef.spec.dependencies.foldLeft(Result.unit): (depAcc, depSpec) =>
          depAcc.flatMap: _ =>
            depSpec.source match
              case DepSource.Module(depModule, None) =>
                if spec.module(depModule).isEmpty then
                  Result.Err(s"module '${module.value}' depends on undefined module '${depModule.value}'")
                else
                  deps += ModuleDep(depModule, depSpec.link, None, None)
                  Result.unit

              case DepSource.Module(depModule, Some(relPath)) =>
                val depDir = specDir.resolve(relPath).normalize().toRealPath()
                val depSpecPath = depDir.resolve("jo.toml")
                if !Files.exists(depSpecPath) then
                  Result.Err(s"module dependency '${depModule.value}' not found: $depSpecPath")
                else
                  loadAt(depSpecPath).flatMap: depProject =>
                    depProject.module(depModule) match
                      case Some(_) =>
                        deps += ModuleDep(depModule, depSpec.link, Some(depProject), Some(relPath))
                        Result.unit
                      case None =>
                        Result.Err(s"module dependency '${depModule.value}' is not defined in ${LogFormat.path(depProject.specPath)}")

              case DepSource.Registry(_, _) =>
                Result.unit

        result.map(_ => byModule + (module -> deps.toList))

  def loadSpec(dir: Path, tomlFile: String = "jo.toml"): Result[BuildSpec] =
    val file = dir.resolve(tomlFile)

    if !Files.exists(file) then
      return Result.Err(s"spec file not found: $file")

    val src = Files.readString(file)

    try Result.Ok(BuildSpec.decode(TomlParser.parse(src)))
    catch case e: TomlError =>
      Result.Err(s"in $file: ${e.getMessage}")

  private def populatePlatformCache(project: Project): Result[Unit] =
    val memo = project.platformByModule
    val stack = mutable.ArrayBuffer.empty[(Project, ModuleId)]

    def compute(module: ModuleId): Result[Platform] =
      memo.get(module) match
        case Some(platform) =>
          Result.Ok(platform)

        case None =>
          val cycleStart = stack.indexWhere(sameModule(_, project, module))
          if cycleStart >= 0 then
            Result.Err(formatModuleCycle(project, stack.drop(cycleStart).toList :+ ((project, module))))
          else
            stack += ((project, module))
            val contributors = mutable.LinkedHashMap.empty[String, Platform]

            val own = project.declaredPlatform(module)
            if own != Platform.Pure then
              contributors(project.moduleLabel(project, module)) = own

            val result = project.moduleDepsOf(module).foldLeft(Result.unit): (acc, dep) =>
              acc.flatMap: _ =>
                val depProject = dep.project.getOrElse(project)
                val depPlatform =
                  dep.project match
                    case Some(externalProject) => Result.Ok(externalProject.platform(dep.module))
                    case None                  => compute(dep.module)

                depPlatform.map: platform =>
                  if platform != Platform.Pure then
                    contributors(depProject.moduleLabel(project, dep.module)) = platform

            val platformResult = result.flatMap: _ =>
              val distinct = contributors.values.toList.distinct
              if distinct.length > 1 then
                val summary = contributors.map((n, p) => s"'$n' (${p.value})").mkString(", ")
                Result.Err(s"platform conflict in module '${module.value}': source dependencies require different platforms: $summary")
              else
                Result.Ok(distinct.headOption.getOrElse(Platform.Pure))

            stack.remove(stack.length - 1)

            platformResult.map: platform =>
              memo(module) = platform
              platform

    project.moduleIds.foldLeft(Result.unit): (acc, module) =>
      acc.flatMap(_ => compute(module).map(_ => ()))
