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

enum VersionSpec:
  case Ge(required: Version)
  case Gt(required: Version)
  case Le(required: Version)
  case Lt(required: Version)
  case Eq(required: Version)
  case Caret(lower: Version)
  case Tilde(lower: Version)

  def show: String = this match
    case Ge(required)    => s">=${Version.showShort(required)}"
    case Gt(required)    => s">${Version.showShort(required)}"
    case Le(required)    => s"<=${Version.showShort(required)}"
    case Lt(required)    => s"<${Version.showShort(required)}"
    case Eq(required)    => s"=${Version.showShort(required)}"
    case Caret(lower)    => s"^${Version.showShort(lower)}"
    case Tilde(lower)    => s"~${Version.showShort(lower)}"

  def contains(version: Version): Boolean = this match
    case Ge(required) => version >= required
    case Gt(required) => version > required
    case Le(required) => version <= required
    case Lt(required) => version < required
    case Eq(required) => version == required
    case Caret(lower) =>
      val upper = Version(lower.major + 1, 0, 0)
      version >= lower && version < upper
    case Tilde(lower) =>
      val upper = Version(lower.major, lower.minor + 1, 0)
      version >= lower && version < upper

  def minimumVersion: Version = this match
    case Ge(required) => required
    case Gt(required) => required
    case Eq(required) => required
    case Caret(lower) => lower
    case Tilde(lower) => lower
    case Le(required) => required
    case Lt(required) => required

object VersionSpec:
  def parse(input: String): Either[String, VersionSpec] =
    val clause = input.trim
    if clause.isEmpty then return Left(s"invalid version constraint '$input'")
    if clause.contains(",") then
      return Left(s"invalid version constraint '$input': only a single constraint is supported")

    if clause.startsWith("^") then
      Version.parseShort(clause.drop(1).trim) match
        case Some(lower) => Right(VersionSpec.Caret(lower))
        case None        => Left(s"invalid version '${clause.drop(1).trim}' in constraint '$input'")
    else if clause.startsWith("~") then
      Version.parseShort(clause.drop(1).trim) match
        case Some(lower) => Right(VersionSpec.Tilde(lower))
        case None        => Left(s"invalid version '${clause.drop(1).trim}' in constraint '$input'")
    else
      Version.parseConstraint(clause).map:
        case (">=", required)      => VersionSpec.Ge(required)
        case (">", required)       => VersionSpec.Gt(required)
        case ("<=", required)      => VersionSpec.Le(required)
        case ("<", required)       => VersionSpec.Lt(required)
        case ("=" | "==", required) => VersionSpec.Eq(required)
        case (op, _)               => throw IllegalStateException(s"unsupported operator '$op'")

object Version:
  val current: Version = Version(0, 10, 0)

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

  /** Parse a simple version constraint string, e.g. ">=1.2". */
  def parseConstraint(constraint: String): Either[String, (String, Version)] =
    val ops = List(">=", "<=", ">", "<", "==", "=")
    ops.find(constraint.startsWith) match
      case None =>
        Left(s"invalid version constraint '$constraint'")
      case Some(op) =>
        val vStr = constraint.drop(op.length).trim
        parseShort(vStr) match
          case None    => Left(s"invalid version '$vStr' in constraint '$constraint'")
          case Some(v) => Right((op, v))

  def showShort(version: Version): String =
    s"${version.major}.${version.minor}"
