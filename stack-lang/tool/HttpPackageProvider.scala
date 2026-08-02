package tool

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.util.zip.ZipFile

import tool.toml.TomlParser

/** A single parsed line from a package's JSONL release index.
 *
 *  The wire key for the platform is `runtime`: the registry daemon writes it, and the
 *  build spec's `platform` rename does not reach data Jo does not produce.
 */
private case class ReleaseRecord(
  version: Version,
  url: String,
  sha512: String,
  jo: VersionSpec,
  platform: String,
  deps: Map[String, VersionSpec],
  yanked: Boolean,
)

/** Fetches packages from the Jo registry and caches artifacts locally.
 *
 *  Resolution uses the JSONL release index at:
 *    <registryUrl>/<package-name>.jsonl
 *
 *  The index is cached on disk at:
 *    ~/.jo/cache/index/<name>.jsonl
 *
 *  with a TTL of [[indexTtlMs]]. An in-memory cache prevents redundant
 *  disk reads within one process run.
 *
 *  Artifacts are cached at:
 *    <cacheRoot>/<name>/<version>/<name>-v<version>.joy
 *
 *  sha512 is verified against the registry record immediately after download.
 */
case class HttpPackageProvider(
  registryUrl: String,
  cacheHome: Path,
) extends PackageProvider:
  private val http = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()
  private val memCache = collection.mutable.Map.empty[String, List[ReleaseRecord]]
  private val indexTtlMs = 5 * 60 * 1000L  // 5 minutes

  def versions(name: String): Result[List[Version]] =
    records(name).map(_.filterNot(_.yanked).map(_.version).sorted)

  def dependencyInfo(name: String, version: Version): Result[PackageDependencyInfo] =
    recordFor(name, version).flatMap: rec =>
      Platform.parse(rec.platform) match
        case Some(platform) =>
          Result.Ok(PackageDependencyInfo(rec.jo, platform, rec.deps))
        case None =>
          Result.Err(s"invalid runtime value '${rec.platform}' in $name.jsonl")

  def meta(name: String, version: Version): Result[PackageMeta] =
    path(name, version).flatMap: archive =>
      val zip = ZipFile(archive.toFile)
      try
        val entry = zip.getEntry("meta.toml")
        if entry == null then Result.Err(s"meta.toml not found in $archive")
        else
          val src = String(zip.getInputStream(entry).readAllBytes(), "UTF-8")
          try Result.Ok(PackageMeta.decode(TomlParser.parse(src)))
          catch case e: Exception => Result.Err(e.getMessage)
      finally zip.close()

  def path(name: String, version: Version): Result[Path] =
    val cached = artifactPath(name, version)
    if Files.exists(cached) then Result.Ok(cached)
    else
      recordFor(name, version).flatMap: rec =>
        download(rec.url, cached, rec.sha512).map(_ => cached)

  def digest(name: String, version: Version): Result[String] =
    recordFor(name, version).map(_.sha512)

  def materialize(name: String, version: Version): Result[Path] =
    path(name, version).map: archive =>
      val outDir = cacheHome.resolve("packages").resolve(name).resolve(version.toString).resolve("unpacked")
      materializeArchive(archive, outDir)

  // ---- Internals ---------------------------------------------------------------

  private def recordFor(name: String, version: Version): Result[ReleaseRecord] =
    records(name).flatMap: recs =>
      recs.find(_.version == version) match
        case Some(rec) => Result.Ok(rec)
        case None      => Result.Err(s"package not found: $name $version")

  private def records(name: String): Result[List[ReleaseRecord]] =
    memCache.get(name) match
      case Some(recs) => Result.Ok(recs)
      case None =>
        fetchIndex(name).map: recs =>
          memCache(name) = recs
          recs

  private def fetchIndex(name: String): Result[List[ReleaseRecord]] =
    val diskPath = cacheHome.resolve("index").resolve(s"$name.jsonl")

    val text =
      if Files.exists(diskPath) then
        val cached = Files.readString(diskPath)
        val age = System.currentTimeMillis() - Files.getLastModifiedTime(diskPath).toMillis
        if age < indexTtlMs then
          Result.Ok(cached)
        else
          refreshIndex(name, diskPath) match
            case Result.Ok(text) => Result.Ok(text)
            case Result.Err(_)   => Result.Ok(cached)
      else
        refreshIndex(name, diskPath)

    text.flatMap(parseIndex(name, _))

  private def refreshIndex(name: String, diskPath: Path): Result[String] =
    val url = s"$registryUrl/$name.jsonl"
    fetchText(url) match
      case Result.Err(_) => Result.Err(s"package not found: $name")
      case Result.Ok(text) =>
        Files.createDirectories(diskPath.getParent)
        Files.writeString(diskPath, text)
        Result.Ok(text)

  private def parseIndex(name: String, text: String): Result[List[ReleaseRecord]] =
    val parsed = text.linesIterator.zipWithIndex.foldLeft(Result.Ok(List.empty[ReleaseRecord])): (acc, entry) =>
      acc.flatMap: recs =>
        val (line, i) = entry
        if line.trim.isEmpty then
          Result.Ok(recs)
        else
          ReleaseJson.parse(line.trim) match
            case Right(rec) => Result.Ok(recs :+ rec)
            case Left(err)  => Result.Err(s"malformed line ${i + 1} in $name.jsonl: $err")
    parsed

  private def artifactPath(name: String, version: Version): Path =
    cacheHome.resolve("packages").resolve(name).resolve(version.toString).resolve(s"$name-v$version.joy")

  private def materializeArchive(archive: Path, outDir: Path): Path =
    val digest = Digest.sha512Hex(archive)
    val marker = outDir.resolve(".digest")

    if !(Files.isDirectory(outDir) && Files.exists(marker) && Files.readString(marker) == digest) then
      if Files.exists(outDir) then deleteDir(outDir)
      JoyArchive.unpack(archive, outDir)
      Files.writeString(marker, digest)

    outDir

  private def fetchText(url: String): Result[String] =
    try
      val req = HttpRequest.newBuilder(URI.create(url)).build()
      val res = http.send(req, HttpResponse.BodyHandlers.ofString())
      if res.statusCode() == 200 then Result.Ok(res.body())
      else Result.Err(s"HTTP ${res.statusCode()}: $url")
    catch
      case e: Exception => Result.Err(s"failed to fetch $url: ${e.getMessage}")

  private def download(url: String, dest: Path, expectedSha512: String): Result[Unit] =
    try
      Files.createDirectories(dest.getParent)
      val req = HttpRequest.newBuilder(URI.create(url)).build()
      val res = http.send(req, HttpResponse.BodyHandlers.ofFile(dest))
      if res.statusCode() != 200 then
        Files.deleteIfExists(dest)
        return Result.Err(s"HTTP ${res.statusCode()}: $url")
      val actual = Digest.sha512Hex(dest)
      if actual != expectedSha512 then
        Files.deleteIfExists(dest)
        Result.Err(s"sha512 mismatch for $url: expected $expectedSha512, got $actual")
      else
        Result.unit
    catch
      case e: Exception => Result.Err(s"failed to download $url: ${e.getMessage}")


