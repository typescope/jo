package common

object StringUtil:
  /** Translate escape in the string
    *
    * Java 15 added String.translateEscapes. Don't depend on it for clarity.
    */
  def unescape(s: String): String =
    val sb = new StringBuilder
    var isLastEscape = false

    var i = 0
    while i < s.size do
      val c = s(i)
      if i == 0 || !isLastEscape then
        if c == '\\' then isLastEscape = true
        else sb += c
      else
        c match
          case 'b'  => sb += '\b'
          case 'f'  => sb += '\f'
          case 'n'  => sb += '\n'
          case 'r'  => sb += '\r'
          case 't'  => sb += '\t'
          case '"'  => sb += '"'
          case '\\' => sb += '\\'
        isLastEscape = false
      end if
      i += 1
    end while
    sb.toString

  /** Escape a string -- the opposite of unescape */
  def escape(s: String): String =
    val sb = new StringBuilder

    var i = 0
    while i < s.size do
      val c = s(i)
      sb ++= escapeChar(c)
      i += 1
    end while
    sb.toString

  def unescapeChar(s: String): Char =
    assert(s.size == 1 || s.size == 2, s)

    if s(0) == '\\' then
      assert(s.size == 2)
      s(1) match
        case 'b'  => '\b'
        case 'f'  => '\f'
        case 'n'  => '\n'
        case 'r'  => '\r'
        case 't'  => '\t'
        case '\''  => '\''
        case '\\' => '\\'
    else
      assert(s.size == 1)
      s(0)

  def escapeChar(c: Char): String =
    c match
      case '\b'  =>  "\\b"
      case '\f'  =>  "\\f"
      case '\n'  =>  "\\n"
      case '\r'  =>  "\\r"
      case '\t'  =>  "\\t"
      case '"'   =>  "\\\""
      case '\\'  =>  "\\\\"
      case _     =>  c.toString

  def utf8Length(str: String): Int =
    var len = 0
    var i = 0
    while i < str.length do
      val codePoint = str.codePointAt(i)

      if codePoint <= 0x7F then
        len += 1

      else if codePoint <= 0x7FF then
        len += 2

      else if codePoint <= 0xFFFF then
        len += 3

      else
        len += 4

      i += Character.charCount(codePoint)
    end while
    len
