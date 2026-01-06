package reporting

import ast.Positions.*

import Reporter.*
import Diagnostics.*

import common.KeyProps

import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future, Await }
import scala.concurrent.duration.*

import java.util.concurrent.TimeoutException

/**
  * Deals with error reporting
  */
class Reporter(
  reported: mutable.ArrayBuffer[Diagnostic],     // reported items
  buffer: Boolean,                               // whether buffer reports
  sources: mutable.Map[String, Source])          // all sources
extends KeyProps.Container:

  def getSource(file: String): Source =
    sources.get(file) match
      case Some(source) => source
      case None =>
        val source = new Source(file)
        sources(file) = source
        source

  def fresh(buffer: Boolean = true): Reporter =
    new Reporter(mutable.ArrayBuffer.empty, buffer, sources).copyProps(this)

  def report(kind: Kind, message: String, pos: SourcePosition): Unit =
    report(new ReportItem(kind, message, pos))

  def report(kind: Kind, message: String, pos: SourcePosition, trace: Trace): Unit =
    report(new TracedItem(new ReportItem(kind, message, pos), trace))

  def report(item: Diagnostic): Unit =
    reported += item
    if !buffer then
      println(item)
      println

  def commit(toReporter: Reporter): Unit =
    for diag <- reported do toReporter.report(diag)

  def error(message: String, pos: SourcePosition): Unit =
    report(Kind.Error, message, pos)

  def warn(message: String, pos: SourcePosition): Unit =
    report(Kind.Warning, message, pos)

  def error(message: String, pos: SourcePosition, trace: Trace): Unit =
    report(Kind.Error, message, pos, trace)

  def warn(message: String, pos: SourcePosition, trace: Trace): Unit =
    report(Kind.Warning, message, pos, trace)

  def error(message: String): Unit =
    report(new UnpositionedReportItem(Kind.Error, message))

  def warn(message: String): Unit =
    report(new UnpositionedReportItem(Kind.Warning, message))

  def hasErrors: Boolean = reported.exists(_.kind == Kind.Error)

  def hasWarnings: Boolean = reported.exists(_.kind == Kind.Warning)

  def reports: List[Diagnostic] = reported.toList

  def printSummary() =
    var errorCount = 0
    var warningCount = 0

    for item <- this.reports do
      item.kind match
        case Kind.Error => errorCount += 1
        case Kind.Warning => warningCount += 1
        case Kind.Info =>

    println(s"$errorCount error(s), $warningCount warning(s)")

  extension [T](v: T)
    inline def |> [U](step: Step[T, U])(using config: Config): U =
      if this.hasErrors || Config.fatalWarnings.value && this.hasWarnings then
        throw FatalError.StopAfterPhase()
      else
        if Config.showSteps.value then println("Running " + step.name)
        step.run(v) <| step.name
  end extension

  extension [T](inline op: T)
    inline def <|(key: String): T =
      Timer.measure(key, enable = true)(op)(using this)

object Reporter:
  class Step[S, T](val name: String, val run: S => T)

  /** A fatal error that aborts the compilation */
  enum FatalError extends Exception:
    case CodeError(content: Diagnostic)
    case InternalError(message: String)
    case StopAfterPhase()

  def createReporter(buffer: Boolean = false): Reporter =
    val sources = mutable.Map.empty[String, Source]
    val reported = new mutable.ArrayBuffer[Diagnostic]
    new Reporter(reported, buffer, sources)

  def monitor()(fn: Reporter ?=> Unit)(using config: Config, reporter: Reporter): Unit =
    try
      timeout(100) { fn }  <| "total"
      if Config.reportTime.value then Timer.report()
    catch
      case error: FatalError.CodeError =>
        println("[error] " + error.content)
        System.exit(1)
      case error: FatalError.InternalError =>
        println("[error] " + error.message)
        System.exit(1)
      case _: FatalError.StopAfterPhase =>
        reporter.printSummary()
        System.exit(1)
      case _: TimeoutException =>
        println("Operation time out")
        System.exit(1)

  def timeout[T](seconds: Int)(work: => T): T =
    given ExecutionContext = ExecutionContext.global
    val workFuture = Future { work }
    Await.result(workFuture, Duration(seconds, SECONDS))

  def abort(message: String, pos: SourcePosition): Nothing =
    val error = new ReportItem(Kind.Error, message, pos)
    throw new FatalError.CodeError(error)

  def abortInternal(message: String): Nothing =
    throw new FatalError.InternalError(message)

  def error(message: String, pos: SourcePosition)(using rp: Reporter): Unit =
    rp.error(message, pos)

  def warn(message: String, pos: SourcePosition)(using rp: Reporter): Unit =
    rp.warn(message, pos)

  def error(message: String, pos: SourcePosition, trace: Trace)(using rp: Reporter): Unit =
    rp.error(message, pos, trace)

  def warn(message: String, pos: SourcePosition, trace: Trace)(using rp: Reporter): Unit =
    rp.warn(message, pos, trace)

  def error(message: String)(using rp: Reporter): Unit =
    rp.error(message)

  def warn(message: String)(using rp: Reporter): Unit =
    rp.warn(message)

  def reports(using rp: Reporter): List[Diagnostic] = rp.reports

  def source(file: String)(using rp: Reporter): Source = rp.getSource(file)
