package common

object StringUtil:
  /** Check if a character is a valid hexadecimal digit */
  def isHexDigit(c: Char): Boolean =
    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  /** Translate escape in the string
    *
    * Java 15 added String.translateEscapes. Don't depend on it for clarity.
    */
  def unescape(s: String): String =
    val sb = new java.lang.StringBuilder
    var isLastEscape = false

    var i = 0
    while i < s.size do
      val c = s.codePointAt(i)
      if i == 0 || !isLastEscape then
        if c == '\\' then isLastEscape = true
        else sb.appendCodePoint(c)
      else
        c match
          case 'b'  => sb.append('\b')
          case 'f'  => sb.append('\f')
          case 'n'  => sb.append('\n')
          case 'r'  => sb.append('\r')
          case 't'  => sb.append('\t')
          case '"'  => sb.append('"')
          case '\\' => sb.append('\\')
          case 'u'  =>
            // Unicode escape: \uXXXX
            if i + 4 < s.size then
              val hex1 = s.charAt(i + 1)
              val hex2 = s.charAt(i + 2)
              val hex3 = s.charAt(i + 3)
              val hex4 = s.charAt(i + 4)
              if isHexDigit(hex1) && isHexDigit(hex2) && isHexDigit(hex3) && isHexDigit(hex4) then
                val hexStr = s.substring(i + 1, i + 5)
                val codePoint = Integer.parseInt(hexStr, 16)
                sb.appendCodePoint(codePoint)
                i += 4  // skip the 4 hex digits
              else
                throw new IllegalArgumentException(s"Invalid unicode escape sequence: \\u$hex1$hex2$hex3$hex4")
            else
              throw new IllegalArgumentException("Incomplete unicode escape sequence: \\u")
          case _    =>
            // Unknown escape sequence, keep as-is
            sb.append('\\')
            sb.appendCodePoint(c)
        isLastEscape = false
      end if
      i += Character.charCount(c)
    end while
    sb.toString

  /** Escape a string -- the opposite of unescape */
  def escape(s: String): String =
    val sb = new StringBuilder

    var i = 0
    while i < s.size do
      val c = s.codePointAt(i)
      sb ++= escapeChar(c)
      i += Character.charCount(c)
    end while
    sb.toString

  /** Handle escaped char
    *
    * It only supports BMP unicode points, no surrogate code points
    *
    * TODO: Surrogate code points can be supported if the language interpret
    * char literal as 32-bit integers.
    */
  def unescapeChar(s: String): Char =
    assert(s.size == 1 || s.size == 2 || s.size == 6, s)

    if s(0) == '\\' then
      if s.size == 6 && s(1) == 'u' then
        // Unicode escape: \uXXXX
        val hex1 = s.charAt(2)
        val hex2 = s.charAt(3)
        val hex3 = s.charAt(4)
        val hex4 = s.charAt(5)
        if isHexDigit(hex1) && isHexDigit(hex2) && isHexDigit(hex3) && isHexDigit(hex4) then
          val hexStr = s.substring(2, 6)
          Integer.parseInt(hexStr, 16).toChar
        else
          throw new IllegalArgumentException(s"Invalid unicode escape sequence: \\u$hex1$hex2$hex3$hex4")
      else
        assert(s.size == 2)
        s(1) match
          case 'b'  => '\b'
          case 'f'  => '\f'
          case 'n'  => '\n'
          case 'r'  => '\r'
          case 't'  => '\t'
          case '\''  => '\''
          case '\\' => '\\'
          case _    => s(1)  // Unknown escape, return the character itself
    else
      assert(s.size == 1)
      s(0)

  def escapeChar(c: Int): String =
    c match
      case '\b'  =>  "\\b"
      case '\f'  =>  "\\f"
      case '\n'  =>  "\\n"
      case '\r'  =>  "\\r"
      case '\t'  =>  "\\t"
      case '"'   =>  "\\\""
      case '\\'  =>  "\\\\"
      case _     =>  Character.toString(c)

  def utf8CodePointLength(codePoint: Int): Int =
    if codePoint <= 0x7F then
      1

    else if codePoint <= 0x7FF then
      2

    else if codePoint <= 0xFFFF then
      3

    else
      4

  def utf8Length(str: String): Int =
    var len = 0
    var i = 0
    while i < str.length do
      val codePoint = str.codePointAt(i)
      len += utf8CodePointLength(codePoint)
      i += Character.charCount(codePoint)
    end while
    len

  /** Convert a name to Pascal-case
    *
    * some_cat   ->    SomeCat
    * some-cat   ->    SomeCat
    */
  def toPascalCase(input: String): String =
    val sb = new java.lang.StringBuilder
    var capitalizeNext = true

    val it = input.codePoints().iterator()
    while it.hasNext do
      val codePoint = it.next()
      if codePoint == '_' || codePoint == '-' then
        capitalizeNext = true
      else
        val adjusted =
          if capitalizeNext then
            capitalizeNext = false
            Character.toUpperCase(codePoint)
          else
            Character.toLowerCase(codePoint)
        sb.appendCodePoint(adjusted)

    sb.toString()
