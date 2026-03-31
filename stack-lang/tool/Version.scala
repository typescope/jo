package tool

/** A semantic version (MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-modifier). */
case class Version(major: Int, minor: Int, patch: Int, modifier: Option[String] = None) extends Ordered[Version]:
  def isPreRelease: Boolean = modifier.isDefined

  def compare(other: Version): Int =
    val c = major.compare(other.major)
    if c != 0 then c
    else
      val c2 = minor.compare(other.minor)
      if c2 != 0 then c2
      else
        val c3 = patch.compare(other.patch)
        if c3 != 0 then c3
        else
          // pre-release < stable: Some(_) < None
          (modifier, other.modifier) match
            case (None, None)       => 0
            case (None, Some(_))    => 1
            case (Some(_), None)    => -1
            case (Some(a), Some(b)) => a.compare(b)

  override def toString =
    modifier match
      case None    => s"$major.$minor.$patch"
      case Some(m) => s"$major.$minor.$patch-$m"

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
    val base = version.copy(modifier = None)
    base.major == required.major && base >= required

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

  /** Parse MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-modifier (modifier: non-empty alphanumeric). */
  def parse(s: String): Option[Version] =
    val dashIdx = s.indexOf('-')
    val (base, modOpt) =
      if dashIdx < 0 then (s, None)
      else (s.take(dashIdx), Some(s.drop(dashIdx + 1)))
    modOpt match
      case Some(mod) if mod.isEmpty || !mod.forall(_.isLetterOrDigit) => None
      case _ =>
        base.split("\\.") match
          case Array(maj, min, pat) =>
            for
              a <- maj.toIntOption
              b <- min.toIntOption
              c <- pat.toIntOption
            yield Version(a, b, c, modOpt)
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
