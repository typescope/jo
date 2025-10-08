package reporting

import cli.OptionParser.OptionSpec
import cli.OptionParser.Setting

import scala.collection.mutable

enum Mode:
  case Application
  case Library

case class Config(private[Config] rawValues: Map[Setting[?], Any]):
  private val cache: mutable.Map[Setting, Any] = mutable.Map.empty

  def cached[T](key: Setting[T])(computeValue: => T): T =
    cache.get(key) match
      case Some(v) => v.asInstanceOf[T]

      case None =>
        val v = computeValue
        cache(key) = v
        v

object Config:
  class BooleanSetting(val flag: String, val default: Boolean, val desc: String)
  extends Setting[Boolean]:
    def spec = OptionSpec.Flag

    def value(using cf: Config): List[String] = cf.cached(this):
      cf.rawValues.get(this) match
        case Some(v) => true
        case None => default

  class StringSetting(val flag: String, val default: String, val desc: String)
  extends Setting[String]:
    def spec = OptionSpec.Single

    def value(using cf: Config): String =
      cf.rawValues.get(this) match
        case Some(v) => v.asInstanceOf[String]
        case None => default

  class OptionSetting[T] extends Setting[Option[T]]:
    def default = None
    def spec = OptionSpec.Single

  class OptionStringSetting(val flag: String, val desc: String) extends Setting[Option[String]]:
    def value(using cf: Config): Option[String] =
      cf.rawValues.get(this) match
        case Some(v) => Some(v.asInstanceOf[String])
        case None => None

  class CommaListSetting(val flag: String, val desc: String) extends Setting[List[String]]:
    def default = Nil

    def spec = OptionSpec.Single

    def value(using cf: Config): List[String] = cf.cached(this):
      cf.rawValues.get(this) match
        case Some(v) =>
          val raw = v.asInstanceOf[String]
          raw.split(",").map(_.trim).filter(_.nonEmpty).toList

        case None =>
          Nil

  //----------------------------------------------------------------------------

  val printAfter : Setting[List[String]] = CommaListSetting("-printAfter", "print after steps")
  val printOnly  : Setting[List[String]] = CommaListSetting("-printOnly",  "only print specified files")

  val fatalWarnings : Setting[Boolean] = BooleanSetting("-fatal-warnings",  false, "should warnings are fatal")
  val reportTime    : Setting[Boolean] = BooleanSetting("-time",            false, "whether show time report")
  val checkTree     : Setting[Boolean] = BooleanSetting("-checkTree",       false, "whether check tree after a phase")
  val showSteps     : Setting[Boolean] = BooleanSetting("-steps",           false, "whether display steps")
  val testPickling  : Setting[Boolean] = BooleanSetting("-testPickling",    false, "whether test pickling")
  val noStdLib      : Setting[Boolean] = BooleanSetting("-no-stdlib",       false, "whether disable loading stdlib")

  val outFilePath: Setting[Option[String]] = OptionStringSetting("-o", "output file path")

  val targetDir: Setting[String]   = StringSetting("-d", ".",  "target directory for sast")

  object libPaths extends Setting[List[String]]:
    def flag = "-lib"
    def spec = OptionSpec.Single
    def default =  Nil
    def desc = "path to libs in tological order of dependencies"

    override def value(using cf: Config): List[String] = cf.cached(this):
      cf.rawValues.get(this) match
        case Some(v) =>
          val dirs = v.asInstanceOf[String]
          val userLibs = dirs.split(":").map(_.trim).filter(_.nonEmpty).toList
          if Config.noStdLib then userLibs else Config.StdLibPath :: userLibs

        case None =>
          if Config.noStdLib then Nil else Config.StdLibPath :: Nil

    // TODO: validate that the path exists
    override def validate(using Config, Reporter): Unit = ()

  //----------------------------------------------------------------------------

  object linkMap extends Setting[List[String]]:
    def flag = "-link"
    def spec = OptionSpec.Multi
    def defalt =  Nil
    def desc = "e.g., -link stk.Predef.entry=Test.main"

    override def value(using cf: Config): List[String] = cf.cached(this):
      cf.rawValues.get(this) match
        case Some(dirs) =>
          val userLibs = dirs.split(":").map(_.trim).filter(_.nonEmpty).toList
          if Config.noStdLib then userLibs else Config.StdLibPath :: userLibs

        case None =>
          if Config.noStdLib then Nil else Config.StdLibPath :: Nil

    override def validate(using cf: Config, rp: Reporter): Unit =
      val linkArgs = cf.rawValues.getOrElse(this, Nil)

      cf.cached:
        validateLinkMappings(linkArgs)

    /** Validate and convert -link option values to a map.
      *
      * @param linkArgs List of link arguments in "source=target" format
      * @return Map from source path to target path
      */
    def validateLinkMappings(linkArgs: List[String])(using Reporter): Map[String, String] =
      var linkMappings = Map.empty[String, String]

      for linkArg <- linkArgs do
        val parts = linkArg.split("=", 2)

        if parts.length != 2 then
          Reporter.error(s"Error: Invalid -link format: $linkArg. Expected format: source=target")

        else
          val (source, target) = (parts(0), parts(1))

          if !isValidPath(source) then
            Reporter.error(s"Error: Invalid -link source path: $source. Must be a name or dot-separated path")

          else if !isValidPath(target) then
            Reporter.error(s"Error: Invalid -link target path: $target. Must be a name or dot-separated path")

          else if linkMappings.contains(source) then
            Reporter.error(s"Error: Duplicate -link source: $source")

          else
            linkMappings = linkMappings + (source -> target)
        end if
      end for

      linkMappings
  end linkMap

  //----------------------------------------------------------------------------

  /** Check if a string is a valid path (name or dot-separated path).
    *
    * Valid examples: "foo", "foo.bar", "stk.Predef.entry"
    *
    * Invalid examples: "", ".", ".foo", "foo.", "foo..bar", "foo.123", "foo.bar-baz"
    */
  private def isValidPath(path: String): Boolean =
    if path.isEmpty then return false

    val parts = path.split("\\.")

    // Each part must be a valid identifier
    parts.forall(isValidIdentifier)

  /** Check if a string is a valid identifier.
    *
    * Must start with letter or underscore, followed by letters, digits, or underscores.
    */
  private def isValidIdentifier(name: String): Boolean =
    if name.isEmpty then return false

    val first = name.charAt(0)
    if !first.isLetter && first != '_' then return false

    name.tail.forall(c => c.isLetterOrDigit || c == '_')

  //----------------------------------------------------------------------------

  val commonOptions = List(
    printAfter,
    printOnly,
    fatalWarnings,
    reportTime,
    checkTree,
    showSteps,
    testPickling,
    noStdLib,
    libPaths
  )

  val appOptions = outPath :: linkMap :: commonOptions

  //----------------------------------------------------------------------------

  // Get the root directory of the project (set by the bin/jo wrapper script)
  private lazy val rootDir: String =
    sys.env.getOrElse("JO_HOME", System.getProperty("user.dir"))

  // Compute paths relative to project root
  lazy val JSRuntimePath: String =
    java.nio.file.Paths.get(rootDir, "sast/runtime/js").toString

  lazy val NativeRuntimePath: String =
    java.nio.file.Paths.get(rootDir, "sast/runtime/native").toString

  lazy val StdLibPath: String =
    java.nio.file.Paths.get(rootDir, "sast/stdlib").toString
