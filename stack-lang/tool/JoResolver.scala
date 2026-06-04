package tool

import java.nio.file.{Path, Paths, Files}

/** Resolves a Jo version constraint to the running compiler binary.
 *
 *  Uses the running compiler if its version satisfies the constraint.
 *  Errors explicitly if not — the user must switch via `jo versions use`.
 *
 *  Constraint format: MAJOR.MINOR, e.g. "1.2".
 */
object JoResolver:
  def resolve(constraint: VersionSpec): Result[(Version, Path)] =
    val current = JoVersion.current
    if !constraint.contains(current) then
      return Result.Err(
        s"this project requires Jo ${constraint.show} but the active version is $current\n" +
        s"  Run: jo versions use <version>"
      )
    selfBinary() match
      case Some(bin) => Result.Ok((current, bin))
      case None      => Result.Err(s"could not locate the running compiler binary (JO_HOME not set)")

  def resolveExact(version: Version): Result[Path] =
    val current = JoVersion.current
    if current != version then
      return Result.Err(
        s"this project is locked to Jo $version but the active version is $current\n" +
        s"  Run: jo versions use $version"
      )
    selfBinary() match
      case Some(bin) => Result.Ok(bin)
      case None      => Result.Err(s"could not locate the running compiler binary (JO_HOME not set)")

  private def selfBinary(): Option[Path] =
    sys.env.get("JO_HOME").flatMap: home =>
      val bin = Paths.get(home, "bin", "jo")
      if Files.exists(bin) then Some(bin) else None
