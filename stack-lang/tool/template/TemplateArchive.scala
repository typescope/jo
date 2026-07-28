package tool.template

import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*

import tool.{ArchiveError, JoyArchive, Result}

/** Resolves and extracts a template out of a downloaded repo archive.
 *
 *  Kept independent of how the zip was obtained (no HTTP here) so it can be
 *  unit-tested directly against a hand-built zip fixture.
 */
object TemplateArchive:
  /** Unzips `zipFile`, reads its `jo-templates.jsonl`, resolves `name`
   *  against the manifest found there, and copies the resolved template
   *  into `destDir`.
   *
   *  The manifest is read from the *same* extracted archive as the files it
   *  describes — never fetched separately. That matters because `gitref`
   *  can be a mutable ref (branch, or the default `HEAD`): a caller that
   *  fetched a manifest and an archive as two independent requests could
   *  observe two different commits if the branch moved in between, silently
   *  validating one revision and extracting another. A single archive fetch
   *  makes that impossible — manifest and files can't diverge if there's
   *  only ever one download.
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
          Result.Err(s"error: unexpected archive structure for $label (expected exactly one top-level directory)")

        case Some(root) =>
          val manifestFile = root.resolve("jo-templates.jsonl")

          if !Files.exists(manifestFile) then
            Result.Err(s"error: $label has no jo-templates.jsonl — not a valid Jo template repo")
          else
            for
              entries <- TemplateManifest.parse(Files.readString(manifestFile))
              entry   <- TemplateManifest.resolve(entries, name, label)
              _       <- copyResolved(root, entry.path, destDir, label)
            yield ()

    catch
      case e: ArchiveError => Result.Err(s"error: ${e.message}")

    finally
      deleteRecursively(tempDir)

  private def copyResolved(root: Path, path: String, destDir: Path, label: String): Result[Unit] =
    val source = if path == "." then root else root.resolve(path).normalize()

    if !source.startsWith(root) || !Files.isDirectory(source) then
      Result.Err(s"error: template path '$path' not found in $label")
    else
      copyTree(source, destDir)
      Result.unit

  private def topLevelDir(root: Path): Option[Path] =
    Files.list(root).iterator.asScala.toList match
      case single :: Nil if Files.isDirectory(single) => Some(single)
      case _                                          => None

  /** Copies the contents of `source` (a trusted local directory — not zip
   *  entries, so no traversal guard needed here) into `destDir`. Shared with
   *  `LocalTemplateProvider`, which copies straight from a fixture root
   *  rather than an extracted archive.
   */
  def copyTree(source: Path, destDir: Path): Unit =
    Files.createDirectories(destDir)

    val entries = Files.walk(source).iterator.asScala.filterNot(_ == source).toList

    for entry <- entries do
      val target = destDir.resolve(source.relativize(entry).toString)

      if Files.isDirectory(entry) then
        Files.createDirectories(target)
      else
        Files.createDirectories(target.getParent)
        Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING)

  private def deleteRecursively(dir: Path): Unit =
    if Files.exists(dir) then
      Files.walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.delete)
