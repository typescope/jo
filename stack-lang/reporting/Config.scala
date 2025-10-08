package reporting

import cli.OptionParser.OptionSpec
import cli.OptionParser.Setting

import Config.InternalSetting

import scala.collection.mutable

case class Config(private[Config] rawValues: Map[Setting[?], Any]):
  private val cache: mutable.Map[Setting[?], Any] = mutable.Map.empty

  def cached[T](key: Setting[T])(computeValue: => T): T =
    cache.get(key) match
      case Some(v) => v.asInstanceOf[T]

      case None =>
        val v = computeValue
        cache(key) = v
        v

  def setInternal[T](key: InternalSetting[T], v: T): Unit =
    assert(!cache.contains(key), "key already set: " + key.desc)
    cache(key) = v

object Config:
  enum Mode:
    case Application
    case Library


  class BooleanSetting(val flag: String, val default: Boolean, val desc: String)
  extends Setting[Boolean]:
    def spec = OptionSpec.Flag

    def value(using cf: Config): Boolean =
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

  abstract class OptionSetting[T] extends Setting[Option[T]]:
    def default = None
    def spec = OptionSpec.Single

  class OptionStringSetting(val flag: String, val desc: String) extends OptionSetting[String]:
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

  class InternalSetting[T](val default: T, val desc: String) extends Setting[T]:
    def flag = throw new Exception("not command-line option")
    def spec = OptionSpec.Single

    def value(using cf: Config): T = cf.cached(this):
      default

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

  /** Mode of compilation --- not a command line setting */
  val mode: InternalSetting[Mode] = InternalSetting[Mode](Mode.Library, "mode of compilation")

  object libPaths extends Setting[List[String]]:
    def flag = "-lib"
    def spec = OptionSpec.Single
    def default =  Nil
    def desc = "path to libs in tological order of dependencies"

    def value(using cf: Config): List[String] = cf.cached(this):
      cf.rawValues.get(this) match
        case Some(v) =>
          val dirs = v.asInstanceOf[String]
          val userLibs = dirs.split(":").map(_.trim).filter(_.nonEmpty).toList
          if Config.noStdLib.value then userLibs else Config.StdLibPath :: userLibs

        case None =>
          if Config.noStdLib.value then Nil else Config.StdLibPath :: Nil

    // TODO: validate that the path exists
    override def validate()(using Config, Reporter): Unit = ()

  //----------------------------------------------------------------------------

  object linkMap extends Setting[Map[String, String]]:
    def flag = "-link"
    def spec = OptionSpec.Multi
    def default = Map.empty[String, String]
    def desc = "e.g., -link stk.Predef.entry=Test.main"

    override def value(using cf: Config): Map[String, String] = cf.cached(this):
      throw new Exception("validation of options not performed")

    override def validate()(using cf: Config, rp: Reporter): Unit =
      val linkArgs = cf.rawValues.get(this) match
        case Some(args: List[?]) => args.asInstanceOf[List[String]]
        case _ => Nil

      val validatedMap = validateLinkMappings(linkArgs)

      // Cache the validated result
      cf.cache(this) = validatedMap

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

    /** Check for conflicts between user-supplied link mappings and compiler defaults.
      *
      * @param defaultMappings Compiler-defined mappings
      * @return Combined mappings with user mappings taking precedence
      */
    def addDefault(defaultMappings: Map[String, String])(using Config, Reporter): Map[String, String] =
      val userMappings = this.value
      for (source, userTarget) <- userMappings do
        defaultMappings.get(source) match
          case Some(defaultTarget) if defaultTarget != userTarget =>
            Reporter.warn(s"User-supplied link mapping ignored due to conflicts with compiler default: $source=$userTarget (was $source=$defaultTarget)")
          case _ =>
      end for

      defaultMappings ++ userMappings
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

  val appOptions = outFilePath :: linkMap :: commonOptions

  //----------------------------------------------------------------------------

  // Get the root directory of the project (set by the bin/jo wrapper script)
  private lazy val rootDir: String =
    sys.env.getOrElse("JO_HOME", System.getProperty("user.dir"))

  // Compute paths relative to project root
  lazy val JSRuntimePath: String =
    java.nio.file.Paths.get(rootDir, "sast/runtime/js").toString

  lazy val NativeRuntimePath: String =
    java.nio.file.Paths.get(rootDir, "sast/runtime/native").toString

  lazy val InterpreterRuntimePath: String =
    java.nio.file.Paths.get(rootDir, "sast/runtime/interpreter").toString

  lazy val StdLibPath: String =
    java.nio.file.Paths.get(rootDir, "sast/stdlib").toString
