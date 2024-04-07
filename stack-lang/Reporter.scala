import scala.collection.mutable

import Reporter.*

/**
 * Deals with error reporting
  */
class Reporter(source: String, buf: mutable.ArrayBuffer[Error]):
  def this(source: String) = this(source, new mutable.ArrayBuffer)

  def withSource(source: String): Reporter = new Reporter(source, this.buf)

  def abort(message: String, span: Span): Nothing =
    val sourcePos = new SourcePosition(source, span.start, span.length)
    throw new FatalError.CodeError(message, sourcePos)

  def error(message: String, span: Span): Unit =
    val sourcePos = new SourcePosition(source, span.start, span.length)
    buf += new Error(message, sourcePos)

  def hasErrors: Boolean = buf.nonEmpty

  def report(): Unit =
    for error <- buf do
      println(error.message)
      println

object Reporter:
  extension [T](v: T)
    inline def |> [U](inline fn: T => U)(using rp: Reporter): U =
      if rp.hasErrors then
        throw FatalError.StopAfterPhase("Errors found")
      else
        fn(v)
  end extension

  /** The start and end of a token relative to the beginning of some file  */
  case class Span(start: Int, length: Int)

  /** A position in a source file */
  case class SourcePosition(source: String, start: Int, length: Int)

  /** An non-fatal error that does not abort the compilation */
  case class Error(message: String, pos: SourcePosition)

  /** A fatal error that aborts the compilation */
  enum FatalError extends Exception:
    val message: String
    case CodeError(message: String, pos: SourcePosition)
    case InternalError(message: String)
    case StopAfterPhase(message: String)

  def monitor(fn: Reporter => Unit): Unit =
    val reporter = new Reporter("<no source>")
    try
      fn(reporter)
    catch
      case error: FatalError =>
        println("Error: " + error.message)

  def abort(message: String, span: Span)(using rp: Reporter): Nothing =
    rp.abort(message, span)

  def abortInternal(message: String): Nothing =
    throw new FatalError.InternalError(message)

  def error(message: String, span: Span)(using rp: Reporter): Unit =
    rp.error(message, span)
