package tool

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths}
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
  def getInstalledVersions(): List[Version]
  def activeVersion(): Option[Version]
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
    else
      deleteDir(dir)
      Result.Ok(())

  def use(version: Version): Result[Unit] =
    val versionBin = installBase.resolve(version.toString).resolve("bin").resolve("jo")
    if !Files.exists(versionBin) then
      Result.Err(s"Jo $version is not installed — run 'jo versions install $version' first")
    else
      Files.createDirectories(activeBin.getParent)
      Files.writeString(activeBin,
        s"""#!/bin/bash
           |exec "$versionBin" "$$@"
           |""".stripMargin)
      activeBin.toFile.setExecutable(true)
      Result.Ok(())

  def getInstalledVersions(): List[Version] =
    if !Files.exists(installBase) then return Nil
    Files.list(installBase).iterator.asScala
      .filter(Files.isDirectory(_))
      .flatMap(p => Version.parse(p.getFileName.toString))
      .toList.sorted.reverse

  def activeVersion(): Option[Version] =
    if !Files.exists(activeBin) then return None
    val content = Files.readString(activeBin)
    val pattern = """compilers/([^/]+)/bin/jo""".r
    pattern.findFirstMatchIn(content).flatMap(m => Version.parse(m.group(1)))

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
            writeLauncher(installDir)
            Result.Ok(())
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

  private def writeLauncher(installDir: Path): Unit =
    val bin = installDir.resolve("bin")
    Files.createDirectories(bin)
    val launcher = bin.resolve("jo")
    Files.writeString(launcher,
      s"""#!/bin/sh
         |BIN_DIR="$$(cd "$$(dirname "$$0")" && pwd)"
         |JO_HOME="$$(cd "$$BIN_DIR/.." && pwd)"
         |export JO_HOME
         |exec java -jar "$$JO_HOME/jo.jar" "$$@"
         |""".stripMargin)
    launcher.toFile.setExecutable(true)

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

end HttpInstaller

object HttpInstaller:
  def default(): HttpInstaller =
    val home = Paths.get(System.getProperty("user.home"))
    HttpInstaller(
      versionsUrl = "https://jo-lang.org/versions.jsonl",
      installBase = home.resolve(".jo/compilers"),
      activeBin   = home.resolve(".local/bin/jo"),
    )

/** Test installer backed by a YAML file listing available versions.
  *
  * Installed/active state is stored in plain files under `stateDir` so it
  * survives across multiple `runJoCmd` calls within the same test scenario:
  * {{{
  *   compilers/<version>/   one directory per installed version
  *   active                 file containing the active version string
  * }}}
  */
class MockInstaller(available: List[Version], stateDir: Path) extends Installer:

  private def compilersDir = stateDir.resolve("compilers")
  private def activeFile   = stateDir.resolve("active")

  def getVersions(): Result[List[Version]] =
    Result.Ok(available.sorted.reverse)

  def install(version: Version): Result[Unit] =
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
    val dir = compilersDir.resolve(version.toString)
    if !Files.exists(dir) then
      Result.Err(s"Jo $version is not installed")
    else
      Files.delete(dir)
      if activeVersion().contains(version) then Files.deleteIfExists(activeFile)
      Result.Ok(())

  def use(version: Version): Result[Unit] =
    val dir = compilersDir.resolve(version.toString)
    if !Files.exists(dir) then
      Result.Err(s"Jo $version is not installed — run 'jo versions install $version' first")
    else
      Files.writeString(activeFile, version.toString)
      Result.Ok(())

  def getInstalledVersions(): List[Version] =
    if !Files.exists(compilersDir) then return Nil
    Files.list(compilersDir).iterator.asScala
      .filter(Files.isDirectory(_))
      .flatMap(p => Version.parse(p.getFileName.toString))
      .toList.sorted.reverse

  def activeVersion(): Option[Version] =
    if !Files.exists(activeFile) then None
    else Version.parse(Files.readString(activeFile).trim)

end MockInstaller

object MockInstaller:
  /** Read available versions from a YAML file in the format:
    * {{{
    *   versions:
    *     - 0.10.0
    *     - 0.9.0
    * }}}
    * State is stored in the same directory as the YAML file.
    */
  def fromYaml(versionsFile: Path): MockInstaller =
    val versions = Files.readString(versionsFile).linesIterator
      .map(_.trim.stripPrefix("-").trim)
      .filter(l => l.nonEmpty && l != "versions:")
      .flatMap(Version.parse)
      .toList
    MockInstaller(versions, versionsFile.getParent)
