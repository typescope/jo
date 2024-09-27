import scala.collection.mutable

object Positions:
  /** Represents objects with positions */
  trait Positioned:
    this: Product =>

    Positions.checkComponentPos(this)

    def hasPos: Boolean = span `ne` NoSpan

    def span: Span

    def pos(using SourceContext): SourcePosition = span.toPos

  private def checkComponentPos(obj: Product): Unit =
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

    def toPos(using ctx: SourceContext): SourcePosition =
      toPos(ctx.source)

  object NoSpan extends Span(-1, -1)

  case class LineColumn(line: Int, column: Int)

  /** A source file
    *
    * The lineOffsets contains one more entry for EOF if it does not end with
    * a new line.
    *
    * The starting line has the offset 0, which is the first entry.
    */
  class Source(val file: String, lineOffsets: mutable.ArrayBuffer[Int]):
    def this(file: String) = this(file, mutable.ArrayBuffer())

    def addLineOffset(offset: Int): Unit =
      assert(lineOffsets.isEmpty || offset > lineOffsets.last, "offset = " + offset + ", " + lineOffsets.last)
      lineOffsets += offset

    def offsetToLineColumn(offset: Int): LineColumn =
      assert(lineOffsets.nonEmpty)
      // it is possible that `lineOffsets.last < offset` for line inquiry from parser

      var from = 0
      val last = lineOffsets.size - 1
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

      val lineOffset = lineOffsets(from)
      // println(s"from = $from, to = $to, offset = $offset, $lineOffsets")
      assert(offset >= lineOffset && (from == last || offset < lineOffsets(from + 1)))

      LineColumn(from, offset - lineOffset)

    def readLine(line: Int): String =
      val jfile = new java.io.RandomAccessFile(file, "r")
      jfile.seek(lineOffsets(line))
      val lineStr = jfile.readLine()
      jfile.close()
      lineStr

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

  /** A context with source information */
  trait SourceContext:
    def source: Source
