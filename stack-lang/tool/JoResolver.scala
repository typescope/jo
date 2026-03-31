package tool

import java.nio.file.{Path, Paths, Files}
import scala.jdk.CollectionConverters.*

/** Resolves a Jo version constraint to a binary path.
 *
 *  Resolution order:
 *  1. Scan ~/.jo/compilers/<major>.<minor>.<patch>/bin/jo and pick
 *     the highest installed version satisfying the constraint.
 *  2. Fall back to JO_HOME/bin/jo (the running compiler) with JoVersion.current
 *     if JO_HOME is set and the current version satisfies the constraint.
 *
 *  Install layout: ~/.jo/compilers/<major>.<minor>.<patch>/bin/jo
 *  Constraint format: MAJOR.MINOR, e.g. "1.2".
 */
object JoResolver:
  def resolve(constraint: VersionSpec): Result[(Version, Path)] =
    installedCompilers.filter((v, _) => constraint.contains(v)).lastOption match
      case Some((v, dir)) => return Result.Ok((v, compilerBin(dir)))
      case None =>

    // 2. Fall back to the running dev binary
    val current = JoVersion.current
    if constraint.contains(current) then
      selfBinary() match
        case Some(bin) => return Result.Ok((current, bin))
        case None =>

    Result.Err(s"no Jo compiler satisfies '${constraint.show}'; install one under ~/.jo/compilers/<version>/")

  def resolveExact(version: Version): Result[Path] =
    installedCompilers.find(_._1 == version) match
      case Some((_, dir)) =>
        Result.Ok(compilerBin(dir))

      case None =>
        val current = JoVersion.current
        if current == version then
          selfBinary() match
            case Some(bin) => Result.Ok(bin)
            case None      => Result.Err(s"locked Jo compiler $version is not installed")
        else
          Result.Err(s"locked Jo compiler $version is not installed")

  /** Path to the compiler binary in the development source tree, if available. */
  private def selfBinary(): Option[Path] =
    sys.env.get("JO_HOME").flatMap: home =>
      val bin = Paths.get(home, "bin", "jo")
      if Files.exists(bin) then Some(bin) else None

  private def installedCompilers: List[(Version, Path)] =
    if !Files.isDirectory(Config.compilers) then Nil
    else
      Files.list(Config.compilers).iterator.asScala
        .filter(Files.isDirectory(_))
        .flatMap(dir => Version.parse(dir.getFileName.toString).map(v => v -> dir))
        .toList
        .sortBy(_._1)

  private def compilerBin(dir: Path): Path =
    dir.resolve("bin").resolve("jo")
