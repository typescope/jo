package cli

import scala.collection.mutable
import reporting.Mode

/** Shared command-line option parsing utilities */
object OptionParser:

  /** Specification for a command-line option */
  enum OptionSpec:
    case Flag                          // Boolean flag (no argument)
    case Single                        // Single-value option (requires one argument)
    case Multi                         // Multi-value option (can appear multiple times)

  /** Common option specifications shared across all compilers */
  val commonOptions: Map[String, OptionSpec] = Map(
    "-printAfter"     -> OptionSpec.Single,
    "-printOnly"      -> OptionSpec.Single,
    "-fatal-warnings" -> OptionSpec.Flag,
    "-time"           -> OptionSpec.Flag,
    "-checkTree"      -> OptionSpec.Flag,
    "-steps"          -> OptionSpec.Flag,
    "-testPickling"   -> OptionSpec.Flag,
    "-no-stdlib"      -> OptionSpec.Flag,
    "-lib"            -> OptionSpec.Single,
  )

  /** Application-specific options */
  val applicationOptions: Map[String, OptionSpec] = Map(
    "-o"    -> OptionSpec.Single,
    "-link" -> OptionSpec.Multi,
  )

  /** Validate and convert -link option values to a map.
    *
    * @param linkArgs List of link arguments in "source=target" format
    * @return Map from source path to target path
    */
  def validateLinkMappings(linkArgs: List[String]): Map[String, String] =
    var linkMappings = Map.empty[String, String]

    for linkArg <- linkArgs do
      val parts = linkArg.split("=", 2)

      if parts.length != 2 then
        println(s"Error: Invalid -link format: $linkArg. Expected format: source=target")
        System.exit(1)

      val (source, target) = (parts(0), parts(1))

      if !isValidPath(source) then
        println(s"Error: Invalid -link source path: $source. Must be a name or dot-separated path")
        System.exit(1)

      if !isValidPath(target) then
        println(s"Error: Invalid -link target path: $target. Must be a name or dot-separated path")
        System.exit(1)

      if linkMappings.contains(source) then
        println(s"Error: Duplicate -link source: $source")
        System.exit(1)

      linkMappings = linkMappings + (source -> target)

    linkMappings

  /** Check if a string is a valid path (name or dot-separated path).
    * Valid examples: "foo", "foo.bar", "stk.Predef.entry"
    * Invalid examples: "", ".", ".foo", "foo.", "foo..bar", "foo.123", "foo.bar-baz"
    */
  private def isValidPath(path: String): Boolean =
    if path.isEmpty then return false

    val parts = path.split("\\.")

    // Each part must be a valid identifier
    parts.forall(isValidIdentifier)

  /** Check if a string is a valid identifier.
    * Must start with letter or underscore, followed by letters, digits, or underscores.
    */
  private def isValidIdentifier(name: String): Boolean =
    if name.isEmpty then return false

    val first = name.charAt(0)
    if !first.isLetter && first != '_' then return false

    name.tail.forall(c => c.isLetterOrDigit || c == '_')

  /** Parse command-line options according to option specs.
    *
    * @param args Command-line arguments
    * @param optionSpecs Map of option name to option specification
    * @return (options map where each value is a list, remaining positional arguments)
    *         - Flag options: List("") (empty string indicates presence)
    *         - Single options: List(value) (single-element list)
    *         - Multi options: List(value1, value2, ...) (can have multiple elements)
    */
  def parseOptions(args: Seq[String], optionSpecs: Map[String, OptionSpec]):
      (Map[String, List[String]], List[String]) =
    val positional = new mutable.ArrayBuffer[String]
    val options = mutable.Map.empty[String, mutable.ArrayBuffer[String]]
    val iter = args.iterator

    while iter.hasNext do
      val arg = iter.next()
      if arg(0) != '-' then
        positional += arg
      else
        optionSpecs.get(arg) match
          case Some(OptionSpec.Flag) =>
            options(arg) = mutable.ArrayBuffer("")

          case Some(OptionSpec.Single) =>
            if iter.hasNext then
              val value = iter.next()
              if value(0) == '-' then
                throw new Exception(s"Option $arg requires an argument")
              else
                options(arg) = mutable.ArrayBuffer(value)
            else
              throw new Exception(s"Option $arg requires an argument")

          case Some(OptionSpec.Multi) =>
            if iter.hasNext then
              val value = iter.next()
              if value(0) == '-' then
                throw new Exception(s"Option $arg requires an argument")
              else
                if !options.contains(arg) then
                  options(arg) = mutable.ArrayBuffer.empty
                options(arg) += value
            else
              throw new Exception(s"Option $arg requires an argument")

          case None =>
            throw new Exception(s"Unknown option: $arg")
    end while

    (options.view.mapValues(_.toList).toMap, positional.toList)

  case class CompilerOptions(
    config: reporting.Config,
    linkMappings: Map[String, String],
    outFile: Option[String],
    sources: List[String],
    options: Map[String, List[String]]  // Keep for additional options
  )

  /** Parse compiler options and build config.
    *
    * @param args Command-line arguments
    * @param mode Compilation mode (Application or Library)
    * @param additionalOptions Additional option specs beyond common/application options
    * @return CompilerOptions containing config, linkMappings, outFile, sources, and raw options
    */
  def parseCompilerOptions(args: Array[String], mode: Mode, additionalOptions: Map[String, OptionSpec] = Map.empty): CompilerOptions =
    val baseOptions = if mode == Mode.Application then commonOptions ++ applicationOptions else commonOptions
    val allOptions = baseOptions ++ additionalOptions
    val (options, sources) = parseOptions(args, allOptions)

    val config = buildConfig(options, mode)
    val linkMappings = validateLinkMappings(options.getOrElse("-link", Nil))
    val outFile = getOption(options, "-o")

    CompilerOptions(config, linkMappings, outFile, sources, options)

  /** Get first value from option list (for Single and Flag options) */
  def getOption(options: Map[String, List[String]], key: String): Option[String] =
    options.get(key).flatMap(_.headOption)

  /** Check if flag option is present */
  def hasFlag(options: Map[String, List[String]], key: String): Boolean =
    options.contains(key)

  /** Build a Config from parsed options */
  def buildConfig(options: Map[String, List[String]], mode: Mode): reporting.Config =
    val printAfter = getOption(options, "-printAfter").getOrElse("").split(",").map(_.trim).filter(_.nonEmpty).toList
    val printOnly = getOption(options, "-printOnly").getOrElse("").split(",").map(_.trim).filter(_.nonEmpty).toList
    val fatalWarnings = hasFlag(options, "-fatal-warnings")
    val checkTree = hasFlag(options, "-checkTree")
    val reportTime = hasFlag(options, "-time")
    val showSteps = hasFlag(options, "-steps")
    val testPickling = hasFlag(options, "-testPickling")
    val noStdLib = hasFlag(options, "-no-stdlib")

    val libPaths = getOption(options, "-lib") match
      case Some(dirs) =>
        val userLibs = dirs.split(":").map(_.trim).filter(_.nonEmpty).toList
        if noStdLib then userLibs else reporting.Config.StdLibPath :: userLibs
      case None =>
        if noStdLib then Nil else reporting.Config.StdLibPath :: Nil

    reporting.Config(mode, printAfter, printOnly, fatalWarnings, checkTree, reportTime, showSteps, testPickling, noStdLib, libPaths)
