package reporting

class Config(options: Map[String, String]):
  val printAfter: List[String] = options.getOrElse("-print", "").split(",").map(_.trim).toList

  val fatalWarnings: Boolean = options.contains("-fatal-warnings")

  val checkTree: Boolean = options.contains("-checkTree")

  val reportTime: Boolean = options.contains("-time")

  val showSteps: Boolean = options.contains("-showSteps")

object Config:
  // The flag tells whether the option needs an argument
  val commonOptionsSpec = Map(
    "-print"          -> true,
    "-fatal-warnings" -> false,
    "-time"           -> false,
    "-checkTree"      -> false,
    "-showSteps"      -> false,
  )
