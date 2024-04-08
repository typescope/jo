import scala.collection.mutable

import Reporter.*

/**
  * Deals with error reporting
  */
class Reporter(source: Source, state: State):
  export source.addLineOffset

  def withSource(file: String): Reporter =
    Reporter.withSource(file)(using state)

  def abort(message: String, span: Span): Nothing =
    val sourcePos = new SourcePosition(source, span.start, span.length)
    val error = new Error(message, sourcePos)
    throw new FatalError.CodeError(error)

  def error(message: String, span: Span): Unit =
    val sourcePos = new SourcePosition(source, span.start, span.length)
    state.errors += new Error(message, sourcePos)

  def hasErrors: Boolean = state.errors.nonEmpty

  def report(): Unit =
    for error <- state.errors do
      println(error.message)
      println

object Reporter:
  // TODO: change fn to phase: Phase[T, U] <: T => U to get phase name
  extension [T](v: T)
    inline def |> [U](inline fn: T => U)(using rp: Reporter): U =
      if rp.hasErrors then
        throw FatalError.StopAfterPhase("Errors found")
      else
        fn(v)
  end extension

  /** Shared state of reporters */
  class State(
    private[Reporter] val errors: mutable.ArrayBuffer[Error],
    private[Reporter] val sources: mutable.Map[String, Source]):

    def this() = this(mutable.ArrayBuffer.empty, mutable.Map.empty)

  /** The start and end of a token relative to the beginning of some file  */
  case class Span(start: Int, length: Int)

  case class LineColumn(line: Int, column: Int)

  /** A source file */
  class Source(val file: String, lineOffsets: mutable.ArrayBuffer[Int]):
    def this(file: String) = this(file, mutable.ArrayBuffer(0))

    def addLineOffset(offset: Int): Unit =
      lineOffsets += offset

    def offsetToLineColumn(offset: Int): LineColumn =
      var from = 0
      var to = lineOffsets.size - 1

      while from != to do
        val mid = (to + 1) / 2
        if lineOffsets(mid) == offset then
          from = mid
          to = mid
        else if lineOffsets(mid) < offset then
          from = mid
        else
          to = mid

      LineColumn(from, offset - lineOffsets(from))

  /** A position in a source file */
  case class SourcePosition(source: Source, start: Int, length: Int):
    lazy val startPos = source.offsetToLineColumn(start)
    lazy val endPos = source.offsetToLineColumn(start + length)
    def startLine: Int = startPos.line
    def endLine: Int = endPos.line
    def startLineColumn: Int = startPos.column
    def endLineColumn: Int = endPos.column

    override def toString() =
      source.file + ":" + (startLine + 1) + ":" + (startLineColumn + 1)

  /** An non-fatal error that does not abort the compilation */
  case class Error(message: String, pos: SourcePosition):
    override def toString() = message + " at " + pos

  /** A fatal error that aborts the compilation */
  enum FatalError extends Exception:
    case CodeError(content: Error)
    case InternalError(message: String)
    case StopAfterPhase(message: String)

  def monitor(fn: State ?=> Unit): Unit =
    val state = new State()
    try
      fn(using state)
    catch
      case error: FatalError.CodeError =>
        println("[error] " + error.content)
      case error: FatalError.InternalError =>
        println("[error] " + error.message)

  def withSource(file: String)(using state: State) =
    state.sources.get(file) match
      case Some(source) => new Reporter(source, state)
      case None =>
        val source = new Source(file)
        state.sources(file) = source
        new Reporter(source, state)


  def abort(message: String, span: Span)(using rp: Reporter): Nothing =
    rp.abort(message, span)

  def abortInternal(message: String): Nothing =
    throw new FatalError.InternalError(message)

  def error(message: String, span: Span)(using rp: Reporter): Unit =
    rp.error(message, span)
