package tool.template

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}

import tool.Result

/** [[TemplateProvider]] for GitHub (`gh:owner/repo`).
 *
 *  Uses two unauthenticated, CDN-backed endpoints — neither counts against
 *  `api.github.com` rate limits, so no token is needed. Base URLs are
 *  constructor parameters, not hardcoded, so tests can point this at a local
 *  server instead of the real GitHub hosts.
 */
class GithubTemplateProvider(
  rawBaseUrl: String = "https://raw.githubusercontent.com",
  archiveBaseUrl: String = "https://codeload.github.com",
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

  // ---- Internals ---------------------------------------------------------------

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
  val default: GithubTemplateProvider = GithubTemplateProvider()
