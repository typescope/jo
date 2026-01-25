package ast

object Naming:
  def isNameStart(c: Int): Boolean =
    isLetter(c) || c == '_'

  def isNameRest(c: Int): Boolean =
    isNameStart(c) || isDigit(c)

  val OP_CHAR: Array[Int] = Array('+', '-', '*', '/', '%', '|', '&', '^', '>', '<', '=', ':', '?', '!', '@', '~')
  def isOperatorChar(c: Int): Boolean =
    OP_CHAR.indexOf(c) >= 0

  def isSpace(c: Int): Boolean =
    c == ' ' || c == '\n' || c == '\t' || c == '\r'

  def isDigit(c: Int): Boolean =
    c >= '0' && c <= '9'

  def isLetter(c: Int): Boolean =
    c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'

  def isLetterOrDigit(c: Int): Boolean = isLetter(c) || isDigit(c)

  def isOperator(name: String): Boolean = !isNameStart(name(0))

  def isBinaryOperator(name: String): Boolean = isOperator(name) && name != "!"

  def isCapitalized(name: String): Boolean =
    Character.isUpperCase(name.charAt(0))
