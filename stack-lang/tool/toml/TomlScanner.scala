package tool.toml

enum Token:
  case TKey(value: String)
  case TStr(value: String)
  case TInt(value: Long)
  case TBool(value: Boolean)
  case TLBracket          // [
  case TRBracket          // ]
  case TLBrace            // {
  case TRBrace            // }
  case TEquals            // =
  case TComma             // ,
  case TDot               // .
  case TNewline
  case TEOF

case class ScannedToken(token: Token, line: Int)

class TomlScanner(input: String):
  private var pos = 0
  private var line = 1

  def scanAll(): List[ScannedToken] =
    val buf = collection.mutable.ListBuffer.empty[ScannedToken]
    var tok = next()

    while tok.token != Token.TEOF do
      tok.token match
        case Token.TNewline => // skip blank lines silently but emit one newline per run
          buf += tok
          skipNewlines()
        case _ =>
          buf += tok
      tok = next()

    buf += tok
    buf.toList

  private def skipNewlines(): Unit =
    while pos < input.length && (input(pos) == '\r' || input(pos) == '\n') do
      if input(pos) == '\n' then line += 1
      pos += 1

  private def next(): ScannedToken =
    skipSpacesAndComments()

    if pos >= input.length then return ScannedToken(Token.TEOF, line)

    val startLine = line
    val ch = input(pos)

    ch match
      case '\r' | '\n' =>
        if ch == '\n' then line += 1

        pos += 1
        ScannedToken(Token.TNewline, startLine)
      case '[' =>
        pos += 1
        ScannedToken(Token.TLBracket, startLine)
      case ']' =>
        pos += 1
        ScannedToken(Token.TRBracket, startLine)
      case '{' =>
        pos += 1
        ScannedToken(Token.TLBrace, startLine)
      case '}' =>
        pos += 1
        ScannedToken(Token.TRBrace, startLine)
      case '=' =>
        pos += 1
        ScannedToken(Token.TEquals, startLine)
      case ',' =>
        pos += 1
        ScannedToken(Token.TComma, startLine)
      case '.' =>
        pos += 1
        ScannedToken(Token.TDot, startLine)
      case '"'  => ScannedToken(Token.TStr(readBasicString()), startLine)
      case '\'' => ScannedToken(Token.TStr(readLiteralString()), startLine)
      case _ if ch.isDigit || ch == '-' => ScannedToken(Token.TInt(readInt()), startLine)
      case _ if ch.isLetter || ch == '_' => readKeyOrBool(startLine)
      case _ => throw TomlError(s"unexpected character '${ch}'", startLine)

  private def skipSpacesAndComments(): Unit =
    while pos < input.length do
      val c = input(pos)

      if c == ' ' || c == '\t' then
        pos += 1
      else if c == '#' then
        while pos < input.length && input(pos) != '\n' && input(pos) != '\r' do
          pos += 1
      else
        return

  private def readBasicString(): String =
    pos += 1 // consume opening "
    val sb = new StringBuilder

    while pos < input.length && input(pos) != '"' do
      if input(pos) == '\\' then
        pos += 1

        if pos >= input.length then throw TomlError("unterminated escape in string", line)

        input(pos) match
          case '"'  => sb += '"'
          case '\\' => sb += '\\'
          case 'n'  => sb += '\n'
          case 't'  => sb += '\t'
          case 'r'  => sb += '\r'
          case c    => throw TomlError(s"unknown escape \\$c", line)
      else
        if input(pos) == '\n' then line += 1

        sb += input(pos)

      pos += 1

    if pos >= input.length then throw TomlError("unterminated string", line)

    pos += 1 // consume closing "
    sb.toString

  private def readLiteralString(): String =
    pos += 1 // consume opening '
    val sb = new StringBuilder

    while pos < input.length && input(pos) != '\'' do
      if input(pos) == '\n' then line += 1

      sb += input(pos)
      pos += 1

    if pos >= input.length then throw TomlError("unterminated literal string", line)

    pos += 1 // consume closing '
    sb.toString

  private def readInt(): Long =
    val start = pos

    if pos < input.length && input(pos) == '-' then pos += 1

    while pos < input.length && input(pos).isDigit do pos += 1

    val s = input.substring(start, pos)

    try s.toLong
    catch case _: NumberFormatException => throw TomlError(s"invalid integer: $s", line)

  private def readKeyOrBool(startLine: Int): ScannedToken =
    val start = pos

    while pos < input.length && (input(pos).isLetterOrDigit || input(pos) == '_' || input(pos) == '-') do
      pos += 1

    val s = input.substring(start, pos)

    s match
      case "true"  => ScannedToken(Token.TBool(true), startLine)
      case "false" => ScannedToken(Token.TBool(false), startLine)
      case key     => ScannedToken(Token.TKey(key), startLine)
