package fuzzing

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

import ast.Printing
import parsing.Parser
import reporting.Reporter

/** Printer round-trip checker.
  *
  * For each `.jo` file under a directory (default `tests/pos`):
  *   1. Parse the file (skip if parsing itself fails — not the printer's fault).
  *   2. Print the resulting `FileUnit` via `ast.Printing.show`.
  *   3. Parse the printed output. It must not throw and must not produce
  *      errors on the Reporter.
  *
  * Reports a pass/fail summary plus the first error per failing file.
  *
  * This checks *parse* round-trip only — enough to validate that the printer
  * emits something the parser accepts. Behavioral round-trip (run both files
  * and diff stdout) is an orthogonal, more expensive check and is out of scope
  * for this tool.
  */
object TestPrinter:

  private case class Failure(file: Path, phase: String, message: String)

  def run(args: List[String]): Int =
    val dir       = argValue(args, "--dir",   "tests/pos")
    val limit     = argValue(args, "--limit", "").toIntOption
    val verbose   = args.contains("--verbose")
    val stopFirst = args.contains("--stop-on-fail")

    val all      = collectJoFiles(Paths.get(dir))
    val selected = limit.fold(all)(n => all.take(n))

    println(s"test-printer: dir=$dir files=${selected.size}")

    var passed        = 0
    var skipped       = 0
    val failuresBuf   = List.newBuilder[Failure]

    var done = false
    val it = selected.iterator
    while !done && it.hasNext do
      val file = it.next()
      check(file) match
        case CheckResult.Ok =>
          passed += 1
          if verbose then println(s"  ok: $file")

        case CheckResult.SkipOriginal(msg) =>
          skipped += 1
          if verbose then println(s"  skip: $file ($msg)")

        case CheckResult.Fail(phase, msg) =>
          failuresBuf += Failure(file, phase, msg)
          println(s"  FAIL [$phase]: $file")
          println(s"    $msg")
          if stopFirst then done = true

    val failures = failuresBuf.result()
    println()
    println(s"summary: ${selected.size} files, $passed passed, ${failures.size} failed, $skipped skipped (unparseable)")

    if failures.nonEmpty then 1 else 0
  end run

  //--------------------------------------------------------------------------
  // Per-file check

  private enum CheckResult:
    case Ok
    case SkipOriginal(msg: String)
    case Fail(phase: String, msg: String)

  private def check(file: Path): CheckResult =
    val originalTree =
      try
        given rp: Reporter = Reporter.createReporter(buffer = true)
        val t = Parser.parse(file.toString)
        if rp.hasErrors then
          return CheckResult.SkipOriginal(firstError(rp))
        t
      catch case t: Throwable =>
        return CheckResult.SkipOriginal(t.getClass.getSimpleName + ": " + t.getMessage)

    val printed =
      try Printing.show(originalTree)
      catch case t: Throwable =>
        return CheckResult.Fail("print", t.getClass.getSimpleName + ": " + t.getMessage)

    val tmp = Files.createTempFile("printer-test-", ".jo")
    try
      Files.writeString(tmp, printed)

      try
        given rp: Reporter = Reporter.createReporter(buffer = true)
        Parser.parse(tmp.toString)
        if rp.hasErrors then
          CheckResult.Fail("reparse", firstError(rp))
        else
          CheckResult.Ok
      catch case t: Throwable =>
        CheckResult.Fail("reparse", t.getClass.getSimpleName + ": " + t.getMessage)
    finally Files.deleteIfExists(tmp)
  end check

  //--------------------------------------------------------------------------
  // Helpers

  private def collectJoFiles(dir: Path): List[Path] =
    if !Files.isDirectory(dir) then Nil
    else
      val stream = Files.walk(dir)
      try
        stream.iterator.asScala
          .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".jo"))
          .toList
          .sortBy(_.toString)
      finally stream.close()

  private def argValue(args: List[String], key: String, default: String): String =
    args.sliding(2).collectFirst { case List(`key`, v) => v }.getOrElse(default)

  private def firstError(rp: Reporter): String =
    rp.reports
      .collectFirst { case d if d.kind == reporting.Diagnostics.Kind.Error => d.toString }
      .getOrElse("(reporter has errors but none accessible)")

end TestPrinter
