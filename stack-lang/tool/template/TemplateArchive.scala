package tool.template

import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import tool.{JoyArchive, Result}

/** Resolves and extracts a template out of a materialized repo source (a
 *  downloaded archive, a git checkout, or — in tests — a static fixture).
 *
 *  Kept independent of how the source was obtained (no HTTP, no `git` here)
 *  so it can be unit-tested directly against a hand-built directory or zip
 *  fixture.
 */
object TemplateArchive:
  /** Unzips `zipFile`, then resolves and copies via [[resolveAndCopy]].
   *
   *  Used only as a fallback when `git` isn't available — see
   *  `GithubTemplateProvider` — since plain `java.util.zip` can't preserve
   *  Unix executable bits or symlinks (there's no JDK API for either), and
   *  a git checkout can.
   *
   *  Archives are expected to contain exactly one top-level directory (as
   *  GitHub/GitLab/Gitea archive endpoints always produce) — its name isn't
   *  predictable ahead of time, so it's discovered rather than assumed.
   *  Reuses `JoyArchive.unpack` for the actual unzipping, including its
   *  zip-slip guard.
   */
  def extract(zipFile: Path, name: Option[String], destDir: Path, label: String): Result[Unit] =
    val tempDir = Files.createTempDirectory("jo-template-")

    try
      JoyArchive.unpack(zipFile, tempDir)

      topLevelDir(tempDir) match
        case None =>
          Result.Err(s"unexpected archive structure for $label (expected exactly one top-level directory)")

        case Some(root) =>
          resolveAndCopy(root, name, destDir, label)

    catch
      case e: Exception => Result.Err(describe(e))

    finally
      deleteRecursively(tempDir)

  /** Reads `jo-templates.jsonl` from `root`, resolves `name` against it, and
   *  copies the resolved template into `destDir`. Shared by every way a
   *  template source can be materialized — an extracted zip, a git
   *  checkout, or (in tests / `LocalTemplateProvider`) a static fixture —
   *  so the manifest is always read from the exact same materialization as
   *  the files it describes, never fetched or resolved separately. That
   *  matters because `gitref` can be a mutable ref (branch, or the default
   *  `HEAD`): reading the manifest from one fetch and the files from a
   *  second, independent one could silently validate one revision and
   *  extract another if the branch moved in between.
   */
  def resolveAndCopy(root: Path, name: Option[String], destDir: Path, label: String): Result[Unit] =
    val manifestFile = root.resolve("jo-templates.jsonl")

    if !Files.exists(manifestFile) then
      Result.Err(s"$label has no jo-templates.jsonl — not a valid Jo template repo")
    else
      for
        entries <- TemplateManifest.parse(Files.readString(manifestFile))
        entry   <- TemplateManifest.resolve(entries, name, label)
        _       <- copyResolved(root, entry.path, destDir, label)
      yield ()

  private def describe(e: Exception): String =
    Option(e.getMessage).getOrElse(e.getClass.getSimpleName)

  private def copyResolved(root: Path, path: String, destDir: Path, label: String): Result[Unit] =
    val source = if path == "." then root else root.resolve(path).normalize()

    if !source.startsWith(root) || !Files.isDirectory(source) then
      Result.Err(s"template path '$path' not found in $label")
    else
      copyTree(source, destDir)

  private def topLevelDir(root: Path): Option[Path] =
    Using.resource(Files.list(root)): stream =>
      stream.iterator.asScala.toList match
        case single :: Nil if Files.isDirectory(single) => Some(single)
        case _                                          => None

  /** Copies the contents of `source` (a trusted local directory — not zip
   *  entries, so no traversal guard needed here) into `destDir`. Shared with
   *  `LocalTemplateProvider`, which copies straight from a fixture root
   *  rather than an extracted archive.
   *
   *  Stages into a temporary sibling of `destDir` and atomically renames it
   *  into place only once every file has copied successfully. A disk, I/O,
   *  or permission failure partway through then leaves no `destDir` at all,
   *  rather than a half-populated one that would fail the collision check
   *  on a retry. The staging directory sits next to `destDir` (not under
   *  the system temp dir) specifically so the rename is same-filesystem,
   *  and therefore atomic.
   *
   *  Symlinks in `source` (e.g. from a git checkout) are recreated as
   *  symlinks, not dereferenced — `Files.copy` follows symlinks by default,
   *  which would otherwise silently replace a link with a copy of whatever
   *  it points to.
   */
  def copyTree(source: Path, destDir: Path): Result[Unit] =
    val staging = Files.createTempDirectory(destDir.getParent, ".jo-new-staging-")

    try
      val entries = Using.resource(Files.walk(source)): stream =>
        stream.iterator.asScala.filterNot(_ == source).toList

      for entry <- entries do
        val target = staging.resolve(source.relativize(entry).toString)

        if Files.isSymbolicLink(entry) then
          Files.createDirectories(target.getParent)
          Files.createSymbolicLink(target, Files.readSymbolicLink(entry))
        else if Files.isDirectory(entry) then
          Files.createDirectories(target)
        else
          Files.createDirectories(target.getParent)
          Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING)

      Files.move(staging, destDir, StandardCopyOption.ATOMIC_MOVE)
      Result.unit

    catch
      case e: Exception =>
        deleteRecursively(staging)
        Result.Err(s"failed to write '$destDir': ${describe(e)}")

  private def deleteRecursively(dir: Path): Unit =
    if Files.exists(dir) then
      Using.resource(Files.walk(dir)): stream =>
        stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(Files.delete)
