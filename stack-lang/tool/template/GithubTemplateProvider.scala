package tool.template

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import scala.util.Using

import tool.Result

/** [[TemplateProvider]] for GitHub (`gh:owner/repo`).
 *
 *  `manifest` (used only by `--list`) and `fetch` both prefer a shallow
 *  `git` checkout when `git` is on `PATH`: a real checkout is what lets
 *  either operation reach a private repo using whatever credentials the
 *  user's local `git` already has configured (SSH key, credential helper,
 *  `.netrc`, `insteadOf` rewrites, ...) — this tool never manages
 *  credentials itself. `git` doesn't care which transport a clone URL
 *  names, so rather than hardcoding one URL, `cloneBaseUrls` is an ordered
 *  list of candidates (HTTPS, then SSH, by default) tried in turn as
 *  `origin` until one of them authenticates; if every candidate fails, all
 *  of their failures are folded into the final error, not just the last
 *  one. A real checkout also reproduces Unix executable bits and symlinks
 *  correctly, which matters for `fetch` specifically (`java.util.zip`
 *  exposes neither — see `TemplateArchive`). When `git` isn't available,
 *  `manifest` falls back to an unauthenticated raw-content HTTP request and
 *  `fetch` falls back to downloading and unzipping a codeload archive —
 *  both public-repos-only, since there's no git credential store to draw
 *  on. Base URLs are constructor parameters, not hardcoded, so tests can
 *  point this at a local server or a local git repo instead of the real
 *  GitHub hosts.
 */
