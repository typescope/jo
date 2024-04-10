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
    state.addError(new Error(message, sourcePos))

  def hasErrors: Boolean = state.errors.nonEmpty

  def errorsCount = state.errors.size

  def report(): Unit =
    for error <- state.errors do
      println(error.message)
      println

object Reporter:
  // TODO: change fn to phase: Phase[T, U] <: T => U to get phase name
  extension [T](v: T)
    inline def |> [U](inline fn: T => U)(using rp: Reporter): U =
      if rp.hasErrors then
        throw FatalError.StopAfterPhase(s"${rp.errorsCount} error(s) found")
      else
        fn(v)
  end extension

  /** Shared state of reporters */
  class State(
    errorBuffer: mutable.ArrayBuffer[Error],
    private[Reporter] val sources: mutable.Map[String, Source]):

    def this() = this(mutable.ArrayBuffer.empty, mutable.Map.empty)

    def errors: List[Error] = errorBuffer.toList

    def addError(error: Error) = errorBuffer += error

  trait Positioned:
    this: Product =>

    private var span: Span = NoSpan

    // Check components have positions on construction
    for elem <- this.productIterator do checkPos(elem)

    private def checkPos(elem: Any): Unit =
      elem match
        case positioned: Positioned => assert(positioned.hasPos, "missing position")
        case elems: Seq[?]          => elems.foreach(checkPos)
        case  _                     =>

    def hasPos: Boolean = span `ne` NoSpan

    def withPos(span: Span): this.type =
      assert(!hasPos, "Position already set")
      this.span = span
      this

    def pos: Span = span

  /** The start and end of a token relative to the beginning of some file  */
  case class Span(start: Int, length: Int):
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

  /** An non-fatal error that does not abort the compilation */
  case class Error(message: String, pos: SourcePosition):
    override def toString() =
      val isOneLine = pos.isOneLine
      val lineContent = pos.source.readLine(pos.startLine).trim
      val padding = " " * pos.startLineColumn
      val pointer = if isOneLine then "^" * pos.length else "^"
      s"""|---------- Error at $pos ---------------
          || $lineContent
          || $padding$pointer
          || $padding$message""".stripMargin

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
      case error: FatalError.StopAfterPhase =>
        for error <- state.errors do
          println(error)
          println
        println(error.message)

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
