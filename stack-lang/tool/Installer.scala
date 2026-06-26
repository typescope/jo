package tool

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** One line from `versions.jsonl` — describes a released compiler version. */
case class VersionRecord(version: Version, url: String, sha256url: String, date: String)

object VersionRecord:
  def parse(line: String): Either[String, VersionRecord] =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith("//") then
      return Left("empty or comment line")

    def field(key: String): Either[String, String] =
      val pattern = s""""$key"\\s*:\\s*"([^"]*)"""".r
      pattern.findFirstMatchIn(trimmed) match
        case Some(m) => Right(m.group(1))
        case None    => Left(s"missing field '$key'")

    for
      versionStr <- field("version")
      version    <- Version.parse(versionStr).toRight(s"invalid version '$versionStr'")
      url        <- field("url")
      sha256url  <- field("sha256url")
      date       <- field("date")
    yield VersionRecord(version, url, sha256url, date)

  def parseAll(content: String): Either[String, List[VersionRecord]] =
    val results = content.linesIterator
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("//"))
      .map(parse)
      .toList
    val errors = results.collect { case Left(e) => e }
    if errors.nonEmpty then Left(errors.mkString(", "))
    else Right(results.collect { case Right(r) => r })

/** Manages installed compiler versions. */
trait Installer:
  def getVersions(): Result[List[Version]]
  def install(version: Version): Result[Unit]
  def remove(version: Version): Result[Unit]
  def use(version: Version): Result[Unit]
  def getInstalledVersions(): Result[List[Version]]
  def activeVersion(): Result[Version]
end Installer

/** Downloads and installs compiler versions from the Jo release index over HTTP.
  *
  * Installs to `installBase/<version>/` and writes the active launcher at `activeBin`.
  */
