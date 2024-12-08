package reporting

import pos.Positions.*


object Diagnostics:
  /** Kind of reports */
  enum Kind:
    case Error, Warning, Info

  abstract class Diagnostic:
    def kind: Kind
    def positioned: Boolean
    def pos: SourcePosition

  class ReportItem(val kind: Kind, val message: String, val pos: SourcePosition)
  extends Diagnostic:
    val positioned = true
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
