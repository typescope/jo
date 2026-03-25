package tool

trait Logger:
  def log(msg: String): Unit
  def error(msg: String): Unit

object Logger:
  val stderr: Logger = new Logger:
    def log(msg: String): Unit   = Console.err.print(msg)
    def error(msg: String): Unit = Console.err.print(msg)

  def log(msg: String)(using l: Logger): Unit   = l.log(msg)
  def error(msg: String)(using l: Logger): Unit = l.error(msg)

