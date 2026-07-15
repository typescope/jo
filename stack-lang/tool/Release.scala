package tool

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

object Release:
  def buildPackage(project: Project, module: ModuleId)(using Logger, PackageProvider): Result[Unit] =
    project.requireModule(module).flatMap: spec =>
      spec.pkg match
        case None =>
          Result.Err(s"'jo package' requires [module.${module.value}.package]")

        case Some(pkg) =>
          Logger.info(s"[package] ${project.moduleLabel(project, module)}\n")
          validatePackageDependencies(project, module).flatMap: dependencies =>
            Build.makePlanResult(project, List(module)).flatMap: plans =>
              Runner.run(plans.modules.head).flatMap: _ =>
                val version = pkg.version
                val sastDir = project.sastDir(module)
                val releaseDir = project.buildDir(module).resolve("release")
                val archiveName = s"${pkg.name}-v$version.joy"
                val archivePath = releaseDir.resolve(archiveName)
                val archiveDigestPath = releaseDir.resolve(s"$archiveName.sha512")
                val sourcesName = s"${pkg.name}-v$version-sources.zip"
                val sourcesPath = releaseDir.resolve(sourcesName)
                val sourcesDigestPath = releaseDir.resolve(s"$sourcesName.sha512")
                val tempDir = Files.createTempDirectory("jo-release-")

                try
                  val stageDir = tempDir.resolve("stage")
                  val sourceStageDir = tempDir.resolve("sources")
                  Files.createDirectories(stageDir)
                  Files.createDirectories(sourceStageDir)
                  stageRelease(project, module, spec, dependencies, sastDir, stageDir).flatMap: _ =>
                    stageSources(project, module, spec, sourceStageDir).map: _ =>
                      JoyArchive.pack(stageDir, archivePath)
                      JoyArchive.pack(sourceStageDir, sourcesPath)
                      val archiveSha = Digest.sha512Hex(archivePath)
                      val sourceSha = Digest.sha512Hex(sourcesPath)
                      Files.writeString(archiveDigestPath, s"$archiveSha  $archiveName\n")
                      Files.writeString(sourcesDigestPath, s"$sourceSha  $sourcesName\n")
                      Logger.info(s"[artifact] ${LogFormat.path(archivePath)}\n")
                      Logger.info(s"[artifact] ${LogFormat.path(sourcesPath)}\n")
                finally deleteDir(tempDir)

  private def validatePackageDependencies(project: Project, module: ModuleId)(using PackageProvider): Result[Map[String, VersionSpec]] =
    packageDependencies(project, module).flatMap: dependencies =>
      DependencyResolver.resolveProject(project, List(module)).flatMap: resolved =>
        resolved.packages.find(_.meta.platform != Platform.Pure) match
          case Some(pkg) =>
            Result.Err(
              s"'jo package' only allows published packages to depend on pure packages; dependency '${pkg.name}' requires platform=${pkg.meta.platform.value}"
            )
          case None =>
            Result.Ok(dependencies)

  private def packageDependencies(project: Project, module: ModuleId): Result[Map[String, VersionSpec]] =
    project.requireModule(module).flatMap: spec =>
      val dependencies = mutable.LinkedHashMap.empty[String, VersionSpec]

      def addDependency(name: String, constraint: VersionSpec): Result[Unit] =
        dependencies.get(name) match
          case Some(existing) if existing != constraint =>
            Result.Err(
              s"package dependency '$name' has conflicting constraints '${existing.show}' and '${constraint.show}' in module '${module.value}'"
            )
          case _ =>
            dependencies(name) = constraint
            Result.unit

      spec.dependencies.foldLeft(Result.unit): (acc, depSpec) =>
        acc.flatMap: _ =>
          if depSpec.link == DepLink.Link then
            Result.unit
          else
            depSpec.source match
              case DepSource.Registry(name, constraint) =>
                addDependency(name, constraint)

              case DepSource.Module(depModule, sourcePath) =>
                project.moduleDepOf(module, depModule, sourcePath) match
                  case None =>
                    Result.Err(s"module dependency '${depModule.value}' was not resolved for module '${module.value}'")

                  case Some(dep) =>
                    val depProject = dep.project.getOrElse(project)
                    depProject.pkg(depModule) match
                      case None =>
                        Result.Err(
                          s"module '${module.value}' depends on source module '${depModule.value}', but that module has no [module.${depModule.value}.package]"
                        )
                      case Some(pkg) =>
                        versionConstraint(pkg.version, depModule).flatMap: constraint =>
                          addDependency(pkg.name, constraint)
      .map(_ => dependencies.toMap)

  private def versionConstraint(version: String, module: ModuleId): Result[VersionSpec] =
    Version.parse(version) match
      case Some(parsed) => Result.Ok(VersionSpec(Version(parsed.major, parsed.minor, 0)))
      case None =>
        Result.Err(s"invalid package version '$version' for module '${module.value}'")

  private def stageRelease(
    project: Project,
    module: ModuleId,
    spec: ModuleSpec,
    dependencies: Map[String, VersionSpec],
    sastDir: Path,
    stageDir: Path,
  ): Result[Unit] =
    if !Files.isDirectory(sastDir) then
      return Result.Err(s"sast output not found: $sastDir")

    val sastFiles = Files.walk(sastDir).iterator.asScala
      .filter(Files.isRegularFile(_))
      .filter(_.getFileName.toString.endsWith(".sast"))
      .toList
      .sortBy(_.toString)

    if sastFiles.isEmpty then
      return Result.Err(s"no .sast files found in $sastDir")

    val namespaceDirs = sastFiles.map(f => sastDir.relativize(f).getParent).distinct.sortBy(_.toString)

    // All .sast files must share a common root namespace (the shortest path must
    // be a prefix of every other directory, e.g. jo/ is a prefix of jo/mutable/).
    val rootDir = namespaceDirs.head
    if !namespaceDirs.forall(_.startsWith(rootDir)) then
      return Result.Err("all source files must belong to the same root namespace")

    val namespace = rootDir.iterator.asScala.map(_.toString).mkString(".")
    val pkg = spec.pkg.get
    val meta = PackageMeta(
      namespace,
      pkg.name,
      project.joVersionSpec,
      pkg.version,
      project.platform(module),
      pkg.description,
      pkg.authors,
      pkg.homepage,
      pkg.license,
      pkg.keywords,
      dependencies,
    )

    Files.writeString(stageDir.resolve("meta.toml"), renderMeta(meta))

    for file <- sastFiles do
      val rel = sastDir.relativize(file)
      val target = stageDir.resolve(rel.toString)
      Files.createDirectories(target.getParent)
      Files.copy(file, target)
    Result.unit

  private def stageSources(project: Project, module: ModuleId, spec: ModuleSpec, stageDir: Path): Result[Unit] =
    val sources = SourceGlob.expand(spec.src, project.dir, SourceGlob.defaultModuleSrc(module))

    if sources.isEmpty then
      return Result.Err(s"no source files found for package '${spec.pkg.get.name}'")

    for file <- sources do
      val rel = project.dir.relativize(file)
      val target = stageDir.resolve(rel.toString)
      Files.createDirectories(target.getParent)
      Files.copy(file, target)
    Result.unit

  private def renderMeta(meta: PackageMeta): String =
    val sb = new StringBuilder
    sb.append(s"""namespace = "${meta.namespace}"\n""")
    sb.append(s"""name = "${meta.name}"\n""")
    sb.append(s"""jo = "${meta.jo.show}"\n""")
    sb.append(s"""version = "${meta.version}"\n""")
    sb.append(s"""platform = "${meta.platform.value}"\n""")
    meta.description.foreach(d => sb.append(s"""description = "$d"\n"""))
    if meta.authors.nonEmpty then
      sb.append(s"authors = ${renderStrList(meta.authors)}\n")
    meta.homepage.foreach(h => sb.append(s"""homepage = "$h"\n"""))
    meta.license.foreach(l => sb.append(s"""license = "$l"\n"""))
    if meta.keywords.nonEmpty then
      sb.append(s"keywords = ${renderStrList(meta.keywords)}\n")

    if meta.dependencies.nonEmpty then
      sb.append("\n[dependencies]\n")
      for (name, constraint) <- meta.dependencies.toSeq.sortBy(_._1) do
        sb.append(s"""$name = "${constraint.show}"\n""")

    sb.toString

  private def renderStrList(items: List[String]): String =
    items.map(s => "\"" + s + "\"").mkString("[", ", ", "]")
