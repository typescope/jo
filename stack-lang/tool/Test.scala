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

// ---- Build suite -------------------------------------------------------------

/** Each test project must have a jo.steps file (see parseSteps for format). */
private def runBuildTests(): List[Path] =
  val joBin = Paths.get("bin/jo").toAbsolutePath()
  if !Files.exists(joBin) then
    println("  skipped: bin/jo not found")
    return Nil

  var failed = List.empty[Path]
  for specFile <- findFiles("tests/tool-build/*/jo.toml") do
    val stepsFile = specFile.resolveSibling("jo.steps")
    if Files.exists(stepsFile) then
      failed :::= runStepsFile(stepsFile, specFile, joBin)
    else
      println(s"  skipped: no jo.steps in ${specFile.getParent.getFileName}")
  failed

// ---- jo.steps DSL ------------------------------------------------------------

/** A group of commands whose combined stdout may be checked.
 *  expected = None  → run for side effects only (exit 0 required)
 *  expected = Some  → compare combined stdout to expected string */
private case class Step(cmds: List[String], expected: Option[String])

/** Parse a jo.steps file into a list of Steps.
 *
 *  Format (also a valid bash script):
 *    - Non-empty, non-comment lines are commands
 *    - Lines starting with `#` are comments
 *    - `: '` opens an expected-output block; a lone `'` closes it
 *      (null-command string literal in bash — content is taken literally)
 *    - Commands before a `: '` block belong to that step
 *    - Commands without a following `: '` form a step with no expected output
 */
private def parseSteps(content: String): List[Step] =
  val lines  = content.linesIterator.toList
  val steps  = collection.mutable.ListBuffer.empty[Step]
  var cmds   = List.empty[String]
  var i      = 0

  while i < lines.length do
    val line = lines(i)
    if line == ": '" then
      i += 1
      val buf = collection.mutable.ListBuffer.empty[String]
      while i < lines.length && lines(i) != "'" do
        buf += lines(i)
        i += 1
      if i < lines.length then i += 1   // skip closing '
      steps += Step(cmds.reverse, Some(buf.mkString("\n") + "\n"))
      cmds = Nil
    else if line.trim.isEmpty || line.startsWith("#") then
      i += 1
    else
      cmds = line :: cmds
      i += 1

  if cmds.nonEmpty then steps += Step(cmds.reverse, None)
  steps.toList

private def runStepsFile(stepsFile: Path, specFile: Path, joBin: Path): List[Path] =
  val specDir = specFile.getParent
  val steps   = parseSteps(Files.readString(stepsFile))
  var failed  = List.empty[Path]

  // Clean once before the whole scenario
  val buildDir = specDir.resolve(".build")
  if Files.exists(buildDir) then deleteDir(buildDir)

  for step <- steps do
    try
      var stepOk = true
      val actual = step.cmds.map: cmd =>
        if cmd.startsWith("jo ") then
          val (out, ok) = captureResult { runJoCmd(cmd.drop(3).trim, specFile, joBin) }
          if !ok then stepOk = false
          out
        else
          runShellCmd(cmd, specDir)
      .mkString

      step.expected match
        case None =>
          if !stepOk then
            println(s"FAIL: $stepsFile [${step.cmds.mkString("; ")}]")
            failed ::= stepsFile
        case Some(expected) =>
          if actual == expected then
            println(s"  ok: $stepsFile [${step.cmds.mkString("; ")}]")
          else
            println(s"FAIL: $stepsFile [${step.cmds.mkString("; ")}]")
            diff(expected, actual).foreach(println)
            failed ::= stepsFile
    catch case e: Exception =>
      println(s"FAIL: $stepsFile [${step.cmds.mkString("; ")}] — ${e.getMessage}")
      failed ::= stepsFile

  failed

// ---- Command runners ---------------------------------------------------------

private def runJoCmd(subcmd: String, specFile: Path, joBin: Path): Unit =
  val plan = Build.makePlan(specFile.toString): constraint =>
    val (_, v) = Version.parseConstraint(constraint)
    (v, joBin)

  subcmd match
    case "run" =>
      Runner.run(plan)
      plan.mainPlan match
        case app: CompilePlan.AppPlan => Runner.execute(app, Nil)
        case _: CompilePlan.LibPlan   => ()

    case "test" =>
      Runner.buildForTest(plan) match
        case None     => print("no tests defined\n")
        case Some(tp) => Runner.execute(tp, Nil)

    case "build" | "check" =>
      val run = if subcmd == "build" then Runner.run else Runner.check
      run(plan)

    case other =>
      throw ToolError(s"unknown jo subcommand '$other' in test")

private def runShellCmd(cmd: String, workDir: Path): String =
  val pb = ProcessBuilder(List("sh", "-c", cmd).asJava)
  pb.directory(workDir.toFile)
  val proc = pb.start()
  val out  = String(proc.getInputStream.readAllBytes(), "UTF-8")
  val exit = proc.waitFor()
  if exit != 0 then throw ToolError(s"shell command failed (exit $exit): $cmd")
  out

/** Run f, capturing Console output.  Returns (output, ok) — ok=false if f threw ToolError.
 *  Output printed before the error is still returned, enabling error-output checks. */
private def captureResult(f: => Unit): (String, Boolean) =
  val buf = ByteArrayOutputStream()
  val ps  = PrintStream(buf, true, "UTF-8")
  val ok  = try { Console.withOut(ps)(f); true }
             catch case _: ToolError => false
  (buf.toString("UTF-8"), ok)

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
