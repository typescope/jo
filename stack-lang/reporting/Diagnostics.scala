package reporting

import ast.Positions.*
import common.StringUtil

import scala.collection.mutable

object Diagnostics:
  /** Kind of reports */
  enum Kind:
    case Error, Warning, Info

  abstract class Diagnostic:
    def kind: Kind
    def positioned: Boolean
    def pos: SourcePosition

  //----------------------------------------------------------------------------
  //
  // Unpositioned report
  //

  class UnpositionedReportItem(val kind: Kind, val message: String)
  extends Diagnostic:
    val positioned = false

    def pos = throw new Exception("No position for the report: " + this)

    override def toString() = s"[$kind] $message"

  //----------------------------------------------------------------------------
  //
  // Positioned report
  //

  class ReportItem(val kind: Kind, val message: String, val pos: SourcePosition)
  extends Diagnostic:
    val positioned = true
    override def toString() =
      val isOneLine = pos.isOneLine
      val lineContent = pos.source.lineContent(pos.startLine)
      val padding = " " * StringUtil.displayColumnsForCodePoints(lineContent, pos.startLineColumn)
      val num =
        if isOneLine then
          math.max(1, StringUtil.displayColumnsInRange(lineContent, pos.startLineColumn, pos.endLineColumn))
        else 1
      val pointer = if isOneLine then "^" * num  else "^"
      s"""|---------- $kind at $pos ---------------
          || $lineContent
          || $padding$pointer
          || $padding$message""".stripMargin


  //----------------------------------------------------------------------------
  //
  // Positioned report
  //
  trait DoublePositionedReport extends Diagnostic:
    val message1: String
    val message2: String
    val pos1: SourcePosition
    val pos2: SourcePosition

    val positioned = true

    def pos = pos1

    override def toString() =
      val lineContent = pos1.source.lineContent(pos1.startLine)
      val padding = " " * StringUtil.displayColumnsForCodePoints(lineContent, pos1.startLineColumn)
      val num =
        if pos1.isOneLine then
          math.max(1, StringUtil.displayColumnsInRange(lineContent, pos1.startLineColumn, pos1.endLineColumn))
        else 1
      val pointer = if pos1.isOneLine then "^" * num else "^"

      val lineContent2 = pos2.source.lineContent(pos2.startLine)
      val padding2 = " " * StringUtil.displayColumnsForCodePoints(lineContent2, pos2.startLineColumn)
      val num2 =
        if pos2.isOneLine then
          math.max(1, StringUtil.displayColumnsInRange(lineContent2, pos2.startLineColumn, pos2.endLineColumn))
        else 1
      val pointer2 = if pos2.isOneLine then "^" * num2 else "^"

      s"""|---------- $kind at $pos ---------------
          || $lineContent
          || $padding$pointer
          || $padding$message1
          ||
          || $message2
          || $lineContent2
          || $padding2$pointer2""".stripMargin

  //----------------------------------------------------------------------------
  //
  // Traced error report
  //

  type Trace = Vector[SourcePosition]

  val EMPTY_PADDING     = "    "
  val CONNECTING_INDENT = "\u2502   "               // "|   "
  val CHILD             = "\u251c\u2500\u2500 "     // "|-- "
  val LAST_CHILD        = "\u2514\u2500\u2500 "     // "\-- "

  class TracedItem(item: ReportItem, trace: Trace)
  extends Diagnostic:
    val positioned = true
    val kind = item.kind
    val pos = item.pos

    override def toString() =
      val traceText =
        if trace.size > 1 then
          System.lineSeparator() * 2
          + "The following is the trace that leads to the problem:"
          + System.lineSeparator() + buildStacktrace(trace)
        else
          ""

      item.toString() + traceText

  private def buildStacktrace(trace: Trace): String =
    assert(trace.size > 1, trace.size)

    val lines: mutable.ArrayBuffer[String] = new mutable.ArrayBuffer
    for pos <- trace do
      val isLastTraceItem = pos `eq` trace.last
      val line =
        val loc = "[ " + pos + " ]"
        val code = pos.source.lineContent(pos.startLine)
        s"$code\t$loc"

      val positionMarkerLine =
          (if isLastTraceItem then EMPTY_PADDING else CONNECTING_INDENT) + positionMarker(pos)

      val prefix = if isLastTraceItem then LAST_CHILD else CHILD
      lines += (prefix + line + System.lineSeparator() + positionMarkerLine)
    end for

    val sb = new StringBuilder
    for line <- lines do sb.append(line)
    sb.toString

  /** Used to underline source positions in the stack trace
   *  pos.source must exist
   */
  private def positionMarker(pos: SourcePosition): String =
    val lineContent = pos.source.lineContent(pos.startLine)
    val padding = " " * StringUtil.displayColumnsForCodePoints(lineContent, pos.startLineColumn)
    val carets =
      if (pos.startLine == pos.endLine)
        "^" * math.max(1, StringUtil.displayColumnsInRange(lineContent, pos.startLineColumn, pos.endLineColumn))
      else "^"

    s"$padding$carets" + System.lineSeparator()
