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
      case Some(dirs) =>
        // Split by colon to support multiple directories
        // User must specify libraries in topological order of dependencies
        val userLibs = dirs.split(":").map(_.trim).filter(_.nonEmpty).toList
        if noStdLib then userLibs else Config.StdLibPath :: userLibs

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
    "-lib"            -> true,   // -lib <dir1:dir2:dir3> (colon-separated, in dependency order)
  )

  // Get the root directory of the project (set by the bin/jo wrapper script)
  private lazy val rootDir: String =
    sys.env.getOrElse("JO_HOME", System.getProperty("user.dir"))

  // Compute paths relative to project root
  lazy val JSRuntimePath: String =
    java.nio.file.Paths.get(rootDir, "sast/runtime/js").toString

  lazy val NativeRuntimePath: String =
    java.nio.file.Paths.get(rootDir, "sast/runtime/native").toString

  lazy val StdLibPath: String =
    java.nio.file.Paths.get(rootDir, "sast/stdlib").toString
