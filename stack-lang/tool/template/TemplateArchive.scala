package tool.template

import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*

import tool.{ArchiveError, JoyArchive, Result}

/** Extracts a template subtree out of a downloaded repo archive.
 *
 *  Kept independent of how the zip was obtained (no HTTP here) so it can be
 *  unit-tested directly against a hand-built zip fixture.
 */
object TemplateArchive:
  /** Extracts `path` from the archive at `zipFile` into `destDir`.
   *
   *  Archives are expected to contain exactly one top-level directory (as
   *  GitHub/GitLab/Gitea archive endpoints always produce) — its name isn't
   *  predictable ahead of time, so it's discovered rather than assumed.
   *  Reuses `JoyArchive.unpack` for the actual unzipping, including its
   *  zip-slip guard.
   */
  def extract(zipFile: Path, path: String, destDir: Path, label: String): Result[Unit] =
    val tempDir = Files.createTempDirectory("jo-template-")

    try
      JoyArchive.unpack(zipFile, tempDir)

      topLevelDir(tempDir) match
        case None =>
          Result.Err(s"error: unexpected archive structure for $label (expected exactly one top-level directory)")

        case Some(root) =>
          val source = if path == "." then root else root.resolve(path).normalize()

          if !source.startsWith(root) || !Files.isDirectory(source) then
            Result.Err(s"error: template path '$path' not found in $label")

          else
            copyTree(source, destDir)
            Result.unit

    catch
      case e: ArchiveError => Result.Err(s"error: ${e.message}")

    finally
      deleteRecursively(tempDir)

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
