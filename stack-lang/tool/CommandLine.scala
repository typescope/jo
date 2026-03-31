package tool

object CommandLine:
  enum OptionSpec:
    case Flag
    case Single
    case Multi

  abstract class Setting[T]:
    def flag: String
    def spec: OptionSpec
    def default: T
    def desc: String

  class BooleanSetting(val flag: String, val desc: String) extends Setting[Boolean]:
    def spec = OptionSpec.Flag
    def default = false

  class OptionStringSetting(val flag: String, val desc: String) extends Setting[Option[String]]:
    def spec = OptionSpec.Single
    def default = None

  val verboseOpt = BooleanSetting("--verbose", "show verbose output")

  case class Parsed(
    private val rawValues: Map[Setting[?], Any],
    positional: List[String],
    trailing: List[String],
  ):
    def value[T](setting: Setting[T]): T =
      rawValues.get(setting) match
        case Some(v) => v.asInstanceOf[T]

        case None    => setting.default

  def parse(args: Array[String], settings: List[Setting[?]]): Result[Parsed] =
    val byFlagResult = settings.foldLeft[Result[Map[String, Setting[?]]]](Result.Ok(Map.empty)): (acc, setting) =>
      acc.flatMap: byFlag =>
        if byFlag.contains(setting.flag) then
          Result.Err(s"error: duplicate option definition '${setting.flag}'")
        else
          Result.Ok(byFlag + (setting.flag -> setting))

    byFlagResult.flatMap: byFlag =>
      def loop(
        i: Int,
        rawValues: Map[Setting[?], Any],
        positional: List[String],
      ): Result[Parsed] =
        if i >= args.length then
          Result.Ok(Parsed(rawValues, positional.reverse, Nil))
        else
          val arg = args(i)

          if arg == "--" then
            Result.Ok(Parsed(rawValues, positional.reverse, args.drop(i + 1).toList))

          else if arg.startsWith("-") then
            val eqIdx = arg.indexOf('=')
            val (flag, inlineValue) =
              if eqIdx >= 0 then (arg.take(eqIdx), Some(arg.drop(eqIdx + 1)))
              else (arg, None)

            byFlag.get(flag) match
              case None =>
                Result.Err(s"error: unknown option '$arg'")

              case Some(setting) =>
                setting.spec match
                  case OptionSpec.Flag =>
                    if inlineValue.isDefined then
                      Result.Err(s"error: option '$flag' does not take an argument")
                    else
                      loop(i + 1, rawValues + (setting -> true), positional)

                  case OptionSpec.Single =>
                    val valueResult = inlineValue match
                      case Some(v) =>
                        if v.isEmpty then Result.Err(s"error: option '$flag' requires an argument")
                        else Result.Ok(v)

                      case None =>
                        if i + 1 >= args.length || args(i + 1).startsWith("-") then
                          Result.Err(s"error: option '$flag' requires an argument")
                        else
                          Result.Ok(args(i + 1))

                    valueResult.flatMap: value =>
                      loop(i + (if inlineValue.isDefined then 1 else 2), rawValues + (setting -> Some(value)), positional)

                  case OptionSpec.Multi =>
                    Result.Err(s"error: unsupported option kind for '$flag'")

          else
            loop(i + 1, rawValues, arg :: positional)

      loop(0, Map.empty, Nil)
