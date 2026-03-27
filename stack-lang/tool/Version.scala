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

/** A Jo/package compatibility requirement written as `MAJOR.MINOR`.
 *
 *  `VersionSpec(Version(1, 2, 0))` means:
 *  - stay within major line `1`
 *  - require version `>= 1.2.0`
 *  - prefer the latest compatible concrete release in that line
 */
case class VersionSpec(required: Version):
  def show: String =
    Version.showShort(required)

  def contains(version: Version): Boolean =
    version.major == required.major && version >= required

  def minimumVersion: Version =
    required

object VersionSpec:
  def parse(input: String): Either[String, VersionSpec] =
    val clause = input.trim
    if clause.isEmpty then
      return Left(s"invalid version constraint '$input'")

    if clause.contains(",") then
      return Left(s"invalid version constraint '$input': only a single compatibility line is supported")

    Version.parseShort(clause) match
      case Some(required) => Right(VersionSpec(required))
      case None           => Left(s"invalid version '$clause' in constraint '$input'")

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

  def showShort(version: Version): String =
    s"${version.major}.${version.minor}"
