package reporting

enum Mode:
  case Application
  case Library

class Config(options: Map[String, String], val mode: Mode):
  val printAfter: List[String] = options.getOrElse("-printAfter", "").split(",").map(_.trim).toList

  val printOnly: List[String] = options.getOrElse("-printOnly", "").split(",").map(_.trim).toList

  val fatalWarnings: Boolean = options.contains("-fatal-warnings")

  val checkTree: Boolean = options.contains("-checkTree")

  val reportTime: Boolean = options.contains("-time")

  val showSteps: Boolean = options.contains("-steps")

  val testPickling: Boolean = options.contains("-testPickling")

  val noStdLib: Boolean = options.contains("-no-stdlib")

  val libPaths: List[String] =
    options.get("-lib") match
      case Some(dir) =>
        Config.StdLibPath :: dir :: Nil

      case None =>
        if noStdLib then Nil else Config.StdLibPath :: Nil

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
    "-no-stdlib"      -> false,
    "-lib"            -> true,   // -lib <dir>
  )

  val JSRuntimePath = "out/runtime/js"
  val NativeRuntimePath = "out/native/js"
  val StdLibPath = "out/stdlib"
