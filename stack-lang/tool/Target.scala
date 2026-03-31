package tool

/** Compilation and execution target. */
enum Target:
  case Python
  case Ruby

  /** Compiler flag, e.g. `--python`. */
  def flag: String = this match
    case Python => "python"
    case Ruby   => "ruby"

  /** Output file extension. */
  def ext: String = this match
    case Python => ".py"
    case Ruby   => ".rb"

  /** Runtime interpreter command. */
  def interpreter: String = this match
    case Python => "python3"
    case Ruby   => "ruby"

object Target:
  def parse(s: String): Option[Target] = s match
    case "python" => Some(Python)
    case "ruby"   => Some(Ruby)
    case _        => None

  val all: List[Target] = List(Python, Ruby)
