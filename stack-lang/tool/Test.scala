package tool

import java.nio.file.{Files, FileSystems, Path, Paths}
import java.io.{ByteArrayOutputStream, PrintStream}
import scala.jdk.CollectionConverters.*

/** Runs all file-based regression tests for the build tool.
 *  For each .toml input: compares actual output against the paired check file,
 *  or generates the check file if it does not exist yet.
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

  println("=== Build + Run ===")
  failed :::= runBuildTests()
  println()

  if failed.isEmpty then println("All tool tests passed.")
  else
    println(s"FAILED: ${failed.reverse.mkString(" ")}")
    sys.exit(1)

// ---- Build + Run suite -------------------------------------------------------

private def runBuildTests(): List[Path] =
  val joBin = Paths.get("bin/jo").toAbsolutePath()
  if !Files.exists(joBin) then
    println("  skipped: bin/jo not found")
    return Nil

  var failed = List.empty[Path]
  for specFile <- findFiles("tests/tool-build/*/jo.toml") do
    val checkFile = specFile.resolveSibling("jo.check")
    try
      val actual = capture { buildAndRun(specFile, joBin) }
      if !Files.exists(checkFile) then
        Files.writeString(checkFile, actual)
        println(s"  generated: $checkFile")
      else
        val expected = Files.readString(checkFile)
        if actual == expected then
          println(s"  ok: $specFile")
        else
          println(s"FAIL: $specFile")
          diff(expected, actual).foreach(println)
          failed ::= specFile
    catch case e: Exception =>
      println(s"FAIL: $specFile — ${e.getMessage}")
      failed ::= specFile
  failed

private def buildAndRun(specFile: Path, joBin: Path): Unit =
  val plan = Build.makePlan(specFile.toString): constraint =>
    val (_, v) = Version.parseConstraint(constraint)
    (v, joBin)

  // Clean build dir for a reproducible run
  val buildDir = specFile.toAbsolutePath.getParent.resolve(".build")
  if Files.exists(buildDir) then deleteDir(buildDir)

  // Build — suppress [build] log lines so they don't appear in captured output
  val sink = PrintStream(java.io.OutputStream.nullOutputStream())
  Console.withOut(sink)(Runner.run(plan))

  // Execute and emit stdout for capture
  plan.mainPlan match
    case app: CompilePlan.AppPlan => print(captureProc(execArgs(app)))
    case _: CompilePlan.LibPlan   => ()

private def execArgs(app: CompilePlan.AppPlan): List[String] =
  app.target match
    case "python" => List("python3", app.outFile.toString)
    case "js"     => List("node",    app.outFile.toString)
    case "ruby"   => List("ruby",    app.outFile.toString)
    case _        => List(app.outFile.toString)

/** Run a subprocess and return its stdout as a string. */
private def captureProc(args: List[String]): String =
  val proc = ProcessBuilder(args.asJava).start()
  val out  = String(proc.getInputStream.readAllBytes(), "UTF-8")
  val exit = proc.waitFor()
  if exit != 0 then throw ToolError(s"execution failed (exit $exit): ${args.mkString(" ")}")
  out

private def deleteDir(dir: Path): Unit =
  Files.walk(dir)
    .sorted(java.util.Comparator.reverseOrder())
    .forEach(Files.delete)

// ---- Shared helpers ----------------------------------------------------------

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
