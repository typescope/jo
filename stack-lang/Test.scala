import java.io.{ File => JFile }

import scala.collection.mutable
import scala.io.Source

import common.IO
import phases.FrontEnd

import reporting.Reporter
import reporting.Reporter.FatalError
import reporting.Diagnostics.*

object Test:
  /** Creates a list of tests */
  def testsIn(dirPath: String): List[String] =
    val dir = new JFile(dirPath)
    assert(dir.exists(), s"the directory $dirPath does not exist")
    dir.listFiles.foldLeft(List.empty[String]) { case (inputs, f) =>
      val name = f.getName
      if (name.endsWith(".stk") || f.isDirectory) f.getPath :: inputs
      else inputs
    }

  def compileAndCheck(test: String): Boolean = Reporter.timeout(100):
    given rp: Reporter = Reporter.createReporter(buffer = true)
    given Reporter.Config = Reporter.Config(fatalWarnings = true)

    val sourceFiles =
      if IO.isFile(test) then test :: Nil
      else IO.list(test).filter(_.endsWith(".stk"))

    try
      val stdLib = "lib/Predef.stk" :: Nil
      val runtimeFiles = Nil
      FrontEnd.run(stdLib, runtimeFiles, sourceFiles)

      verifyErrors(sourceFiles, rp.reports)
    catch
      case error: FatalError.CodeError =>
        verifyErrors(sourceFiles, error.content :: Nil)

      case error: FatalError.InternalError =>
        false

      case error: FatalError.StopAfterPhase =>
        verifyErrors(sourceFiles, Reporter.reports)

  def verifyErrors(sourceFiles: List[String], errors: List[Diagnostic])(using Reporter): Boolean =
    val errorMap = mutable.Map.empty[(String, Int), Int] // line -> count
    var errorsExpected = 0

    for
      file <- sourceFiles
    do
      val source = Source.fromFile(file)
      var lineNum = 0
      for line <- source.getLines() do
        val count = "// error".r.findAllMatchIn(line).size
        if count > 0 then
          errorMap(file -> lineNum) = count
          errorsExpected += count
        lineNum += 1
      source.close()
    end for

    var success = errorsExpected == errors.size

    if !success then
      println(s"Expect $errorsExpected errors, found = ${errors.size}")

    for ((file, line), count) <- errorMap do
      val found = errors.filter(e => e.positioned && e.pos.startLine == line && e.pos.source.file == file).size
      if count != found then
        success = false
        println("Incorrect number of errors at line " + file + ":" + line + ", found = " + found + ", expect = " + count)
    end for

    success

@main
def negTest() =
  val failed = new mutable.ArrayBuffer[String]
  val tests = Test.testsIn("tests/neg")
  for sourceFile <- tests  do
    println("testing " + sourceFile)

    if !Test.compileAndCheck(sourceFile) then
      failed += sourceFile
      println(s"[error] testing $sourceFile failed")
  end for

  val succeedNum = tests.size - failed.size

  println
  println(s"${tests.size} tests, $succeedNum succeeded, ${failed.size} failed")

  if failed.size > 0 then
    println
    println("Failed tests:")
    for file <- failed  do
      println("- " + file)

    System.exit(1)
