package reporting

enum Mode:
  case Application
  case Library

case class Config(
  mode: Mode,
  printAfter: List[String],
  printOnly: List[String],
  fatalWarnings: Boolean,
  checkTree: Boolean,
  reportTime: Boolean,
  showSteps: Boolean,
  testPickling: Boolean,
  noStdLib: Boolean,
  libPaths: List[String]
)

object Config:

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
