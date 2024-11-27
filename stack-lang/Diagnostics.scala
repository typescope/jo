import Symbols.Symbol
import Positions.*


object Diagnostics:
  /** Kind of reports */
  enum Kind:
    case Error, Warning, Info

  sealed abstract class Diagnostic:
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

  class DoubleDefinition(symBefore: Symbol, symNow: Symbol)
  extends Diagnostic:
    assert(symBefore.name == symNow.name)

    val kind = Kind.Error
    val positioned = true
    val pos = symNow.sourcePos

    override def toString() =
      val message = "Redefinition of " + symBefore.name
      val pos = symNow.sourcePos
      val lineContent = pos.source.readLine(pos.startLine).replaceAll("[\n\r]$", "")
      val padding = " " * pos.startLineColumn
      val num = if pos.length == 0 then 1 else pos.length
      val pointer = if pos.isOneLine then "^" * num else "^"

      val pos2 = symBefore.sourcePos
      val lineContent2 = pos2.source.readLine(pos2.startLine).replaceAll("[\n\r]$", "")
      val num2 = if pos2.length == 0 then 1 else pos2.length
      val padding2 = " " * pos2.startLineColumn
      val pointer2 = if pos2.isOneLine then "^" * num2 else "^"

      s"""|---------- $kind at $pos ---------------
          || $lineContent
          || $padding$pointer
          || $padding$message
          ||
          || The name `${symNow.name}` is already defined at $pos2:
          || $lineContent2
          || $padding2$pointer2""".stripMargin
