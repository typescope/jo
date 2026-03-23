package tool

import java.nio.file.{Files, FileSystems, Path, Paths}
import java.io.{ByteArrayOutputStream, PrintStream}
import scala.jdk.CollectionConverters.*

/** Runs all file-based regression tests for the build tool.
 *  For each .toml input: compares actual output against the paired .txt file,
 *  or generates the .txt file if it does not exist yet.
 */
@main def runTests(): Unit =
  val suites = List(
    ("TOML parser",  "tests/tool-toml/toml/*.toml",          (f: Path) => tool.toml.tomlCheck(f.toString)),
    ("BuildSpec",    "tests/tool-toml/build-spec/*.toml",    (f: Path) => printModel("build-spec", f.toString)),
    ("LockFile",     "tests/tool-toml/lock-file/*.toml",     (f: Path) => printModel("lock-file", f.toString)),
    ("PackageMeta",  "tests/tool-toml/package-meta/*.toml",  (f: Path) => printModel("package-meta", f.toString)),
    ("Graph + Plan", "tests/tool-graph/*/jo.toml",           (f: Path) => printPlan(f.toString)),
  )

  var failed = List.empty[Path]

  for (title, glob, run) <- suites do
    println(s"=== $title ===")
    for file <- findFiles(glob) do
      val txtFile = file.resolveSibling(file.getFileName.toString.stripSuffix(".toml") + ".txt")
      val actual  = capture { run(file) }
      if !Files.exists(txtFile) then
        Files.writeString(txtFile, actual)
        println(s"  generated: $txtFile")
      else
        val expected = Files.readString(txtFile)
        if actual == expected then
          println(s"  ok: $file")
        else
          println(s"FAIL: $file")
          diff(expected, actual).foreach(println)
          failed ::= file
    println()

  if failed.isEmpty then println("All tool tests passed.")
  else
    println(s"FAILED: ${failed.reverse.mkString(" ")}")
    sys.exit(1)

// ---- Helpers ---------------------------------------------------------------

private def findFiles(pattern: String): List[Path] =
  val i       = pattern.indexWhere(c => c == '*' || c == '?')
  val baseDir = Paths.get(pattern.substring(0, pattern.lastIndexOf('/', i)))
  val matcher = FileSystems.getDefault.getPathMatcher(s"glob:$pattern")
  Files.walk(baseDir).iterator.asScala
    .filter(matcher.matches)
    .toList.sortBy(_.toString)

private def capture(f: => Unit): String =
  val buf = ByteArrayOutputStream()
  val ps  = PrintStream(buf, true, "UTF-8")
  Console.withOut(ps)(f)
  buf.toString("UTF-8")

private def diff(expected: String, actual: String): List[String] =
  val exp = expected.linesIterator.toIndexedSeq
  val act = actual.linesIterator.toIndexedSeq
  (0 until (exp.length max act.length)).flatMap: i =>
    (exp.lift(i), act.lift(i)) match
      case (Some(e), Some(a)) if e != a => List(s"< $e", s"> $a")
      case (Some(e), None)              => List(s"< $e")
      case (None, Some(a))              => List(s"> $a")
      case _                            => Nil
  .toList
