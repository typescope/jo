package tool

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object Release:
  def buildPackage(args: Array[String])(using Logger, PackageProvider): Unit =
    buildPackage(args): constraint =>
      JoResolver.resolve(constraint) match
        case Result.Ok(v)    => v
        case Result.Err(msg) => throw ToolError(msg)

  def buildPackage(args: Array[String])(resolveJo: VersionSpec => (Version, Path))(using Logger, PackageProvider): Unit =
    val specFile = Build.parseSpecFile(args)
    val specPath = Path.of(specFile).toAbsolutePath
    validatePackageSpec(specPath)
    val project = Project.load(specPath)
    val specDir = project.dir

    if !project.isLib then die("'jo package' requires a library build ([package] section)")

    val (plans, joBin) = Build.makePlanResult(specFile, List(ModuleKind.Main))(constraint => Result.Ok(resolveJo(constraint))) match
      case Result.Ok(value) => value
      case Result.Err(msg)  => throw ToolError(msg)

    Runner.run(plans.main, joBin) match
      case Result.Err(msg) =>
        Logger.error(msg)
        sys.exit(1)

      case _ =>

    val version = project.pkg.get.version
    val rootBase = specDir.resolve(s".build/${project.name}")
    val joVersion = resolveJo(project.jo)._1
    val sastDir = rootBase.resolve(Planner.joLabel(joVersion)).resolve("sast")
    val releaseDir = rootBase.resolve("release")
    val archiveName = s"${project.name}-v$version.joy"
    val archivePath = releaseDir.resolve(archiveName)
    val archiveDigestPath = releaseDir.resolve(s"$archiveName.sha512")
    val sourcesName = s"${project.name}-v$version-sources.zip"
    val sourcesPath = releaseDir.resolve(sourcesName)
    val sourcesDigestPath = releaseDir.resolve(s"$sourcesName.sha512")

    val tempDir = Files.createTempDirectory("jo-release-")

    try
      val stageDir = tempDir.resolve("stage")
      val sourceStageDir = tempDir.resolve("sources")
      Files.createDirectories(stageDir)
      Files.createDirectories(sourceStageDir)
      stageRelease(project, sastDir, stageDir)
      stageSources(project, sourceStageDir)
      JoyArchive.pack(stageDir, archivePath)
      JoyArchive.pack(sourceStageDir, sourcesPath)
      val archiveSha = Digest.sha512Hex(archivePath)
      val sourceSha = Digest.sha512Hex(sourcesPath)
      Files.writeString(archiveDigestPath, s"$archiveSha  $archiveName\n")
      Files.writeString(sourcesDigestPath, s"$sourceSha  $sourcesName\n")

    finally deleteDir(tempDir)

  private def stageRelease(project: Project, sastDir: Path, stageDir: Path): Unit =
    if !Files.isDirectory(sastDir) then
      throw ToolError(s"sast output not found: $sastDir")

    val sastFiles = Files.walk(sastDir).iterator.asScala
      .filter(Files.isRegularFile(_))
      .filter(_.getFileName.toString.endsWith(".sast"))
      .toList
      .sortBy(_.toString)

    if sastFiles.isEmpty then
      throw ToolError(s"no .sast files found in $sastDir")

    val namespaceDirs = sastFiles.map(f => sastDir.relativize(f).getParent).distinct

    if namespaceDirs.size != 1 then
      throw ToolError("library release must contain exactly one namespace")

    val namespacePath = namespaceDirs.head
    val namespace = namespacePath.iterator.asScala.map(_.toString).mkString(".")
    val ffi = project.ffi.getOrElse("none")
    val dependencies = project.main.dependencies.toSeq.sortBy(_._1).map:
      case (name, DepSpec(DepSource.Registry(constraint), _)) =>
        name -> constraint
      case (name, DepSpec(DepSource.Path(_, _), _)) =>
        throw ToolError(
          s"'jo package' does not support local path dependency '$name'; replace it with a publishable package dependency"
        )
    .toMap

    val meta = PackageMeta(
      namespace,
      project.name,
      project.pkg.get.version,
      ffi,
      project.pkg.flatMap(_.description),
      project.pkg.map(_.authors).getOrElse(Nil),
      project.pkg.flatMap(_.homepage),
      project.pkg.flatMap(_.license),
      project.pkg.map(_.keywords).getOrElse(Nil),
      dependencies,
    )

    Files.writeString(stageDir.resolve("meta.toml"), renderMeta(meta))

    for file <- sastFiles do
      val rel = sastDir.relativize(file)
      val target = stageDir.resolve(rel.toString)
      Files.createDirectories(target.getParent)
      Files.copy(file, target)

  private def stageSources(project: Project, stageDir: Path): Unit =
    val sources = SourceGlob.expand(project.main.src, project.dir)

    if sources.isEmpty then
      throw ToolError(s"no source files found for package '${project.name}'")

    for file <- sources do
      val rel = project.dir.relativize(file)
      val target = stageDir.resolve(rel.toString)
      Files.createDirectories(target.getParent)
      Files.copy(file, target)

  private def renderMeta(meta: PackageMeta): String =
    val sb = new StringBuilder
    sb.append(s"""namespace = "${meta.namespace}"\n""")
    sb.append(s"""name = "${meta.name}"\n""")
    sb.append(s"""version = "${meta.version}"\n""")
    sb.append(s"""ffi = "${meta.ffi}"\n""")
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

  private def validatePackageSpec(specPath: Path): Unit =
    val project = Project.load(specPath)

    project.main.dependencies.foreach:
      case (name, DepSpec(DepSource.Path(_, _), _)) =>
        throw ToolError(
          s"'jo package' does not support local path dependency '$name'; replace it with a publishable package dependency"
        )

      case _ =>

  private def die(msg: String): Nothing =
    System.err.println(s"error: $msg")
    sys.exit(1)
