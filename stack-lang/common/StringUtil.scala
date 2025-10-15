package common

object StringUtil:
  /** Exception thrown when parsing escape sequences fails
    *
    * @param message Error message
    * @param offset Offset in the string where the error occurred
    * @param length Length of the problematic escape sequence
    */
  class EscapeError(val message: String, val offset: Int, val length: Int)
    extends Exception(message)

  /** Policy for which escape sequences to process
    *
    * Enable(chars) - Only process escapes in chars string (e.g., Enable("u") = only unicode)
    * Disable(chars) - Process all escapes except those in chars string
    */
  enum EscapePolicy:
    case Enable(chars: String)
    case Disable(chars: String)

  /** Check if a character is a valid hexadecimal digit */
  def isHexDigit(c: Int): Boolean =
    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  /** Translate escape sequences in the string
    *
    * @param s The string to unescape
    * @param policy Which escape sequences to process:
    *               - Enable(chars): Only process escapes in chars (e.g., Enable("u") for unicode only)
    *               - Disable(chars): Process all escapes except those in chars
    *               - Default: Disable("") processes all standard escapes
    *
    * Java 15 added String.translateEscapes. Don't depend on it for clarity.
    */
  def unescape(s: String, policy: EscapePolicy = EscapePolicy.Disable("")): String =
    val sb = new java.lang.StringBuilder
    var isLastEscape = false

    var i = 0
    while i < s.size do
      val c = s.codePointAt(i)
      if i == 0 || !isLastEscape then
        if c == '\\' then isLastEscape = true
        else sb.appendCodePoint(c)
      else
        val charCode = c.toChar
        val isKnownEscape = "bfnrt\"\\u".contains(charCode)

        val shouldProcess = policy match
          case EscapePolicy.Enable(chars) => chars.contains(charCode)
          case EscapePolicy.Disable(chars) => !chars.contains(charCode)

        if !shouldProcess then
          // This escape is disabled - treat as literal
          sb.append('\\')
          sb.appendCodePoint(c)
          isLastEscape = false
        else if !isKnownEscape then
          // Unknown escape sequence
          val escapeStart = i - 1
          val charDesc = c match
            case ' '  => "space"
            case '\t' => "tab"
            case '\n' => "newline"
            case '\r' => "carriage return"
            case _    => Character.toString(c)
          throw new EscapeError(
            s"Unknown escape sequence: \\$charDesc",
            escapeStart,
            2
          )
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
              // Unicode escape: \u{X...} (1-6 hex digits)
              val escapeStart = i - 1  // Position of the backslash
              if i + 1 >= s.size || s.charAt(i + 1) != '{' then
                throw new EscapeError(
                  "Unicode escape must be followed by '{': \\u{...}",
                  escapeStart,
                  2  // \u
                )

              // Find closing brace
              var j = i + 2
              while j < s.size && s.charAt(j) != '}' do
                j += 1

              if j >= s.size then
                throw new EscapeError(
                  "Unclosed unicode escape sequence",
                  escapeStart,
                  j - escapeStart
                )

              val hexStr = s.substring(i + 2, j)
              if hexStr.isEmpty then
                throw new EscapeError(
                  "Empty unicode escape sequence",
                  escapeStart,
                  4  // \u{}
                )

              if hexStr.length > 6 then
                throw new EscapeError(
                  s"Unicode escape sequence too long (max 6 hex digits): $hexStr",
                  escapeStart,
                  j - escapeStart + 1
                )

              // Validate all characters are hex digits
              var k = 0
              while k < hexStr.length do
                if !isHexDigit(hexStr.charAt(k)) then
                  throw new EscapeError(
                    s"Invalid hex digit in unicode escape: ${hexStr.charAt(k)}",
                    escapeStart,
                    j - escapeStart + 1
                  )
                k += 1

              val codePoint = Integer.parseInt(hexStr, 16)
              if codePoint > 0x10FFFF then
                throw new EscapeError(
                  f"Unicode code point out of range (max 10FFFF): $codePoint%X",
                  escapeStart,
                  j - escapeStart + 1
                )

              sb.appendCodePoint(codePoint)
              i = j  // skip to closing brace (will be incremented at end of loop)
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
    * Supports full Unicode range (U+0000 to U+10FFFF) by returning an Int
    * representing the code point value.
    */
  def unescapeChar(s: String): Int =
    if s.size == 1 then
      return s(0).toInt

    if s(0) != '\\' then
      throw new EscapeError(
        "Expected escape sequence starting with \\",
        0,
        s.size
      )

    if s.size == 2 then
      s(1) match
        case 'b'  => return '\b'.toInt
        case 'f'  => return '\f'.toInt
        case 'n'  => return '\n'.toInt
        case 'r'  => return '\r'.toInt
        case 't'  => return '\t'.toInt
        case '\''  => return '\''.toInt
        case '\\' => return '\\'.toInt
        case _    =>
          throw new EscapeError(
            s"Unknown escape sequence: \\${s(1)}",
            0,
            2
          )

    // Unicode escape: \u{...}
    if s(1) != 'u' then
      throw new EscapeError(
        s"Unknown escape sequence: \\${s(1)}",
        0,
        2
      )

    if s.size < 4 || s(2) != '{' then
      throw new EscapeError(
        "Unicode escape must be \\u{...}",
        0,
        if s.size < 3 then s.size else 3
      )

    if s(s.size - 1) != '}' then
      throw new EscapeError(
        "Unclosed unicode escape sequence",
        0,
        s.size
      )

    val hexStr = s.substring(3, s.size - 1)
    if hexStr.isEmpty then
      throw new EscapeError(
        "Empty unicode escape sequence",
        0,
        4
      )

    if hexStr.length > 6 then
      throw new EscapeError(
        s"Unicode escape sequence too long (max 6 hex digits): $hexStr",
        0,
        s.size
      )

    // Validate all characters are hex digits
    var k = 0
    while k < hexStr.length do
      if !isHexDigit(hexStr.charAt(k)) then
        throw new EscapeError(
          s"Invalid hex digit in unicode escape: ${hexStr.charAt(k)}",
          0,
          s.size
        )
      k += 1

    val codePoint = Integer.parseInt(hexStr, 16)
    if codePoint > 0x10FFFF then
      throw new EscapeError(
        f"Unicode code point out of range (max 10FFFF): $codePoint%X",
        0,
        s.size
      )

    codePoint

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
