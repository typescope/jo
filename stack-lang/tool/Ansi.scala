package tool

object Ansi:
  private val Reset  = "\u001b[0m"
  private val Blue   = "\u001b[34m"
  private val Green  = "\u001b[32m"
  private val Yellow = "\u001b[33m"
  private val Red    = "\u001b[31m"
  private val Dim    = "\u001b[2m"

  val enabled: Boolean =
    sys.env.get("JO_COLOR") match
      case Some("0" | "false" | "False" | "FALSE" | "no" | "No" | "NO") => false
      case _ => true

  def blue(s: String): String =
    color(Blue, s)

  def green(s: String): String =
    color(Green, s)

  def yellow(s: String): String =
    color(Yellow, s)

  def red(s: String): String =
    color(Red, s)

  def dim(s: String): String =
    color(Dim, s)

  private def color(code: String, s: String): String =
    if enabled then code + s + Reset else s
