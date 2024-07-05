import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future, Await }
import scala.concurrent.duration.*

import java.util.concurrent.TimeoutException

import Reporter.{ ReportItem, Kind, FatalError, SourcePosition, Span, State, Source }

/**
  * Deals with error reporting
  */
class Reporter(val source: Source, state: State):
  export source.addLineOffset

  def withSource(file: String): Reporter =
    Reporter.withSource(file)(using state)

  def abort(message: String, pos: SourcePosition): Nothing =
    val error = new ReportItem(Kind.Error, message, pos)
    throw new FatalError.CodeError(error)

  private def report(kind: Kind, message: String, pos: SourcePosition): Unit =
    state.add(new ReportItem(kind, message, pos))

  def error(message: String, pos: SourcePosition): Unit =
    report(Kind.Error, message, pos)

  def warn(message: String, pos: SourcePosition): Unit =
    report(Kind.Warning, message, pos)

  def hasErrors: Boolean = state.hasErrors

  // TODO: change fn to phase: Phase[T, U] <: T => U to get phase name
  extension [T](v: T)
    inline def |> [U](inline fn: T => U): U =
      if this.hasErrors then
        throw FatalError.StopAfterPhase()
      else
        fn(v)
  end extension

object Reporter:

  /** Shared state of reporters */
  class State(
    reported: mutable.ArrayBuffer[ReportItem],
    private[Reporter] val sources: mutable.Map[String, Source]):

    def this() = this(mutable.ArrayBuffer.empty, mutable.Map.empty)

    def reports: List[ReportItem] = reported.toList

    def hasErrors: Boolean = reported.exists(_.kind == Kind.Error)

    def add(item: ReportItem) = reported += item

    def print() =
      var errorCount = 0
      var warningCount = 0

      for item <- this.reports do
        item.kind match
          case Kind.Error => errorCount += 1
          case Kind.Warning => warningCount += 1
          case Kind.Info =>

        println(item)
        println

      println(s"$errorCount error(s), $warningCount warning(s)")

  trait Positioned:
    this: Product =>

    Positioned.checkComponentPos(this)

    def hasPos: Boolean = span `ne` NoSpan

    def span: Span

    def pos(using Reporter): SourcePosition = span.toPos

  object Positioned:
    def checkComponentPos(obj: Product): Unit =
      def checkPos(elem: Any): Unit =
        elem match
          case elem: Positioned => assert(elem.hasPos, "missing position: " + elem)
          case elems: Seq[?]    => elems.foreach(checkPos)
          case  _               =>
        end match

      for elem <- obj.productIterator do checkPos(elem)

  /** The start and end of a token relative to the beginning of some file  */
  case class Span(start: Int, length: Int):
    /** A zero length span at the same point */
    def point: Span = Span(start, 0)

    def |(that: Span): Span =
      if this `eq` NoSpan then that
      else if that `eq` NoSpan then this
      else
        val start3 =
          if this.start > that.start then that.start
          else this.start
        val end1 = this.start + this.length
        val end2 = that.start + that.length
        val end3 = if end1 > end2 then end1 else end2
        Span(start3, end3 - start3)

    def toPos(source: Source): SourcePosition =
      new SourcePosition(source, this.start, this.length)

    def toPos(using rp: Reporter): SourcePosition =
      toPos(rp.source)

  object NoSpan extends Span(-1, -1)

  case class LineColumn(line: Int, column: Int)

  /** A source file
    *
    * The lineOffsets contains one more entry for EOF if it does not end with
    * a new line.
    */
  class Source(val file: String, lineOffsets: mutable.ArrayBuffer[Int]):
    def this(file: String) = this(file, mutable.ArrayBuffer(0))

    def addLineOffset(offset: Int): Unit =
      lineOffsets += offset

    def offsetToLineColumn(offset: Int): LineColumn =
      var from = 0
      val last = lineOffsets.size - 2 // ignore the last entry
      var to = last

      while from != to do
        val mid = (to + from) / 2
        // println(s"loop: from = $from, to = $to, mid = $mid")
        if mid == from then
          // only possible when `to + 1 == from`
          if lineOffsets(to) > offset then to = from
          else from = to
        else if lineOffsets(mid) == offset then
          from = mid
          to = mid
        else if lineOffsets(mid) < offset then
          from = mid
        else
          to = mid

      // println(s"from = $from, to = $to, offset = $offset, $lineOffsets")
      assert(offset >= lineOffsets(from) && (from == last || offset < lineOffsets(from + 1)))

      LineColumn(from, offset - lineOffsets(from))

    def lineLength(line: Int) =
      assert(line < lineOffsets.size - 1)  // ignore the last entry
      lineOffsets(line + 1) - lineOffsets(line)

    def readLine(line: Int): String =
      val jfile = new java.io.RandomAccessFile(file, "r")
      val bytes = new Array[Byte](lineLength(line))
      jfile.seek(lineOffsets(line))
      jfile.read(bytes)
      jfile.close()
      new String(bytes)

  /** A position in a source file */
  case class SourcePosition(source: Source, start: Int, length: Int):
    lazy val startPos = source.offsetToLineColumn(start)
    lazy val endPos = source.offsetToLineColumn(start + length)

    def startLine: Int = startPos.line
    def endLine: Int = endPos.line
    def startLineColumn: Int = startPos.column
    def endLineColumn: Int = endPos.column
    def isOneLine: Boolean = startLine == endLine

    override def toString() =
      source.file + ":" + (startLine + 1) + ":" + (startLineColumn + 1)

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

  def monitor(fn: State ?=> Unit): Unit =
    val state = new State()
    try
      timeout(100) { fn(using state) }
    catch
      case error: FatalError.CodeError =>
        println("[error] " + error.content)
      case error: FatalError.InternalError =>
        println("[error] " + error.message)
      case error: FatalError.StopAfterPhase =>
        state.print()
      case error: TimeoutException =>
        println("Operation time out")

  def timeout[T](seconds: Int)(work: => T): T =
    given ExecutionContext = ExecutionContext.global
    val workFuture = Future { work }
    Await.result(workFuture, Duration(seconds, SECONDS))

  def withSource(file: String)(using state: State) =
    state.sources.get(file) match
      case Some(source) => new Reporter(source, state)
      case None =>
        val source = new Source(file)
        state.sources(file) = source
        new Reporter(source, state)


  def abort(message: String, pos: SourcePosition)(using rp: Reporter): Nothing =
    rp.abort(message, pos)

  def abortInternal(message: String): Nothing =
    throw new FatalError.InternalError(message)

  def error(message: String, pos: SourcePosition)(using rp: Reporter): Unit =
    rp.error(message, pos)

  def warn(message: String, pos: SourcePosition)(using rp: Reporter): Unit =
    rp.warn(message, pos)
