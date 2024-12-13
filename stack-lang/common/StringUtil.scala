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
      c match
        case '\b'  => sb ++= "\\b"
        case '\f'  => sb ++= "\\f"
        case '\n'  => sb ++= "\\n"
        case '\r'  => sb ++= "\\r"
        case '\t'  => sb ++= "\\t"
        case '"'   => sb ++= "\\\""
        case '\\'  => sb ++= "\\\\"
        case _     => sb += c
      i += 1
    end while
    sb.toString