class HttpInstaller(
  versionsUrl: String,
  installBase: Path,
  activeBin:   Path,
) extends Installer:

  private val http = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

  def getVersions(): Result[List[Version]] =
    fetchText(versionsUrl).flatMap: content =>
      VersionRecord.parseAll(content) match
        case Right(records) => Result.Ok(records.map(_.version).sorted.reverse)
        case Left(err)      => Result.Err(s"failed to parse versions: $err")

  def install(version: Version): Result[Unit] =
    fetchText(versionsUrl).flatMap: content =>
      VersionRecord.parseAll(content) match
        case Left(err)      => Result.Err(s"failed to parse versions: $err")
        case Right(records) =>
          records.find(_.version == version) match
            case None      => Result.Err(s"version $version not found")
            case Some(rec) => downloadAndInstall(rec)

  def remove(version: Version): Result[Unit] =
    val dir = installBase.resolve(version.toString)
    if !Files.exists(dir) then Result.Err(s"Jo $version is not installed")
    else activeVersion() match
      case Result.Ok(active) if active == version => Result.Err(s"Jo $version is the active version")
      case _ =>
        deleteDir(dir)
        Result.Ok(())

  def use(version: Version): Result[Unit] =
    val versionBin = installBase.resolve(version.toString).resolve("bin").resolve("jo")
    if !Files.exists(versionBin) then
      Result.Err(s"Jo $version is not installed — run 'jo versions install $version' first")
    else
      try
        Files.createDirectories(activeBin.getParent)
        Files.writeString(activeBin,
          s"""#!/bin/bash
             |exec "$versionBin" "$$@"
             |""".stripMargin)
        activeBin.toFile.setExecutable(true)
        System.err.println(s"  ${Ansi.dim("Launcher:")} ${tilde(activeBin)}")
        val binDir = activeBin.getParent.toString
        val path   = sys.env.getOrElse("PATH", "")
        if !path.split(":").contains(binDir) then
          System.err.println(s"${Ansi.yellow("note:")} add '${tilde(activeBin.getParent)}' to your PATH:")
          System.err.println(s"  ${Ansi.dim("export")} PATH=\"${tilde(activeBin.getParent)}:$$PATH\"")
        Result.Ok(())
      catch case e: Exception => Result.Err(s"could not write launcher at $activeBin: ${e.getMessage}")

  def getInstalledVersions(): Result[List[Version]] =
    try
      if !Files.exists(installBase) then Result.Ok(Nil)
      else Result.Ok:
        Files.list(installBase).iterator.asScala
          .filter(Files.isDirectory(_))
          .flatMap(p => Version.parse(p.getFileName.toString))
          .toList.sorted.reverse
    catch case e: Exception => Result.Err(s"could not read installed versions: ${e.getMessage}")

  def activeVersion(): Result[Version] =
    try
      if !Files.exists(activeBin) then Result.Err("no active version set")
      else
        val content = Files.readString(activeBin)
        val pattern = """compilers/([^/]+)/bin/jo""".r
        pattern.findFirstMatchIn(content).flatMap(m => Version.parse(m.group(1))) match
          case Some(v) => Result.Ok(v)
          case None    => Result.Err("could not parse active version from launcher")
    catch case e: Exception => Result.Err(s"could not read active version: ${e.getMessage}")

  private def downloadAndInstall(rec: VersionRecord): Result[Unit] =
    val installDir = installBase.resolve(rec.version.toString)
    if Files.exists(installDir) then
      return Result.Err(s"Jo ${rec.version} is already installed — run 'jo versions remove ${rec.version}' first")

    val tmp = Files.createTempDirectory("jo-install-")
    try
      val tarball = tmp.resolve(s"jo-${rec.version}.tar.gz")
      for
        _ <- downloadFile(rec.url, tarball)
        _ <- verifySha256(tarball, rec.sha256url)
        _ <- extract(tarball, tmp)
        _ <-
          val extracted = tmp.resolve(s"jo-${rec.version}")
          if !Files.exists(extracted) then
            Result.Err(s"unexpected tarball layout: missing jo-${rec.version}/ directory")
          else
            Files.createDirectories(installBase)
            moveDir(extracted, installDir)
            ensureLauncherExecutable(installDir)
      yield ()
    finally
      deleteDir(tmp)

  private def verifySha256(tarball: Path, sha256url: String): Result[Unit] =
    fetchText(sha256url).flatMap: content =>
      val expected = content.trim.split("\\s+").head
      val actual   = Digest.sha256Hex(tarball)
      if actual == expected then Result.Ok(())
      else Result.Err(s"SHA256 mismatch — expected $expected, got $actual")

  private def extract(tarball: Path, dest: Path): Result[Unit] =
    val proc = ProcessBuilder("tar", "-xzf", tarball.toString, "-C", dest.toString)
      .redirectErrorStream(true)
      .start()
    val output = String(proc.getInputStream.readAllBytes())
    val code   = proc.waitFor()
    if code == 0 then Result.Ok(())
    else Result.Err(s"tar extraction failed (exit $code): $output")

  /** The versioned launcher ships in the tarball at `bin/jo`, so it is the single
   *  source of truth for the layout. Just ensure it is executable rather than
   *  regenerating it (which would hardcode the jar path and drift from the tarball).
   */
  private def ensureLauncherExecutable(installDir: Path): Result[Unit] =
    val launcher = installDir.resolve("bin").resolve("jo")

    if !Files.exists(launcher) then
      Result.Err("unexpected tarball layout: missing bin/jo launcher")
    else
      launcher.toFile.setExecutable(true)
      Result.Ok(())

  private def fetchText(url: String): Result[String] =
    try
      val req = HttpRequest.newBuilder(URI.create(url)).build()
      val res = http.send(req, HttpResponse.BodyHandlers.ofString())
      if res.statusCode() == 200 then Result.Ok(res.body())
      else Result.Err(s"HTTP ${res.statusCode()} fetching $url")
    catch case e: Exception => Result.Err(s"failed to fetch $url: ${e.getMessage}")

  private def downloadFile(url: String, dest: Path): Result[Unit] =
    try
      val req = HttpRequest.newBuilder(URI.create(url)).build()
      val res = http.send(req, HttpResponse.BodyHandlers.ofFile(dest))
      if res.statusCode() == 200 then Result.Ok(())
      else Result.Err(s"HTTP ${res.statusCode()} downloading $url")
    catch case e: Exception => Result.Err(s"failed to download $url: ${e.getMessage}")

  private def moveDir(src: Path, dest: Path): Unit =
    try Files.move(src, dest)
    catch case _: Exception =>
      Files.walk(src).iterator.asScala.foreach: path =>
        val target = dest.resolve(src.relativize(path))
        if Files.isDirectory(path) then Files.createDirectories(target)
        else Files.copy(path, target)
      deleteDir(src)

  private def deleteDir(path: Path): Unit =
    if Files.exists(path) then
      Files.walk(path).iterator.asScala.toList.reverse.foreach(Files.delete)

  private def tilde(path: Path): String =
    val home = System.getProperty("user.home")
    val s    = path.toString
    if s.startsWith(home) then "~" + s.drop(home.length) else s

end HttpInstaller