// ---- JSON parsing for release records ----------------------------------------

private object ReleaseJson:
  def parse(line: String): Either[String, ReleaseRecord] =
    Json.parseObj(line.trim).flatMap: obj =>
      for
        versionStr <- requireStr(obj, "version")
        url        <- requireStr(obj, "url")
        sha512     <- requireStr(obj, "sha512")
        joStr      <- requireStr(obj, "jo")
        platform   <- requireStr(obj, "runtime")
        version    <- Version.parse(versionStr).toRight(s"invalid version: $versionStr")
        jo         <- VersionSpec.parse(joStr).left.map(msg => s"invalid jo '$joStr': $msg")
      yield
        val yanked = obj.get("yanked").collect { case b: Boolean => b }.getOrElse(false)
        val deps = obj.get("deps")
          .collect { case m: Map[String, Any] @unchecked => m }
          .getOrElse(Map.empty)
          .collect:
            case (k, v: String) =>
              VersionSpec.parse(v).toOption.map(k -> _)
          .flatten
          .toMap
        ReleaseRecord(version, url, sha512, jo, platform, deps, yanked)

  private def requireStr(obj: Map[String, Any], key: String): Either[String, String] =
    obj.get(key) match
      case Some(s: String) => Right(s)
      case Some(_)         => Left(s"'$key' must be a string")
      case None            => Left(s"missing required field '$key'")
