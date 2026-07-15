package tool

import java.io.IOException
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object SourcePaths:
  /** Expand source paths relative to baseDir. Directories include .jo files recursively. */
  def expand(entries: List[String], baseDir: Path): Result[List[Path]] =
    entries.foldLeft(Result.Ok(List.empty[Path]): Result[List[Path]]): (acc, entry) =>
      acc.flatMap: paths =>
        expandEntry(entry, baseDir).map(paths ++ _)
    .map(_.distinct.sorted)

  private def expandEntry(entry: String, baseDir: Path): Result[List[Path]] =
    if hasGlobSyntax(entry) then
      return Result.Err(s"source path '${entry}' uses glob syntax; use a directory or .jo file path")

    val path = baseDir.resolve(entry).normalize()
    if !Files.exists(path) then
      Result.Err(s"source path not found: ${LogFormat.path(path)}")
    else if Files.isDirectory(path) then
      expandDir(path)
    else if Files.isRegularFile(path) then
      if path.getFileName.toString.endsWith(".jo") then Result.Ok(List(path))
      else Result.Err(s"source file must end with .jo: ${LogFormat.path(path)}")
    else
      Result.Err(s"source path is not a file or directory: ${LogFormat.path(path)}")

  private def expandDir(path: Path): Result[List[Path]] =
    try
      val stream = Files.walk(path)
      try
        Result.Ok(
          stream.iterator.asScala
            .filter(Files.isRegularFile(_))
            .filter(_.getFileName.toString.endsWith(".jo"))
            .toList
        )
      finally stream.close()
    catch case e: IOException =>
      Result.Err(s"could not read source directory ${LogFormat.path(path)}: ${e.getMessage}")

  private def hasGlobSyntax(entry: String): Boolean =
    entry.exists(ch => ch == '*' || ch == '?' || ch == '[' || ch == ']' || ch == '{' || ch == '}')
