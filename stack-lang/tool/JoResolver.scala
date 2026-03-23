package tool

import java.nio.file.{Path, Paths, Files}
import scala.jdk.CollectionConverters.*

/** Resolves a Jo version constraint to a binary path from the local compiler cache.
 *  Cache layout: ~/.jo/cache/compilers/<major>.<minor>.<patch>/jo
 *  Constraint format: operator + MAJOR.MINOR, e.g. ">=1.2".
 *  Picks the highest installed version satisfying the constraint.
 */
object JoResolver:

  /** Returns the build-cache version label for a resolved binary, e.g. "jo-1.2".
   *  Extracts MAJOR.MINOR from the compiler cache directory name (parent of joBin).
   *  Returns None for paths outside the cache (e.g. bare "jo" used in tests).
   */
  def joLabel(joBin: Path): Option[String] =
    Option(joBin.getParent).flatMap: parent =>
      Version.parse(parent.getFileName.toString).map: v =>
        s"jo-${v.major}.${v.minor}"

  def resolve(constraint: String): Path =
    val (op, required) = Version.parseConstraint(constraint)
    val cacheDir = Paths.get(System.getProperty("user.home"), ".jo", "cache", "compilers")

    if !Files.isDirectory(cacheDir) then
      throw ToolError(s"no Jo compilers installed in $cacheDir")

    val candidates = Files.list(cacheDir).iterator.asScala
      .filter(Files.isDirectory(_))
      .flatMap(dir => Version.parse(dir.getFileName.toString).map(v => v -> dir))
      .filter((v, _) => Version.satisfies(v, op, required))
      .toList
      .sortBy(_._1)

    candidates.lastOption match
      case None =>
        throw ToolError(s"no installed Jo compiler satisfies '$constraint' (checked $cacheDir)")
      case Some((_, dir)) =>
        dir.resolve("jo")