object HttpInstaller:
  def default(): HttpInstaller =
    HttpInstaller(
      versionsUrl = Config.versionsUrl,
      installBase = Config.compilers,
      activeBin   = Config.activeBin,
    )

/** Test installer backed by a YAML file listing available versions.
  *
  * Installed/active state is stored in plain files under the YAML file's directory
  * so it survives across multiple command calls within the same test scenario:
  * {{{
  *   compilers/<version>/   one directory per installed version
  *   active                 file containing the active version string
  * }}}
  *
  * Optional `fail:` section in the YAML simulates IO failures for specific operations:
  * {{{
  *   fail:
  *     getVersions: network timeout
  *     getInstalled: disk error
  *     activeVersion: corrupt file
  *     install: permission denied
  *     remove: permission denied
  *     use: permission denied
  * }}}
  */
class MockInstaller(available: List[Version], stateDir: Path, fails: Map[String, String] = Map.empty) extends Installer:

  private def compilersDir = stateDir.resolve("compilers")
  private def activeFile   = stateDir.resolve("active")

  def getVersions(): Result[List[Version]] =
    fails.get("getVersions") match
      case Some(err) => Result.Err(err)
      case None      => Result.Ok(available.sorted.reverse)

  def getInstalledVersions(): Result[List[Version]] =
    fails.get("getInstalled") match
      case Some(err) => Result.Err(err)
      case None =>
        if !Files.exists(compilersDir) then Result.Ok(Nil)
        else Result.Ok:
          Files.list(compilersDir).iterator.asScala
            .filter(Files.isDirectory(_))
            .flatMap(p => Version.parse(p.getFileName.toString))
            .toList.sorted.reverse

  def activeVersion(): Result[Version] =
    fails.get("activeVersion") match
      case Some(err) => Result.Err(err)
      case None =>
        if !Files.exists(activeFile) then Result.Err("no active version set")
        else
          Version.parse(Files.readString(activeFile).trim) match
            case Some(v) => Result.Ok(v)
            case None    => Result.Err("could not parse active version")

  def install(version: Version): Result[Unit] =
    fails.get("install") match
      case Some(err) => Result.Err(err)
      case None =>
        if !available.contains(version) then
          Result.Err(s"version $version not found")
        else
          val dir = compilersDir.resolve(version.toString)
          if Files.exists(dir) then
            Result.Err(s"Jo $version is already installed — run 'jo versions remove $version' first")
          else
            Files.createDirectories(dir)
            Result.Ok(())

  def remove(version: Version): Result[Unit] =
    fails.get("remove") match
      case Some(err) => Result.Err(err)
      case None =>
        val dir = compilersDir.resolve(version.toString)
        if !Files.exists(dir) then
          Result.Err(s"Jo $version is not installed")
        else activeVersion() match
          case Result.Ok(active) if active == version => Result.Err(s"Jo $version is the active version")
          case _ =>
            Files.delete(dir)
            Result.Ok(())

  def use(version: Version): Result[Unit] =
    fails.get("use") match
      case Some(err) => Result.Err(err)
      case None =>
        val dir = compilersDir.resolve(version.toString)
        if !Files.exists(dir) then
          Result.Err(s"Jo $version is not installed — run 'jo versions install $version' first")
        else
          Files.writeString(activeFile, version.toString)
          Result.Ok(())

end MockInstaller

object MockInstaller:
  /** Read available versions and optional fail config from a YAML file:
    * {{{
    *   versions:
    *     - 0.10.0
    *     - 0.9.0
    *
    *   fail:
    *     getVersions: network timeout
    * }}}
    * State is stored in the same directory as the YAML file.
    */
  def fromYaml(versionsFile: Path): MockInstaller =
    val content  = Files.readString(versionsFile)
    val versions = scala.collection.mutable.ListBuffer.empty[Version]
    val fails    = scala.collection.mutable.Map.empty[String, String]
    var section  = ""

    for line <- content.linesIterator do
      val trimmed = line.trim
      if trimmed == "versions:" then section = "versions"
      else if trimmed == "fail:" then section = "fail"
      else if trimmed.isEmpty then ()
      else if section == "versions" && trimmed.startsWith("- ") then
        Version.parse(trimmed.drop(2).trim).foreach(versions += _)
      else if section == "fail" && trimmed.contains(":") then
        val i = trimmed.indexOf(':')
        val value = trimmed.drop(i + 1).trim
        if value.nonEmpty then fails(trimmed.take(i).trim) = value

    MockInstaller(versions.toList, versionsFile.getParent, fails.toMap)