class GithubTemplateProvider(
  rawBaseUrl: String = GithubTemplateProvider.rawUrl,
  archiveBaseUrl: String = GithubTemplateProvider.archiveUrl,
  cloneBaseUrls: List[String] = GithubTemplateProvider.cloneUrls,
  gitAvailable: Boolean = GithubTemplateProvider.detectGit(),
) extends TemplateProvider:
  private val http = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

  def manifest(identifier: String, gitref: String): Result[List[TemplateEntry]] =
    if gitAvailable then manifestViaGit(identifier, gitref)
    else manifestViaHttp(identifier, gitref)

  def fetch(identifier: String, gitref: String, name: Option[String], destDir: Path): Result[Unit] =
    if gitAvailable then fetchViaGit(identifier, gitref, name, destDir)
    else fetchViaZip(identifier, gitref, name, destDir)

  // ---- Internals ---------------------------------------------------------------

  private def manifestViaGit(identifier: String, gitref: String): Result[List[TemplateEntry]] =
    parseIdentifier(identifier).flatMap: (owner, repo) =>
      checkoutViaGit(owner, repo, gitref).flatMap: checkoutDir =>
        try
          val manifestFile = checkoutDir.resolve("jo-templates.jsonl")

          if !Files.exists(manifestFile) then
            Result.Err(s"$identifier has no jo-templates.jsonl — not a valid Jo template repo")
          else
            TemplateManifest.parse(Files.readString(manifestFile))

        finally
          deleteRecursively(checkoutDir)

  private def manifestViaHttp(identifier: String, gitref: String): Result[List[TemplateEntry]] =
    parseIdentifier(identifier).flatMap: (owner, repo) =>
      val url = uri(rawBaseUrl, s"/$owner/$repo/$gitref/jo-templates.jsonl")

      get(url) match
        case FetchResult.Ok(body) =>
          TemplateManifest.parse(body)

        case FetchResult.NotFound =>
          Result.Err(s"$identifier has no jo-templates.jsonl — not a valid Jo template repo")

        case FetchResult.Failure(msg) =>
          Result.Err(msg)

  private def fetchViaGit(identifier: String, gitref: String, name: Option[String], destDir: Path): Result[Unit] =
    parseIdentifier(identifier).flatMap: (owner, repo) =>
      checkoutViaGit(owner, repo, gitref).flatMap: checkoutDir =>
        try
          TemplateArchive.resolveAndCopy(checkoutDir, name, destDir, s"$identifier at $gitref")
        finally
          deleteRecursively(checkoutDir)

  /** Shallow single-commit checkout of `owner/repo` at `gitref` into a fresh
   *  temp directory, returned to the caller (who owns cleanup).
   *
   *  `.git` is deleted from a successful checkout immediately: the result
   *  must be a plain directory either way, exactly like the zip path,
   *  regardless of how it was fetched internally.
   */
  private def checkoutViaGit(owner: String, repo: String, gitref: String): Result[Path] =
    val checkoutDir = Files.createTempDirectory("jo-template-")

    val checkedOut =
      for
        _ <- runGit("init", "-q", checkoutDir.toString)
        _ <- fetchFromFirstWorkingUrl(checkoutDir, owner, repo, gitref)
        _ <- runGit("-C", checkoutDir.toString, "checkout", "-q", "FETCH_HEAD")
      yield ()

    checkedOut match
      case Result.Ok(_) =>
        deleteRecursively(checkoutDir.resolve(".git"))
        Result.Ok(checkoutDir)

      case Result.Err(msg) =>
        deleteRecursively(checkoutDir)
        Result.Err(msg)

  /** Tries each of `cloneBaseUrls` as `origin`, in order, until one fetches
   *  successfully.
   *
   *  Each candidate is just a different transport for the same operation as
   *  far as git is concerned — nothing here needs to know HTTPS from SSH
   *  from a plain local path, or inspect a failure's error text to decide
   *  whether to move on. If every candidate fails, their failures are all
   *  reported together, not just the last one, since with only the last
   *  error a genuinely private repo (every candidate needs credentials
   *  nothing has) and a typo'd repo name (every candidate agrees it doesn't
   *  exist) would otherwise look identical.
   */
  private def fetchFromFirstWorkingUrl(checkoutDir: Path, owner: String, repo: String, gitref: String): Result[Unit] =
    def attempt(remaining: List[String], errors: List[String]): Result[Unit] =
      remaining match
        case Nil =>
          Result.Err(s"could not fetch $owner/$repo from any configured clone URL:\n" + errors.reverse.map(e => s"  - $e").mkString("\n"))

        case base :: rest =>
          val url = s"$base$owner/$repo.git"
          val remoteOp = if errors.isEmpty then "add" else "set-url"

          val result =
            for
              _ <- runGit("-C", checkoutDir.toString, "remote", remoteOp, "origin", url)
              _ <- runGit("-C", checkoutDir.toString, "fetch", "--depth", "1", "-q", "origin", gitref)
            yield ()

          result match
            case Result.Ok(_)   => Result.Ok(())
            case Result.Err(e)  => attempt(rest, e :: errors)

    attempt(cloneBaseUrls, Nil)

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

  /** Runs `git` with `args`, capturing combined output.
   *
   *  Fails fast rather than hanging on any prompt for credentials this
   *  process has nothing to answer with: `GIT_TERMINAL_PROMPT=0` covers
   *  git's own HTTPS credential prompt, and `GIT_SSH_COMMAND`'s
   *  `BatchMode=yes` covers the `ssh` subprocess's own passphrase /
   *  unknown-host-key prompts. The latter is simply inert whenever the URL
   *  being operated on isn't an SSH one, so it's always set rather than
   *  only for SSH-shaped candidates.
   */
  private def runGit(args: String*): Result[Unit] =
    try
      val pb = ProcessBuilder(("git" +: args)*).redirectErrorStream(true)
      pb.environment().put("GIT_TERMINAL_PROMPT", "0")
      pb.environment().put("GIT_SSH_COMMAND", "ssh -o BatchMode=yes")
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
  val rawUrl      = "https://raw.githubusercontent.com"
  val archiveUrl  = "https://codeload.github.com"

  /** Each entry is a prefix `owner/repo.git` is appended to directly (no
   *  separator inserted), so every candidate must already end in whatever
   *  its own transport needs: a trailing `/` for HTTPS, a trailing `:` for
   *  SSH's `user@host:path` scp-like syntax.
   *
   *  Tried in this order — HTTPS first since it's the more common case
   *  (public repos, or an HTTPS credential helper already configured), SSH
   *  second as the fallback for repos only reachable that way.
   */
  val cloneUrl    = "https://github.com/"
  val sshCloneUrl = "git@github.com:"
  val cloneUrls   = List(cloneUrl, sshCloneUrl)

  /** Checked once, at construction — not on every `fetch` call. */
  def detectGit(): Boolean =
    try
      val proc = ProcessBuilder("git", "--version").redirectErrorStream(true).start()
      proc.getInputStream.readAllBytes()
      proc.waitFor() == 0
    catch
      case _: Exception => false

  val default: GithubTemplateProvider = GithubTemplateProvider()
