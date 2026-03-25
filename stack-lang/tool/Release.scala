package tool

import java.nio.file.{Files, Path}
import java.security.MessageDigest
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
    val specDir = specPath.getParent
    val spec = Graph.loadSpec(specDir, specPath.getFileName.toString)

    if !spec.isLib then die("'jo package' requires a library build ([package] section)")
    validatePackageDeps(spec)

    val plan = Build.makePlan(specFile)(resolveJo)

    Runner.run(plan) match
      case Result.Err(msg) =>
        Logger.error(msg)
        sys.exit(1)

      case _ =>

    spec.test.foreach: _ =>
      Runner.test(plan) match
        case Result.Err(msg) =>
          Logger.error(msg)
          sys.exit(1)

        case _ =>

    val version = spec.pkg.get.version
    val rootBase = specDir.resolve(s".build/${spec.name}")
    val joVersion = resolveJo(spec.jo)._1
    val sastDir = rootBase.resolve(Planner.joLabel(joVersion)).resolve("sast")
    val releaseDir = rootBase.resolve("release")
    val archiveName = s"${spec.name}-v$version.joy"
    val archivePath = releaseDir.resolve(archiveName)
    val archiveDigestPath = releaseDir.resolve(s"$archiveName.sha512")
    val sourcesName = s"${spec.name}-v$version-sources.zip"
    val sourcesPath = releaseDir.resolve(sourcesName)
    val sourcesDigestPath = releaseDir.resolve(s"$sourcesName.sha512")

    val tempDir = Files.createTempDirectory("jo-release-")

    try
      val stageDir = tempDir.resolve("stage")
      val sourceStageDir = tempDir.resolve("sources")
      Files.createDirectories(stageDir)
      Files.createDirectories(sourceStageDir)
      stageRelease(spec, sastDir, stageDir)
      stageSources(spec, specDir, sourceStageDir)
      JoyArchive.pack(stageDir, archivePath)
      JoyArchive.pack(sourceStageDir, sourcesPath)
      val archiveSha = sha512Hex(archivePath)
      val sourceSha = sha512Hex(sourcesPath)
      Files.writeString(archiveDigestPath, s"$archiveSha  $archiveName\n")
      Files.writeString(sourcesDigestPath, s"$sourceSha  $sourcesName\n")

    finally deleteDir(tempDir)

  private def stageRelease(spec: BuildSpec, sastDir: Path, stageDir: Path): Unit =
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
    val ffi = spec.pkg.flatMap(_.ffi).getOrElse("none")
    val dependencies = spec.main.dependencies.toSeq.sortBy(_._1).map:
      case (name, DepSpec(DepSource.Registry(constraint), _)) =>
        name -> constraint
      case (name, DepSpec(DepSource.Path(_, _), _)) =>
        throw packagePathDepError(name)
    .toMap

    val meta = PackageMeta(
      namespace,
      spec.name,
      spec.pkg.get.version,
      ffi,
      spec.pkg.flatMap(_.description),
      spec.pkg.map(_.authors).getOrElse(Nil),
      spec.pkg.flatMap(_.homepage),
      spec.pkg.flatMap(_.license),
      spec.pkg.map(_.keywords).getOrElse(Nil),
      dependencies,
    )

    Files.writeString(stageDir.resolve("meta.toml"), renderMeta(meta))

    for file <- sastFiles do
      val rel = sastDir.relativize(file)
      val target = stageDir.resolve(rel.toString)
      Files.createDirectories(target.getParent)
      Files.copy(file, target)

  private def stageSources(spec: BuildSpec, specDir: Path, stageDir: Path): Unit =
    val sources = SourceGlob.expand(spec.main.src, specDir)

    if sources.isEmpty then
      throw ToolError(s"no source files found for package '${spec.name}'")

    for file <- sources do
      val rel = specDir.relativize(file)
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

  private def validatePackageDeps(spec: BuildSpec): Unit =
    spec.main.dependencies.foreach:
      case (name, DepSpec(DepSource.Path(_, _), _)) =>
        throw packagePathDepError(name)

      case _ =>

  private def packagePathDepError(name: String): ToolError =
    ToolError(
      s"'jo package' does not support local path dependency '$name'; replace it with a publishable package dependency"
    )

  private def sha512Hex(path: Path): String =
    val md = MessageDigest.getInstance("SHA-512")
    val in = Files.newInputStream(path)

    try
      val buf = new Array[Byte](8192)
      var n = in.read(buf)

      while n >= 0 do
        if n > 0 then md.update(buf, 0, n)
        n = in.read(buf)

    finally in.close()

    md.digest().map("%02x".format(_)).mkString

  private def deleteDir(dir: Path): Unit =
    if Files.exists(dir) then
      Files.walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.delete)

  private def die(msg: String): Nothing =
    System.err.println(s"error: $msg")
    sys.exit(1)
