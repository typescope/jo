package reporting

class Config(options: Map[String, String]):
  val printAfter: List[String] = options.getOrElse("-printAfter", "").split(",").map(_.trim).toList

  val fatalWarnings: Boolean = options.contains("-fatal-warnings")

  val checkTree: Boolean = options.contains("-checkTree")

  val reportTime: Boolean = options.contains("-reportTime")

object Config:
  // The flag tells whether the option needs an argument
  val commonOptionsSpec = Map(
    "-printAfter"     -> true,
    "-fatal-warnings" -> false,
    "-reportTime"     -> false,
    "-checkTree"      -> false,
  )
