package tool

import java.nio.file.{Path, Paths}

enum LogLevel:
  case Log, Info, Warn, Error

trait Logger:
  def minLevel: LogLevel = LogLevel.Info

  /** Logical working directory of the command whose output this logger renders.
   *
   *  Implementations must keep this value stable for the logger's lifetime.
   *  It is used only to relativize user-facing paths. It does not change the
   *  process working directory or affect filesystem operations.
   */
  protected def cwd: Path = Paths.get("").toAbsolutePath

  def relativize(path: Path): String =
    val absolute = path.toAbsolutePath.normalize()
    val base = cwd.toAbsolutePath.normalize()
    if absolute.startsWith(base) then base.relativize(absolute).toString
    else absolute.toString
  protected def write(msg: String, level: LogLevel): Unit

  final def log(msg: String): Unit   = if LogLevel.Log.ordinal  >= minLevel.ordinal then write(msg, LogLevel.Log)
  final def info(msg: String): Unit  = if LogLevel.Info.ordinal >= minLevel.ordinal then write(msg, LogLevel.Info)
  final def warn(msg: String): Unit  = if LogLevel.Warn.ordinal >= minLevel.ordinal then write(msg, LogLevel.Warn)
  final def error(msg: String): Unit = write(msg, LogLevel.Error)

object Logger:
  def apply(level: LogLevel = LogLevel.Info, workingDir: Path = Paths.get("").toAbsolutePath): Logger = new Logger:
    override val minLevel = level
    override protected val cwd = workingDir
    protected def write(msg: String, level: LogLevel) =
      Console.err.print(colorize(msg, level))

  val stderr: Logger = apply()

  def log(msg: String)(using l: Logger): Unit   = l.log(msg)
  def info(msg: String)(using l: Logger): Unit  = l.info(msg)
  def warn(msg: String)(using l: Logger): Unit  = l.warn(msg)
  def error(msg: String)(using l: Logger): Unit = l.error(msg)
  def relativize(path: Path)(using l: Logger): String = l.relativize(path)

  private def colorize(msg: String, level: LogLevel): String =
    if !Ansi.enabled then msg
    else msg.linesWithSeparators.map(colorizeLine(_, level)).mkString

  private def colorizeLine(line: String, level: LogLevel): String =
    if line.startsWith("[") then
      val end = line.indexOf(']')
      if end >= 0 then
        val label = line.substring(0, end + 1)
        val rest = line.substring(end + 1)
        colorForLabel(label, level)(label) + colorForRest(label)(rest)
      else colorForLevel(level)(line)
    else if line.startsWith("warning:") then
      Ansi.yellow(line)
    else if line.startsWith("error:") then
      Ansi.red(line)
    else
      line

  private def colorForLabel(label: String, level: LogLevel): String => String = label match
    case "[build]" | "[check]" | "[doc]" | "[run]" | "[package]" => Ansi.blue
    case "[output]" | "[artifact]" | "[clean]" => Ansi.green
    case "[cmd]" => Ansi.dim
    case _ => colorForLevel(level)

  private def colorForRest(label: String): String => String = label match
    case "[build]" | "[check]" | "[doc]" | "[run]" | "[package]" => Ansi.dim
    case "[output]" | "[artifact]" | "[clean]" => Ansi.dim
    case "[cmd]" => Ansi.dim
    case _ => identity

  private def colorForLevel(level: LogLevel): String => String = level match
    case LogLevel.Log => Ansi.dim
    case LogLevel.Info => Ansi.blue
    case LogLevel.Warn => Ansi.yellow
    case LogLevel.Error => Ansi.red
