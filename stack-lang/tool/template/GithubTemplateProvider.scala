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
      val url = s"$rawBaseUrl/$owner/$repo/$gitref/jo-templates.jsonl"

      get(url) match
        case FetchResult.Ok(body) =>
          TemplateManifest.parse(body)

        case FetchResult.NotFound =>
          Result.Err(s"error: $identifier has no jo-templates.jsonl — not a valid Jo template repo")

        case FetchResult.Failure(msg) =>
          Result.Err(msg)

  def fetch(identifier: String, gitref: String, path: String, destDir: Path): Result[Unit] =
    parseIdentifier(identifier).flatMap: (owner, repo) =>
      val url = s"$archiveBaseUrl/$owner/$repo/zip/$gitref"
      val tempZip = Files.createTempFile("jo-template-", ".zip")

      try
        getToFile(url, tempZip) match
          case FetchResult.Ok(_) =>
            TemplateArchive.extract(tempZip, path, destDir, s"$identifier at $gitref")

          case FetchResult.NotFound =>
            Result.Err(s"error: $identifier has no ref '$gitref', or the repo does not exist")

          case FetchResult.Failure(msg) =>
            Result.Err(msg)

      finally
        Files.deleteIfExists(tempZip)

  // ---- Internals ---------------------------------------------------------------

  private def parseIdentifier(identifier: String): Result[(String, String)] =
    identifier.split("/", -1) match
      case Array(owner, repo) if owner.nonEmpty && repo.nonEmpty =>
        Result.Ok((owner, repo))
      case _ =>
        Result.Err(s"error: invalid GitHub identifier '$identifier' (expected 'owner/repo')")

  private enum FetchResult[+A]:
    case Ok(value: A)
    case NotFound
    case Failure(message: String)

  private def get(url: String): FetchResult[String] =
    try
      val req = HttpRequest.newBuilder(URI.create(url)).build()
      val res = http.send(req, HttpResponse.BodyHandlers.ofString())
      if res.statusCode() == 200 then FetchResult.Ok(res.body())
      else if res.statusCode() == 404 then FetchResult.NotFound
      else FetchResult.Failure(s"error: HTTP ${res.statusCode()}: $url")
    catch
      case e: Exception => FetchResult.Failure(s"error: failed to fetch $url: ${e.getMessage}")

  private def getToFile(url: String, dest: Path): FetchResult[Unit] =
    try
      val req = HttpRequest.newBuilder(URI.create(url)).build()
      val res = http.send(req, HttpResponse.BodyHandlers.ofFile(dest))
      if res.statusCode() == 200 then FetchResult.Ok(())
      else
        Files.deleteIfExists(dest)
        if res.statusCode() == 404 then FetchResult.NotFound
        else FetchResult.Failure(s"error: HTTP ${res.statusCode()}: $url")
    catch
      case e: Exception =>
        Files.deleteIfExists(dest)
        FetchResult.Failure(s"error: failed to fetch $url: ${e.getMessage}")

object GithubTemplateProvider:
  val default: GithubTemplateProvider = GithubTemplateProvider()
