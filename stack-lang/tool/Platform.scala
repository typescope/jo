package tool

/** Build-spec and package platform. */
enum Platform:
  case Pure
  case Python
  case Ruby

  def value: String = this match
    case Pure   => "pure"
    case Python => "python"
    case Ruby   => "ruby"

  def target: Option[Target] = this match
    case Pure   => None
    case Python => Some(Target.Python)
    case Ruby   => Some(Target.Ruby)

object Platform:
  def parse(s: String): Option[Platform] = s match
    case "pure"   => Some(Pure)
    case "python" => Some(Python)
    case "ruby"   => Some(Ruby)
    case _        => None

  val all: List[Platform] = List(Pure, Python, Ruby)
