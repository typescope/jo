package tool

import java.io.{BufferedInputStream, BufferedOutputStream}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}
import scala.jdk.CollectionConverters.*

case class ArchiveError(message: String) extends Exception(message)

object JoyArchive:
  def pack(inputDir: Path, archiveFile: Path): Unit =
    Files.createDirectories(archiveFile.getParent)

    val files = Files.walk(inputDir).iterator.asScala
      .filter(Files.isRegularFile(_))
      .toList
      .sortBy(p => inputDir.relativize(p).toString)

    val out = ZipOutputStream(BufferedOutputStream(Files.newOutputStream(archiveFile)))

    try
      for file <- files do
        val rel = normalizeEntryName(inputDir.relativize(file).toString)
        val entry = ZipEntry(rel)
        entry.setTime(Files.getLastModifiedTime(file).toMillis)
        out.putNextEntry(entry)
        Files.copy(file, out)
        out.closeEntry()

    finally out.close()

  def unpack(archiveFile: Path, outputDir: Path): Unit =
    Files.createDirectories(outputDir)

    val in = ZipInputStream(BufferedInputStream(Files.newInputStream(archiveFile)))

    try
      var entry = in.getNextEntry()

      while entry != null do
        val name = normalizeEntryName(entry.getName)
        val target = outputDir.resolve(name).normalize()

        if !target.startsWith(outputDir) then
          throw ArchiveError(s"invalid archive entry '$name'")

        if entry.isDirectory then
          Files.createDirectories(target)
        else
          Files.createDirectories(target.getParent)
          Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING)

        in.closeEntry()
        entry = in.getNextEntry()

    finally in.close()

  private def normalizeEntryName(path: String): String =
    path.replace('\\', '/')
