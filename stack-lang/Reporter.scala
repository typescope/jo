import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future, Await }
import scala.concurrent.duration.*

import java.util.concurrent.TimeoutException

import Positions.{ Source, SourcePosition, SourceContext }
import Reporter.{ ReportItem, Kind, FatalError }

/**
  * Deals with error reporting
  */
class Reporter(
  val source: Source,                            // current source
  reported: mutable.ArrayBuffer[ReportItem],     // reported items
  sources: mutable.Map[String, Source])          // all sources
extends SourceContext:

  export source.addLineOffset

  def withSource(file: String): Reporter =
    sources.get(file) match
      case Some(source) => new Reporter(source, reported, sources)
      case None =>
        val source = new Source(file)
        sources(file) = source
        new Reporter(source, reported, sources)

  def withSource(source: Source): Reporter =
    new Reporter(source, reported, sources)

  def fresh(): Reporter =
    new Reporter(source, mutable.ArrayBuffer.empty, sources)

  def abort(message: String, pos: SourcePosition): Nothing =
    val error = new ReportItem(Kind.Error, message, pos)
    throw new FatalError.CodeError(error)

  def report(kind: Kind, message: String, pos: SourcePosition): Unit =
    report(new ReportItem(kind, message, pos))

  def report(item: ReportItem): Unit =
    reported += item
    println(item)
    println

  def error(message: String, pos: SourcePosition): Unit =
    report(Kind.Error, message, pos)

  def warn(message: String, pos: SourcePosition): Unit =
    report(Kind.Warning, message, pos)

  def hasErrors: Boolean = reported.exists(_.kind == Kind.Error)

  def reports: List[ReportItem] = reported.toList

  def printSummary() =
    var errorCount = 0
    var warningCount = 0

    for item <- this.reports do
      item.kind match
        case Kind.Error => errorCount += 1
        case Kind.Warning => warningCount += 1
        case Kind.Info =>

    println(s"$errorCount error(s), $warningCount warning(s)")

  // TODO: change fn to phase: Phase[T, U] <: T => U to get phase name
  extension [T](v: T)
    inline def |> [U](inline fn: T => U): U =
      if this.hasErrors then
        throw FatalError.StopAfterPhase()
      else
        fn(v)

    inline def |+ [U](inline fn: T => U): U = fn(v)
  end extension

object Reporter:
  /** Kind of reports */
  enum Kind:
    case Error, Warning, Info

  class ReportItem(val kind: Kind, val message: String, val pos: SourcePosition):
    override def toString() =
      val isOneLine = pos.isOneLine
      val lineContent = pos.source.readLine(pos.startLine).replaceAll("[\n\r]$", "")
      val padding = " " * pos.startLineColumn
      val num = if pos.length == 0 then 1 else pos.length
      val pointer = if isOneLine then "^" * num  else "^"
      s"""|---------- $kind at $pos ---------------
          || $lineContent
          || $padding$pointer
          || $padding$message""".stripMargin

  /** A fatal error that aborts the compilation */
  enum FatalError extends Exception:
    case CodeError(content: ReportItem)
    case InternalError(message: String)
    case StopAfterPhase()

  def createReporter(file: String): Reporter =
    val source = new Source(file)
    val sources = mutable.Map(file -> source)
    val reported = new mutable.ArrayBuffer[ReportItem]
    new Reporter(source, reported, sources)

  def monitor[T](file: String)(fn: Reporter ?=> Unit): Unit =
    val reporter = createReporter(file)
    try
      timeout(100) { fn(using reporter) }
    catch
      case error: FatalError.CodeError =>
        println("[error] " + error.content)
      case error: FatalError.InternalError =>
        println("[error] " + error.message)
      case error: FatalError.StopAfterPhase =>
        reporter.printSummary()
      case error: TimeoutException =>
        println("Operation time out")

  def timeout[T](seconds: Int)(work: => T): T =
    given ExecutionContext = ExecutionContext.global
    val workFuture = Future { work }
    Await.result(workFuture, Duration(seconds, SECONDS))

  def abort(message: String, pos: SourcePosition)(using rp: Reporter): Nothing =
    rp.abort(message, pos)

  def abortInternal(message: String): Nothing =
    throw new FatalError.InternalError(message)

  def error(message: String, pos: SourcePosition)(using rp: Reporter): Unit =
    rp.error(message, pos)

  def warn(message: String, pos: SourcePosition)(using rp: Reporter): Unit =
    rp.warn(message, pos)

  def reports(using rp: Reporter): List[ReportItem] = rp.reports
