package tool

import java.nio.file.{Path, Paths, Files}
import scala.jdk.CollectionConverters.*

/** Resolves a Jo version constraint to a binary path from the local compiler cache.
 *  Cache layout: ~/.jo/cache/compilers/<major>.<minor>.<patch>/jo
 *  Constraint format: operator + MAJOR.MINOR, e.g. ">=1.2".
 *  Picks the highest installed version satisfying the constraint.
 */
object JoResolver:
  def resolve(constraint: VersionSpec): Result[(Version, Path)] =
    val cacheDir = Paths.get(System.getProperty("user.home"), ".jo", "cache", "compilers")

    if !Files.isDirectory(cacheDir) then
      return Result.Err(s"no Jo compilers installed in $cacheDir")

    val candidates = Files.list(cacheDir).iterator.asScala
      .filter(Files.isDirectory(_))
      .flatMap(dir => Version.parse(dir.getFileName.toString).map(v => v -> dir))
      .filter((v, _) => constraint.contains(v))
      .toList
      .sortBy(_._1)

    candidates.lastOption match
      case None =>
        Result.Err(s"no installed Jo compiler satisfies '${constraint.show}' (checked $cacheDir)")
      case Some((v, dir)) =>
        Result.Ok((v, dir.resolve("jo")))
