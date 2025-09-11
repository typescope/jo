package reporting

class Config(options: Map[String, String]):
  val printAfter: List[String] = options.getOrElse("-printAfter", "").split(",").map(_.trim).toList

  val printOnly: List[String] = options.getOrElse("-printOnly", "").split(",").map(_.trim).toList

  val fatalWarnings: Boolean = options.contains("-fatal-warnings")

  val checkTree: Boolean = options.contains("-checkTree")

  val reportTime: Boolean = options.contains("-time")

  val showSteps: Boolean = options.contains("-steps")

  val testPickling: Boolean = options.contains("-testPickling")

object Config:
  // The flag tells whether the option needs an argument
  val commonOptionsSpec = Map(
    "-printAfter"     -> true,   // -printAfter Parser,Namer,PatternMatcher
    "-printOnly"      -> true,   // -printOnly  skt.Predef
    "-fatal-warnings" -> false,
    "-time"           -> false,
    "-checkTree"      -> false,
    "-steps"          -> false,
    "-testPickling"   -> false,
  )
