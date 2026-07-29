package tool.template

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import scala.util.Using

import tool.Result

/** [[TemplateProvider]] for GitHub (`gh:owner/repo`).
 *
 *  `manifest` (used only by `--list`) always uses the raw-content HTTP
 *  endpoint — cheap, and safe as a standalone fetch since nothing else gets
 *  combined with it. `fetch` prefers a shallow `git` checkout when `git` is
 *  on `PATH`, since only a real checkout reproduces Unix executable bits
 *  and symlinks correctly (`java.util.zip` exposes neither — see
 *  `TemplateArchive`); it falls back to downloading and unzipping a
 *  codeload archive when `git` isn't available. Base URLs are constructor
 *  parameters, not hardcoded, so tests can point this at a local server or
 *  a local git repo instead of the real GitHub hosts.
 */
class GithubTemplateProvider(
  rawBaseUrl: String = GithubTemplateProvider.rawUrl,
  archiveBaseUrl: String = GithubTemplateProvider.archiveUrl,
  cloneBaseUrl: String = GithubTemplateProvider.cloneUrl,
  gitAvailable: Boolean = GithubTemplateProvider.detectGit(),
) extends TemplateProvider:
  private val http = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

  def manifest(identifier: String, gitref: String): Result[List[TemplateEntry]] =
    parseIdentifier(identifier).flatMap: (owner, repo) =>
      val url = uri(rawBaseUrl, s"/$owner/$repo/$gitref/jo-templates.jsonl")

      get(url) match
        case FetchResult.Ok(body) =>
          TemplateManifest.parse(body)

        case FetchResult.NotFound =>
          Result.Err(s"$identifier has no jo-templates.jsonl — not a valid Jo template repo")

        case FetchResult.Failure(msg) =>
          Result.Err(msg)

  def fetch(identifier: String, gitref: String, name: Option[String], destDir: Path): Result[Unit] =
    if gitAvailable then fetchViaGit(identifier, gitref, name, destDir)
    else fetchViaZip(identifier, gitref, name, destDir)

  // ---- Internals ---------------------------------------------------------------

  private def fetchViaGit(identifier: String, gitref: String, name: Option[String], destDir: Path): Result[Unit] =
    parseIdentifier(identifier).flatMap: (owner, repo) =>
      val url = s"$cloneBaseUrl/$owner/$repo.git"
      val checkoutDir = Files.createTempDirectory("jo-template-")

      try
        val checkedOut =
          for
            _ <- runGit("init", "-q", checkoutDir.toString)
            _ <- runGit("-C", checkoutDir.toString, "remote", "add", "origin", url)
            _ <- runGit("-C", checkoutDir.toString, "fetch", "--depth", "1", "-q", "origin", gitref)
            _ <- runGit("-C", checkoutDir.toString, "checkout", "-q", "FETCH_HEAD")
          yield ()

        checkedOut.flatMap: _ =>
          // Never ship git's own metadata into the scaffolded project — the
          // user-facing result is a plain directory either way, exactly
          // like the zip path, regardless of how it was fetched internally.
          deleteRecursively(checkoutDir.resolve(".git"))
          TemplateArchive.resolveAndCopy(checkoutDir, name, destDir, s"$identifier at $gitref")

      finally
        deleteRecursively(checkoutDir)

  private def fetchViaZip(identifier: String, gitref: String, name: Option[String], destDir: Path): Result[Unit] =
    parseIdentifier(identifier).flatMap: (owner, repo) =>
      val url = uri(archiveBaseUrl, s"/$owner/$repo/zip/$gitref")
      val tempZip = Files.createTempFile("jo-template-", ".zip")

      try
        getToFile(url, tempZip) match
          case FetchResult.Ok(_) =>
            TemplateArchive.extract(tempZip, name, destDir, s"$identifier at $gitref")

          case FetchResult.NotFound =>
            Result.Err(s"$identifier has no ref '$gitref', or the repo does not exist")

          case FetchResult.Failure(msg) =>
            Result.Err(msg)

      finally
        Files.deleteIfExists(tempZip)

  /** Runs `git` with `args`, capturing combined output. `GIT_TERMINAL_PROMPT=0`
   *  ensures a private or nonexistent repo fails fast with an error instead
   *  of hanging on a credential prompt with nothing to answer it.
   */
  private def runGit(args: String*): Result[Unit] =
    try
      val pb = ProcessBuilder(("git" +: args)*).redirectErrorStream(true)
      pb.environment().put("GIT_TERMINAL_PROMPT", "0")
      val proc = pb.start()
      val output = String(proc.getInputStream.readAllBytes(), "UTF-8")
      val exit = proc.waitFor()
      if exit == 0 then Result.unit
      else Result.Err(s"git ${args.mkString(" ")} failed (exit $exit): ${output.trim}")
    catch
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        Result.Err(s"failed to run git ${args.mkString(" ")}: ${describe(e)}")

      case e: Exception =>
        Result.Err(s"failed to run git ${args.mkString(" ")}: ${describe(e)}")

  private def deleteRecursively(dir: Path): Unit =
    if Files.exists(dir) then
      Using.resource(Files.walk(dir)): stream =>
        stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(Files.delete)

  private val ownerRepoChars = "^[A-Za-z0-9._-]+$".r

  private def parseIdentifier(identifier: String): Result[(String, String)] =
    identifier.split("/", -1) match
      case Array(owner, repo) if ownerRepoChars.matches(owner) && ownerRepoChars.matches(repo) =>
        Result.Ok((owner, repo))
      case _ =>
        Result.Err(s"invalid GitHub identifier '$identifier' (expected 'owner/repo', letters/digits/'.'/'_'/'-' only)")

  /** Builds a request URI from a trusted base (`rawBaseUrl`/`archiveBaseUrl`,
   *  either the real GitHub hosts or a test server) and a `path` built from
   *  untrusted input (`gitref` in particular — a git ref can legally contain
   *  `#`, which `URI.create` on a plain interpolated string would parse as
   *  the start of a fragment, silently truncating everything after it from
   *  the actual request).
   *
   *  The multi-argument `URI` constructor percent-encodes characters that
   *  aren't valid in a path (`#`, spaces, etc.) while still treating `/` as
   *  a structural separator — which matters because branch names themselves
   *  can legally contain `/` (e.g. `release/1.0`) and must stay usable as
   *  path segments rather than being escaped into `%2F`.
   */
  private def uri(baseUrl: String, path: String): URI =
    val base = URI.create(baseUrl)
    URI(base.getScheme, base.getAuthority, path, null, null)

  private enum FetchResult[+A]:
    case Ok(value: A)
    case NotFound
    case Failure(message: String)

  /** `Throwable.getMessage` is nullable when a exception was raised with no
   *  message, and embedding a literal "null" in an error string reads like
   *  a bug, not a message — fall back to the exception's class name.
   */
  private def describe(e: Exception): String =
    Option(e.getMessage).getOrElse(e.getClass.getSimpleName)

  private def get(url: URI): FetchResult[String] =
    try
      val req = HttpRequest.newBuilder(url).build()
      val res = http.send(req, HttpResponse.BodyHandlers.ofString())
      if res.statusCode() == 200 then FetchResult.Ok(res.body())
      else if res.statusCode() == 404 then FetchResult.NotFound
      else FetchResult.Failure(s"HTTP ${res.statusCode()}: $url")
    catch
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        FetchResult.Failure(s"failed to fetch $url: ${describe(e)}")

      case e: Exception =>
        FetchResult.Failure(s"failed to fetch $url: ${describe(e)}")

  private def getToFile(url: URI, dest: Path): FetchResult[Unit] =
    try
      val req = HttpRequest.newBuilder(url).build()
      val res = http.send(req, HttpResponse.BodyHandlers.ofFile(dest))
      if res.statusCode() == 200 then FetchResult.Ok(())
      else
        Files.deleteIfExists(dest)
        if res.statusCode() == 404 then FetchResult.NotFound
        else FetchResult.Failure(s"HTTP ${res.statusCode()}: $url")
    catch
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        Files.deleteIfExists(dest)
        FetchResult.Failure(s"failed to fetch $url: ${describe(e)}")

      case e: Exception =>
        Files.deleteIfExists(dest)
        FetchResult.Failure(s"failed to fetch $url: ${describe(e)}")

object GithubTemplateProvider:
  val rawUrl     = "https://raw.githubusercontent.com"
  val archiveUrl = "https://codeload.github.com"
  val cloneUrl   = "https://github.com"

  /** Checked once, at construction — not on every `fetch` call. */
  def detectGit(): Boolean =
    try
      val proc = ProcessBuilder("git", "--version").redirectErrorStream(true).start()
      proc.getInputStream.readAllBytes()
      proc.waitFor() == 0
    catch
      case _: Exception => false

  val default: GithubTemplateProvider = GithubTemplateProvider()
