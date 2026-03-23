package tool

/** A semantic version (MAJOR.MINOR.PATCH) used by installed compiler directories. */
case class Version(major: Int, minor: Int, patch: Int) extends Ordered[Version]:
  def compare(other: Version): Int =
    val c = major.compare(other.major)
    if c != 0 then c
    else
      val c2 = minor.compare(other.minor)
      if c2 != 0 then c2
      else patch.compare(other.patch)

  override def toString = s"$major.$minor.$patch"

object Version:
  /** Parse MAJOR.MINOR.PATCH. */
  def parse(s: String): Option[Version] =
    s.split("\\.") match
      case Array(maj, min, pat) =>
        for
          a <- maj.toIntOption
          b <- min.toIntOption
          c <- pat.toIntOption
        yield Version(a, b, c)
      case _ => None

  /** Parse MAJOR.MINOR constraint version (treats patch as 0). */
  def parseShort(s: String): Option[Version] =
    s.split("\\.") match
      case Array(maj, min) =>
        for
          a <- maj.toIntOption
          b <- min.toIntOption
        yield Version(a, b, 0)
      case _ => None

  /** Parse a version constraint string, e.g. ">=1.2" or "=1.2.3".
   *  Returns (operator, version) or throws ToolError.
   */
  def parseConstraint(constraint: String): (String, Version) =
    val ops = List(">=", "<=", ">", "<", "==", "=")
    ops.find(constraint.startsWith) match
      case None =>
        throw ToolError(s"invalid version constraint '$constraint'")
      case Some(op) =>
        val vStr = constraint.drop(op.length).trim
        parseShort(vStr).orElse(parse(vStr)) match
          case None    => throw ToolError(s"invalid version '$vStr' in constraint '$constraint'")
          case Some(v) => (op, v)

  /** Returns true if v satisfies the constraint (op, required). */
  def satisfies(v: Version, op: String, required: Version): Boolean = op match
    case ">="       => v >= required
    case ">"        => v > required
    case "<="       => v <= required
    case "<"        => v < required
    case "=" | "==" => v == required
    case _          => false
