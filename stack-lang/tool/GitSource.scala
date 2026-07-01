package tool

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*
import tool.toml.TomlParser

/** Result of resolving a git dependency. */
enum GitResolution:
  /** A precompiled .joy archive was found in a GitHub release. */
  case Precompiled(
    joyPath:  Path,
    joyUrl:   String,
    sha512:   String,
    version:  Version,
    depInfo:  PackageDependencyInfo,
  )
  /** Source was downloaded; the project lives in sourceDir at commit rev. */
  case Source(sourceDir: Path, rev: String)

/** Fetches Jo dependencies from GitHub repositories.
 *
 *  For tag refs, first checks GitHub Releases for a precompiled .joy asset.
 *  If found, downloads and uses it directly (no local compilation needed).
 *  Otherwise falls back to downloading the source archive and compiling locally.
 *
 *  Cache layout under cacheHome:
 *    git/{user}/{repo}/src/{rev}/          extracted source trees
 *    git/{user}/{repo}/releases/{file}     precompiled .joy assets
 */
object GitSource:
  private val http = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

  /** Resolve a git dependency declared with `git = "…"` in jo.toml.
   *
   *  If `locked` is provided the lock file entry is used directly, avoiding
   *  network calls for commit-SHA resolution.  Only the asset/source download
   *  itself is still performed when not already cached.
   */
  def resolve(
    url:       String,
    ref:       Option[GitRef],
    locked:    Option[LockedGitDep],
    joVersion: Version,
    cacheHome: Path,
  ): Result[GitResolution] =
    parseGitHubUrl(url) match
      case None =>
        Result.Err(
          s"unsupported git URL '$url': only GitHub URLs are currently supported " +
          "(e.g. https://github.com/user/repo)"
        )
      case Some((user, repo)) =>
        locked match
          case Some(dep) => resolveFromLock(user, repo, dep, joVersion, cacheHome)
          case None      => resolveFromGitHub(user, repo, ref, joVersion, cacheHome)

  // ---- Lock-file path --------------------------------------------------------

  private def resolveFromLock(
    user:      String,
    repo:      String,
    dep:       LockedGitDep,
    joVersion: Version,
    cacheHome: Path,
  ): Result[GitResolution] =
    dep.source match
      case LockedGitSource.Precompiled(joyUrl, sha512) =>
        val joyPath = precompiledCachePath(cacheHome, user, repo, joyUrl)
        ensurePrecompiled(joyPath, joyUrl, sha512).flatMap: _ =>
          readPackageInfo(joyPath, dep.url, joVersion).map: (version, depInfo) =>
            GitResolution.Precompiled(joyPath, joyUrl, sha512, version, depInfo)

      case LockedGitSource.Source(rev) =>
        ensureSource(user, repo, rev, cacheHome).map: sourceDir =>
          GitResolution.Source(sourceDir, rev)

  // ---- Fresh resolution ------------------------------------------------------

  private def resolveFromGitHub(
    user:      String,
    repo:      String,
    ref:       Option[GitRef],
    joVersion: Version,
    cacheHome: Path,
  ): Result[GitResolution] =
    ref match
      case Some(GitRef.Rev(rev)) =>
        ensureSource(user, repo, rev, cacheHome).map(GitResolution.Source(_, rev))

      case Some(GitRef.Tag(tag)) =>
        tryPrecompiledRelease(user, repo, tag, joVersion, cacheHome) match
          case Some(result) => result
          case None         =>
            resolveRefToRev(user, repo, tag).flatMap: rev =>
              ensureSource(user, repo, rev, cacheHome).map(GitResolution.Source(_, rev))

      case Some(GitRef.Branch(branch)) =>
        resolveRefToRev(user, repo, branch).flatMap: rev =>
          ensureSource(user, repo, rev, cacheHome).map(GitResolution.Source(_, rev))

      case None =>
        resolveRefToRev(user, repo, "HEAD").flatMap: rev =>
          ensureSource(user, repo, rev, cacheHome).map(GitResolution.Source(_, rev))

  // ---- Precompiled release asset ---------------------------------------------

  /** Check if the GitHub release for `tag` contains a .joy asset.
   *  Returns None if there is no release or no .joy asset, so the caller
   *  can fall back to source compilation.
   */
  private def tryPrecompiledRelease(
    user:      String,
    repo:      String,
    tag:       String,
    joVersion: Version,
    cacheHome: Path,
  ): Option[Result[GitResolution]] =
    val releaseUrl = s"https://api.github.com/repos/$user/$repo/releases/tags/$tag"
    fetchJson(releaseUrl) match
      case Result.Err(_) => None
      case Result.Ok(obj) =>
        findJoyAssetUrl(obj) match
          case None         => None
          case Some(joyUrl) =>
            val joyPath = precompiledCachePath(cacheHome, user, repo, joyUrl)
            val result  =
              downloadIfNeeded(joyPath, joyUrl).flatMap: _ =>
                val sha512 = Digest.sha512Hex(joyPath)
                readPackageInfo(joyPath, s"https://github.com/$user/$repo", joVersion).map:
                  (version, depInfo) =>
                    GitResolution.Precompiled(joyPath, joyUrl, sha512, version, depInfo)
            Some(result)

  /** Extract the browser_download_url of the first .joy asset in the parsed
   *  GitHub Releases API response object.
   */
  private def findJoyAssetUrl(obj: Map[String, Any]): Option[String] =
    obj.get("assets") match
      case Some(items: List[?]) =>
        items.collectFirst:
          case asset: Map[String, Any] @unchecked
              if asset.get("name").exists { case n: String => n.endsWith(".joy"); case _ => false } =>
            asset.get("browser_download_url").collect { case url: String => url }
        .flatten
      case _ => None

  // ---- Source tree -----------------------------------------------------------

  /** Ensure the extracted source tree for `rev` exists and return its path. */
  private def ensureSource(user: String, repo: String, rev: String, cacheHome: Path): Result[Path] =
    val dest = cacheHome.resolve("git").resolve(user).resolve(repo).resolve("src").resolve(rev)

    if Files.isDirectory(dest) && Files.exists(dest.resolve("jo.toml")) then
      return Result.Ok(dest)

    val zipUrl = s"https://github.com/$user/$repo/archive/$rev.zip"
    val tmpZip = dest.resolveSibling(s"$rev-download.zip")
    Files.createDirectories(dest.getParent)

    fetchBinary(zipUrl, tmpZip).flatMap: _ =>
      extractZipStrippingTopDir(tmpZip, dest).map: _ =>
        Files.deleteIfExists(tmpZip)
        dest

  // ---- Precompiled .joy asset ------------------------------------------------

  private def ensurePrecompiled(joyPath: Path, joyUrl: String, expectedSha512: String): Result[Unit] =
    if Files.exists(joyPath) then
      val actual = Digest.sha512Hex(joyPath)
      if actual == expectedSha512 then
        Result.unit
      else
        Files.deleteIfExists(joyPath)
        downloadIfNeeded(joyPath, joyUrl)
    else
      downloadIfNeeded(joyPath, joyUrl)

  private def downloadIfNeeded(dest: Path, url: String): Result[Unit] =
    if Files.exists(dest) then Result.unit
    else
      Files.createDirectories(dest.getParent)
      fetchBinary(url, dest)

  /** Read the version and dependency info from meta.toml inside a .joy archive,
   *  validating that the package is compatible with the current compiler.
   */
  private def readPackageInfo(
    joyPath:   Path,
    sourceUrl: String,
    joVersion: Version,
  ): Result[(Version, PackageDependencyInfo)] =
    val zip = ZipFile(joyPath.toFile)
    try
      val entry = zip.getEntry("meta.toml")
      if entry == null then
        return Result.Err(s"meta.toml not found in precompiled .joy from $sourceUrl")

      val src = String(zip.getInputStream(entry).readAllBytes(), "UTF-8")
      try
        val meta = PackageMeta.decode(TomlParser.parse(src))

        if !meta.jo.contains(joVersion) then
          return Result.Err(
            s"precompiled package from $sourceUrl requires Jo ${meta.jo.show}, " +
            s"but the current compiler is $joVersion"
          )

        Version.parse(meta.version) match
          case None =>
            Result.Err(s"invalid version '${meta.version}' in meta.toml from $sourceUrl")
          case Some(version) =>
            Result.Ok(version -> PackageMeta.dependencyInfo(meta))

      catch
        case e: Exception =>
          Result.Err(s"invalid meta.toml in precompiled .joy from $sourceUrl: ${e.getMessage}")

    finally zip.close()

  // ---- Commit SHA resolution -------------------------------------------------

  /** Call the GitHub Commits API to resolve a ref (branch/tag/HEAD) to a SHA. */
  private def resolveRefToRev(user: String, repo: String, ref: String): Result[String] =
    val url = s"https://api.github.com/repos/$user/$repo/commits/$ref"
    fetchJson(url) match
      case Result.Err(_) =>
        Result.Err(s"could not resolve git ref '$ref' in $user/$repo (repository or ref not found)")
      case Result.Ok(obj) =>
        obj.get("sha") match
          case Some(sha: String) if sha.length == 40 => Result.Ok(sha)
          case _ =>
            Result.Err(s"unexpected response from GitHub API for $user/$repo commits/$ref")

  // ---- ZIP extraction --------------------------------------------------------

  /** Extract a ZIP archive into destDir, stripping the single top-level directory
   *  that GitHub includes in every repository archive (e.g. "repo-abc123/").
   */
  private def extractZipStrippingTopDir(zipFile: Path, destDir: Path): Result[Unit] =
    try
      Files.createDirectories(destDir)
      val zip = ZipFile(zipFile.toFile)

      try
        val entries = zip.entries().asScala.toList
        val prefix  = entries.headOption.map: e =>
          val name = e.getName.replace('\\', '/')
          name.takeWhile(_ != '/') + "/"
        .getOrElse("")

        entries.foreach: entry =>
          val name     = entry.getName.replace('\\', '/')
          val stripped = if prefix.nonEmpty && name.startsWith(prefix) then name.drop(prefix.length) else name

          if stripped.nonEmpty then
            val target = destDir.resolve(stripped).normalize()

            if !target.startsWith(destDir) then
              throw ArchiveError(s"invalid archive entry '${entry.getName}'")

            if entry.isDirectory then
              Files.createDirectories(target)
            else
              Files.createDirectories(target.getParent)
              Files.copy(zip.getInputStream(entry), target, StandardCopyOption.REPLACE_EXISTING)

      finally zip.close()

      Result.unit

    catch
      case e: ArchiveError => Result.Err(e.getMessage)
      case e: Exception    => Result.Err(s"failed to extract archive: ${e.getMessage}")

  // ---- Path helpers ----------------------------------------------------------

  private def precompiledCachePath(cacheHome: Path, user: String, repo: String, joyUrl: String): Path =
    val filename = joyUrl.split('/').last
    cacheHome.resolve("git").resolve(user).resolve(repo).resolve("releases").resolve(filename)

  private def parseGitHubUrl(url: String): Option[(String, String)] =
    val pattern = "(?:https?://)?github\\.com/([^/]+)/([^/]+?)(?:\\.git)?(?:/.*)?$".r
    pattern.findFirstMatchIn(url).map(m => (m.group(1), m.group(2)))

  // ---- HTTP helpers ----------------------------------------------------------

  private def fetchJson(url: String): Result[Map[String, Any]] =
    fetchText(url).flatMap: body =>
      JsonParser.parseObj(body) match
        case Right(obj) => Result.Ok(obj)
        case Left(err)  => Result.Err(s"invalid JSON response from $url: $err")

  private def fetchText(url: String): Result[String] =
    try
      val req = HttpRequest.newBuilder(URI.create(url))
        .header("Accept", "application/vnd.github.v3+json")
        .header("User-Agent", "jo-lang/tool")
        .build()
      val res = http.send(req, HttpResponse.BodyHandlers.ofString())
      if res.statusCode() == 200 then Result.Ok(res.body())
      else Result.Err(s"HTTP ${res.statusCode()}: $url")
    catch
      case e: Exception => Result.Err(s"failed to fetch $url: ${e.getMessage}")

  private def fetchBinary(url: String, dest: Path): Result[Unit] =
    try
      val req = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "jo-lang/tool")
        .build()
      val res = http.send(req, HttpResponse.BodyHandlers.ofFile(dest))
      if res.statusCode() == 200 then Result.unit
      else
        Files.deleteIfExists(dest)
        Result.Err(s"HTTP ${res.statusCode()}: $url")
    catch
      case e: Exception => Result.Err(s"failed to download $url: ${e.getMessage}")
