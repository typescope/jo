package tool

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object FixtureRepo:
  def rebuild(repoSrc: Path, repoDir: Path): Unit =
    if Files.exists(repoDir) then delete(repoDir)
    Files.createDirectories(repoDir)

    if !Files.isDirectory(repoSrc) then return

    val packages = Files.list(repoSrc).iterator.asScala
      .filter(Files.isDirectory(_))
      .toList
      .sortBy(_.getFileName.toString)

    for pkgDir <- packages do
      val name = pkgDir.getFileName.toString
      val versions = Files.list(pkgDir).iterator.asScala
        .filter(Files.isDirectory(_))
        .toList
        .sortBy(_.getFileName.toString)

      for versionDir <- versions do
        val version = versionDir.getFileName.toString
        val outDir = repoDir.resolve(name).resolve(version)
        val outFile = outDir.resolve(s"$name-v$version.joy")
        Files.createDirectories(outDir)
        JoyArchive.pack(versionDir, outFile)

  def delete(dir: Path): Unit =
    Files.walk(dir)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(Files.delete)
