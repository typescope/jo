package tool

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

object Release:
  def buildRelease(args: Array[String])(using Logger): Unit =
    buildRelease(args): constraint =>
      JoResolver.resolve(constraint) match
        case Result.Ok(v)    => v
        case Result.Err(msg) => throw ToolError(msg)

  def buildRelease(args: Array[String])(resolveJo: String => (Version, Path))(using Logger): Unit =
    val (specFile, _) = Build.parseArgs(args)
    val specPath = Path.of(specFile).toAbsolutePath
    val specDir = specPath.getParent
    val spec = Graph.loadSpec(specDir, specPath.getFileName.toString)
    val plan = Build.makePlan(specFile)(resolveJo)

    if !spec.isLib then die("'jo build-release' requires a library build ([package] section)")

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
    val digestPath = releaseDir.resolve(s"$archiveName.sha512")

    val tempDir = Files.createTempDirectory("jo-release-")

    try
      val stageDir = tempDir.resolve("stage")
      Files.createDirectories(stageDir)
      stageRelease(spec, sastDir, stageDir)
      JoyArchive.pack(stageDir, archivePath)
      val sha = sha512Hex(archivePath)
      Files.writeString(digestPath, s"$sha  $archiveName\n")

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
    val meta = PackageMeta(namespace, spec.name, spec.pkg.get.version, ffi)

    Files.writeString(stageDir.resolve("meta.toml"), renderMeta(meta))

    for file <- sastFiles do
      val rel = sastDir.relativize(file)
      val target = stageDir.resolve(rel.toString)
      Files.createDirectories(target.getParent)
      Files.copy(file, target)

  private def renderMeta(meta: PackageMeta): String =
    s"""namespace = "${meta.namespace}"
       |name = "${meta.name}"
       |version = "${meta.version}"
       |ffi = "${meta.ffi}"
       |""".stripMargin

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
