package tool

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.util.zip.ZipFile
import tool.toml.TomlParser

/** A single parsed line from a package's JSONL release index. */
private case class ReleaseRecord(
  version: Version,
  url: String,
  sha512: String,
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
  cacheRoot: Path,
)(using logger: Logger) extends PackageProvider:
  private val http = HttpClient.newHttpClient()
  private val memCache = collection.mutable.Map.empty[String, List[ReleaseRecord]]
  private val indexTtlMs = 5 * 60 * 1000L  // 5 minutes

  def versions(name: String): Result[List[Version]] =
    records(name).map(_.filterNot(_.yanked).map(_.version).sorted)

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
    val diskPath = Config.index.resolve(s"$name.jsonl")

    val text =
      if Files.exists(diskPath) then
        val age = System.currentTimeMillis() - Files.getLastModifiedTime(diskPath).toMillis
        if age < indexTtlMs then
          Result.Ok(Files.readString(diskPath))
        else
          refreshIndex(name, diskPath)
      else
        refreshIndex(name, diskPath)

    text.map(parseIndex(name, _))

  private def refreshIndex(name: String, diskPath: Path): Result[String] =
    val url = s"$registryUrl/$name.jsonl"
    fetchText(url) match
      case Result.Err(_) => Result.Err(s"package not found: $name")
      case Result.Ok(text) =>
        Files.createDirectories(diskPath.getParent)
        Files.writeString(diskPath, text)
        Result.Ok(text)

  private def parseIndex(name: String, text: String): List[ReleaseRecord] =
    text.linesIterator
      .filter(_.trim.nonEmpty)
      .flatMap: line =>
        ReleaseJson.parse(line) match
          case Right(rec) => Some(rec)
          case Left(err)  =>
            Logger.warn(s"[registry] malformed line in $name.jsonl: $err\n")
            None
      .toList

  private def artifactPath(name: String, version: Version): Path =
    cacheRoot.resolve(name).resolve(version.toString).resolve(s"$name-v$version.joy")

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
      http.send(req, HttpResponse.BodyHandlers.ofFile(dest))
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
    JsonParser.parseObj(line.trim).flatMap: obj =>
      for
        versionStr <- requireStr(obj, "version")
        url        <- requireStr(obj, "url")
        sha512     <- requireStr(obj, "sha512")
        version    <- Version.parse(versionStr).toRight(s"invalid version: $versionStr")
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
        ReleaseRecord(version, url, sha512, deps, yanked)

  private def requireStr(obj: Map[String, Any], key: String): Either[String, String] =
    obj.get(key) match
      case Some(s: String) => Right(s)
      case Some(_)         => Left(s"'$key' must be a string")
      case None            => Left(s"missing required field '$key'")


// ---- Minimal recursive-descent JSON parser -----------------------------------

private object JsonParser:
  def parseObj(input: String): Either[String, Map[String, Any]] =
    parseObject(input, 0) match
      case Right((obj, _)) => Right(obj)
      case Left(msg)       => Left(msg)

  private def ws(s: String, i: Int): Int =
    var j = i
    while j < s.length && s(j).isWhitespace do j += 1
    j

  private def parseObject(s: String, i0: Int): Either[String, (Map[String, Any], Int)] =
    var i = ws(s, i0)
    if i >= s.length || s(i) != '{' then return Left(s"expected '{' at $i")
    i = ws(s, i + 1)

    val fields = collection.mutable.LinkedHashMap.empty[String, Any]
    var first = true

    while i < s.length && s(i) != '}' do
      if !first then
        if s(i) != ',' then return Left(s"expected ',' at $i")
        i = ws(s, i + 1)
      first = false

      parseString(s, i) match
        case Left(msg) => return Left(msg)
        case Right((key, j)) =>
          i = ws(s, j)
          if i >= s.length || s(i) != ':' then return Left(s"expected ':' at $i")
          i = ws(s, i + 1)
          parseValue(s, i) match
            case Left(msg) => return Left(msg)
            case Right((v, j2)) =>
              fields(key) = v
              i = ws(s, j2)

    if i >= s.length then Left("unterminated object")
    else Right((fields.toMap, i + 1))

  private def parseValue(s: String, i: Int): Either[String, (Any, Int)] =
    if i >= s.length then Left("unexpected end of input")
    else s(i) match
      case '"'                                => parseString(s, i)
      case '{'                                => parseObject(s, i)
      case '['                                => parseArray(s, i)
      case 't' if s.startsWith("true", i)    => Right((true,  i + 4))
      case 'f' if s.startsWith("false", i)   => Right((false, i + 5))
      case 'n' if s.startsWith("null", i)    => Right((null,  i + 4))
      case c if c.isDigit || c == '-'        => parseNumber(s, i)
      case c                                  => Left(s"unexpected char '$c' at $i")

  private def parseString(s: String, i0: Int): Either[String, (String, Int)] =
    if i0 >= s.length || s(i0) != '"' then return Left(s"expected '\"' at $i0")
    val sb = new StringBuilder
    var i = i0 + 1
    while i < s.length && s(i) != '"' do
      if s(i) == '\\' && i + 1 < s.length then
        s(i + 1) match
          case '"'  => sb += '"';  i += 2
          case '\\' => sb += '\\'; i += 2
          case '/'  => sb += '/';  i += 2
          case 'n'  => sb += '\n'; i += 2
          case 'r'  => sb += '\r'; i += 2
          case 't'  => sb += '\t'; i += 2
          case c    => sb += c;    i += 2
      else
        sb += s(i)
        i += 1
    if i >= s.length then Left("unterminated string")
    else Right((sb.toString, i + 1))

  private def parseArray(s: String, i0: Int): Either[String, (List[Any], Int)] =
    var i = ws(s, i0 + 1)
    val items = collection.mutable.ListBuffer.empty[Any]
    var first = true
    while i < s.length && s(i) != ']' do
      if !first then
        if s(i) != ',' then return Left(s"expected ',' at $i")
        i = ws(s, i + 1)
      first = false
      parseValue(s, i) match
        case Left(msg) => return Left(msg)
        case Right((v, j)) =>
          items += v
          i = ws(s, j)
    if i >= s.length then Left("unterminated array")
    else Right((items.toList, i + 1))

  private def parseNumber(s: String, i0: Int): Either[String, (String, Int)] =
    var i = i0
    if i < s.length && s(i) == '-' then i += 1
    while i < s.length && s(i).isDigit do i += 1
    Right((s.substring(i0, i), i))
