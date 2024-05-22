//> using file Ast.scala
//> using file Sast.scala
//> using file Namer.scala
//> using file Checker.scala
//> using file Parser.scala
//> using file Reporter.scala
//> using file Assembly.scala
//> using file Platform.scala
//> using file Compiler.scala
//> using file IO.scala
//> using file Assembler.scala
//> using file Linux.scala
//> using file X86.scala
//> using file ELF32.scala
//> using file UniqueName.scala
//> using file JSPlatform.scala
//> using file JSPlatformOpt.scala

import java.io.{ File => JFile }

import scala.collection.mutable
import scala.io.Source

import Reporter.{ Error, FatalError, State }

object Test:
  /** Creates a list of tests */
  def testsIn(dirPath: String): List[String] =
    val dir = new JFile(dirPath)
    assert(dir.exists(), s"the directory $dirPath does not exist")
    dir.listFiles.foldLeft(List.empty[String]) { case (inputs, f) =>
      if (f.getName.endsWith(".stk")) f.getPath :: inputs
      else inputs
    }

  def compileAndCheck(test: String): Boolean =
    given Platform = Linux.createX86Platform(test, "c1")
    val state = new State()
    given reporter: Reporter = Reporter.withSource(test)(using state)

    try
      IO.fileContent(test)          |>
      Parsing.parse                 |>
      new Namer().transform         |>
      Compiler.compile

      verifyErrors(test, Nil)
    catch
      case error: FatalError.CodeError =>
        verifyErrors(test, error.content :: Nil)

      case error: FatalError.InternalError =>
        false

      case error: FatalError.StopAfterPhase =>
        verifyErrors(test, state.errors)

  def verifyErrors(test: String, errors: List[Error])(using Reporter): Boolean =
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
def run() =
  val failed = new mutable.ArrayBuffer[String]
  val tests = Test.testsIn("stack-lang/neg")
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
