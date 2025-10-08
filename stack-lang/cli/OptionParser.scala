package cli

import scala.collection.mutable

import reporting.Reporter
import reporting.Config

/** Shared command-line option parsing utilities */
object OptionParser:

  /** Specification for a command-line option */
  enum OptionSpec:
    case Flag                          // Boolean flag (no argument)
    case Single                        // Single-value option (requires one argument)
    case Multi                         // Multi-value option (can appear multiple times)

  class Setting[T]:
    def flag: String
    def spec: OptionSpec
    def default: T
    def desc: String

    def value(using Config): T
    def validate()(using Config, Reporter): Unit = ()

  /** Parse command-line options according to option specs.
    *
    * @param args        Command-line arguments
    * @param optionSpecs Map from option flag to option
    *
    * @return (options map, remaining positional arguments)
    *
    * The values in the map are as follows:
    *
    * - Flag: true
    * - Single: String
    * - Multi: List[String]
    */
  def parseOptions(args: Seq[String], optionSpecs: Map[String, Setting[?]])(using Reporter): (Map[Setting[?], Any], List[String]) =

    val positional = new mutable.ArrayBuffer[String]
    val options = mutable.Map.empty[Setting[?], Any]
    val iter = args.iterator

    def readSetting(setting: Setting[?]): Unit =
      val arg = setting.flag

      setting.spec match
        case OptionSpec.Flag =>
          if options.contains(setting) then
            Reporter.warn(s"Option $arg specified more than once")

          else
            options(setting) = true

        case OptionSpec.Single =>
          if iter.hasNext then
            val value = iter.next()
            if value(0) == '-' then
              Reporter.error(s"Option $arg requires an argument")

            else
              if options.contains(setting) then
                Reporter.warn(s"Option $arg specified more than once")

              else
                options(setting) = value

          else
            Reporter.error(s"Option $arg requires an argument")

        case OptionSpec.Multi =>
          if iter.hasNext then
            val value = iter.next()
            if value(0) == '-' then
              Reporter.error(s"Option $arg requires an argument")

            else
              options.get(setting) match
                case Some(list) =>
                  options(setting) = value :: list.asInstanceOf[List[?]]

                case None =>
                  options(setting) = value :: Nil
          else
            Reporter.error(s"Option $arg requires an argument")

    while iter.hasNext do
      val arg = iter.next()
      if arg(0) != '-' then
        positional += arg

      else
        optionSpecs.get(arg) match
          case None =>
            Reporter.error(s"Unknown option: $arg")

          case Some(setting) =>
            readSetting(setting)
    end while

    (options.toMap, positional.toList)

  def parseConfig(args: Seq[String], options: List[Setting[?]])(using rp: Reporter): (Config, List[String]) =
    var optionSpecs = Map.empty[String, Setting[?]]
    for option <- options do
      if optionSpecs.contains(option.flag) then
        Reporter.abortInternal("Duplicate flag " + option.flag)

      else
        optionSpecs = optionSpecs + (option.flag -> option)
    end for

    val (rawValues, remains) = parseOptions(args, optionSpecs)
    val config = new Config(rawValues)

    // Validate all options
    for option <- options do option.validate()(using config)

    (config, remains)
