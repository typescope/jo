package ast

object Name:
  def isNameStart(c: Char): Boolean =
    isLetter(c) || c == '_'

  def isNameRest(c: Char): Boolean =
    isNameStart(c) || isDigit(c)

  val OP_CHAR = Array('+', '-', '*', '/', '%', '|', '&', '^', '>', '<', '=', ':', '?', '!', '@', '~')
  def isOperator(c: Char): Boolean =
    OP_CHAR.indexOf(c) >= 0

  def isSpace(c: Char): Boolean =
    c == ' ' || c == '\n' || c == '\t'

  def isDigit(c: Char): Boolean =
    c >= '0' && c <= '9'

  def isLetter(c: Char): Boolean =
    c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'

  def isOperator(name: String): Boolean = !isNameStart(name(0))
