package tool

enum LogLevel:
  case Log, Info, Warn, Error

trait Logger:
  def minLevel: LogLevel = LogLevel.Info
  protected def write(msg: String): Unit

  final def log(msg: String): Unit   = if LogLevel.Log.ordinal  >= minLevel.ordinal then write(msg)
  final def info(msg: String): Unit  = if LogLevel.Info.ordinal >= minLevel.ordinal then write(msg)
  final def warn(msg: String): Unit  = if LogLevel.Warn.ordinal >= minLevel.ordinal then write(msg)
  final def error(msg: String): Unit = write(msg)

object Logger:
  def apply(level: LogLevel = LogLevel.Info): Logger = new Logger:
    override val minLevel = level
    protected def write(msg: String) = Console.err.print(msg)

  val stderr: Logger = apply()

  def log(msg: String)(using l: Logger): Unit   = l.log(msg)
  def info(msg: String)(using l: Logger): Unit  = l.info(msg)
  def warn(msg: String)(using l: Logger): Unit  = l.warn(msg)
  def error(msg: String)(using l: Logger): Unit = l.error(msg)
