package tool

import java.nio.file.{Path, Paths, Files}
import scala.jdk.CollectionConverters.*

/** Resolves a Jo version constraint to a binary path from the local compiler cache.
 *  Cache layout: ~/.jo/cache/compilers/<major>.<minor>.<patch>/jo
 *  Constraint format: operator + MAJOR.MINOR, e.g. ">=1.2".
 *  Picks the highest installed version satisfying the constraint. */
object JoResolver:

  case class Version(major: Int, minor: Int, patch: Int) extends Ordered[Version]:
    def compare(other: Version): Int =
      val c = major.compare(other.major)
      if c != 0 then c
      else
        val c2 = minor.compare(other.minor)
        if c2 != 0 then c2
        else patch.compare(other.patch)

    override def toString = s"$major.$minor.$patch"

  /** Returns the build-cache version label for a resolved binary, e.g. "jo-1.2".
   *  Extracts MAJOR.MINOR from the compiler cache directory name (parent of joBin).
   *  Returns None for paths outside the cache (e.g. bare "jo" used in tests). */
  def joLabel(joBin: Path): Option[String] =
    Option(joBin.getParent).flatMap: parent =>
      parseVersion(parent.getFileName.toString).map: v =>
        s"jo-${v.major}.${v.minor}"

  def resolve(constraint: String): Path =
    val (op, required) = parseConstraint(constraint)
    val cacheDir = Paths.get(System.getProperty("user.home"), ".jo", "cache", "compilers")

    if !Files.isDirectory(cacheDir) then
      throw ToolError(s"no Jo compilers installed in $cacheDir")

    val candidates = Files.list(cacheDir).iterator.asScala
      .filter(Files.isDirectory(_))
      .flatMap(dir => parseVersion(dir.getFileName.toString).map(v => v -> dir))
      .filter((v, _) => satisfies(v, op, required))
      .toList
      .sortBy(_._1)

    candidates.lastOption match
      case None =>
        throw ToolError(s"no installed Jo compiler satisfies '$constraint' (checked $cacheDir)")
      case Some((_, dir)) =>
        dir.resolve("jo")

  // ---- Helpers ---------------------------------------------------------------

  private def parseConstraint(constraint: String): (String, Version) =
    val ops = List(">=", "<=", ">", "<", "==", "=")
    ops.find(constraint.startsWith) match
      case None =>
        throw ToolError(s"invalid Jo version constraint '$constraint'")
      case Some(op) =>
        val vStr = constraint.drop(op.length).trim
        parseShortVersion(vStr).orElse(parseVersion(vStr)) match
          case None    => throw ToolError(s"invalid version '$vStr' in constraint '$constraint'")
          case Some(v) => (op, v)

  /** Parse MAJOR.MINOR (treats patch as 0). */
  private def parseShortVersion(s: String): Option[Version] =
    s.split("\\.") match
      case Array(maj, min) =>
        for
          a <- maj.toIntOption
          b <- min.toIntOption
        yield Version(a, b, 0)
      case _ => None

  /** Parse MAJOR.MINOR.PATCH (used for installed compiler directories). */
  private def parseVersion(s: String): Option[Version] =
    s.split("\\.") match
      case Array(maj, min, pat) =>
        for
          a <- maj.toIntOption
          b <- min.toIntOption
          c <- pat.toIntOption
        yield Version(a, b, c)
      case _ => None

  private def satisfies(v: Version, op: String, required: Version): Boolean = op match
    case ">="       => v >= required
    case ">"        => v > required
    case "<="       => v <= required
    case "<"        => v < required
    case "=" | "==" => v == required
    case _          => false
