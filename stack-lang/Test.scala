import java.io.{ File => JFile }

import scala.collection.mutable
import scala.io.Source

import Reporter.{ ReportItem, FatalError }

object Test:
  /** Creates a list of tests */
  def testsIn(dirPath: String): List[String] =
    val dir = new JFile(dirPath)
    assert(dir.exists(), s"the directory $dirPath does not exist")
    dir.listFiles.foldLeft(List.empty[String]) { case (inputs, f) =>
      if (f.getName.endsWith(".stk")) f.getPath :: inputs
      else inputs
    }

  def compileAndCheck(test: String): Boolean = Reporter.timeout(100):
    given Reporter = Reporter.createReporter(test, buffer = true)

    try
      IO.fileContent(test)          |>
      Parser.parse                  |>
      Namer.transform               |>
      new ExplicitInit().transform

      verifyErrors(test, Nil)
    catch
      case error: FatalError.CodeError =>
        verifyErrors(test, error.content :: Nil)

      case error: FatalError.InternalError =>
        false

      case error: FatalError.StopAfterPhase =>
        verifyErrors(test, Reporter.reports)

  def verifyErrors(test: String, errors: List[ReportItem])(using Reporter): Boolean =
    val errorMap = mutable.Map.empty[Int, Int] // line -> count
    var errorsExpected = 0
    var lineNum = 0

    val source = Source.fromFile(test)
    for line <- source.getLines() do
      val count = "// error".r.findAllMatchIn(line).size
      if count > 0 then
        errorMap(lineNum) = count
        errorsExpected += count
      lineNum += 1
    end for
    source.close()

    var success = errorsExpected == errors.size

    if !success then
      println(s"Expect $errorsExpected errors in $test, found = ${errors.size}")

    for (line, count) <- errorMap do
      val found = errors.filter(_.pos.startLine == line).size
      if count != found then
        success = false
        println("Incorrect number of errors at line " + test + ":" + line)
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
