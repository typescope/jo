package tool

import java.nio.file.{Path, Paths, Files}
import scala.jdk.CollectionConverters.*

/** Resolves a Jo version constraint to a binary path.
 *
 *  Resolution order:
 *  1. Scan ~/.jo/cache/compilers/<major>.<minor>.<patch>/jo and pick
 *     the highest installed version satisfying the constraint.
 *  2. Fall back to JO_HOME/bin/jo (the running compiler) with JoVersion.current
 *     if JO_HOME is set and the current version satisfies the constraint.
 *
 *  Cache layout: ~/.jo/cache/compilers/<major>.<minor>.<patch>/jo
 *  Constraint format: operator + MAJOR.MINOR, e.g. ">=1.2".
 */
object JoResolver:
  def resolve(constraint: VersionSpec): Result[(Version, Path)] =
    // 1. Installed compiler cache
    if Files.isDirectory(Cache.compilers) then
      val candidates = Files.list(Cache.compilers).iterator.asScala
        .filter(Files.isDirectory(_))
        .flatMap(dir => Version.parse(dir.getFileName.toString).map(v => v -> dir))
        .filter((v, _) => constraint.contains(v))
        .toList
        .sortBy(_._1)

      candidates.lastOption match
        case Some((v, dir)) => return Result.Ok((v, dir.resolve("jo")))
        case None =>

    // 2. Fall back to the running dev binary
    val current = JoVersion.current
    if constraint.contains(current) then
      selfBinary() match
        case Some(bin) => return Result.Ok((current, bin))
        case None =>

    Result.Err(s"no Jo compiler satisfies '${constraint.show}'; install one with: jo versions install <version>")

  /** Path to the compiler binary in the development source tree, if available. */
  private def selfBinary(): Option[Path] =
    sys.env.get("JO_HOME").flatMap: home =>
      val bin = Paths.get(home, "bin", "jo")
      if Files.exists(bin) then Some(bin) else None
