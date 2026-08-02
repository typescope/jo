package tool

import scala.collection.mutable

/** Minimal recursive-descent JSON parser for single-object lines (JSONL).
 *
 *  Deliberately strict rather than permissive: input comes from a registry
 *  index or a third-party `jo-templates.jsonl` file, both untrusted, so
 *  parser leniency (accepting `\q`, silently truncating trailing garbage,
 *  merging duplicate keys) would make malformed or adversarial input harder
 *  to catch rather than easier. Numbers are represented as `Double` in the
 *  `Any` result, never as `String` — otherwise a caller checking `case s:
 *  String` for a required field would wrongly accept `{"name": 123}`.
 */
object Json:
  def parseObj(input: String): Either[String, Map[String, Any]] =
    parseObject(input, 0).flatMap: (obj, j) =>
      val end = ws(input, j)
      if end != input.length then Left(s"unexpected trailing data at $end")
      else Right(obj)

  private def ws(s: String, i: Int): Int =
    var j = i
    while j < s.length && s(j).isWhitespace do j += 1
    j

  private def parseObject(s: String, i0: Int): Either[String, (Map[String, Any], Int)] =
    var i = ws(s, i0)
    if i >= s.length || s(i) != '{' then return Left(s"expected '{' at $i")
    i = ws(s, i + 1)

    val fields = collection.mutable.LinkedHashMap.empty[String, Any]
    var first = true

    while i < s.length && s(i) != '}' do
      if !first then
        if s(i) != ',' then return Left(s"expected ',' at $i")
        i = ws(s, i + 1)
      first = false

      parseString(s, i) match
        case Left(msg) => return Left(msg)
        case Right((key, j)) =>
          if fields.contains(key) then return Left(s"duplicate key '$key' at $i")
          i = ws(s, j)
          if i >= s.length || s(i) != ':' then return Left(s"expected ':' at $i")
          i = ws(s, i + 1)
          parseValue(s, i) match
            case Left(msg) => return Left(msg)
            case Right((v, j2)) =>
              fields(key) = v
              i = ws(s, j2)

    if i >= s.length then Left("unterminated object")
    else Right((fields.toMap, i + 1))

  private def parseValue(s: String, i: Int): Either[String, (Any, Int)] =
    if i >= s.length then Left("unexpected end of input")
    else s(i) match
      case '"'                                => parseString(s, i)
      case '{'                                => parseObject(s, i)
      case '['                                => parseArray(s, i)
      case 't' if s.startsWith("true", i)    => Right((true,  i + 4))
      case 'f' if s.startsWith("false", i)   => Right((false, i + 5))
      case 'n' if s.startsWith("null", i)    => Right((null,  i + 4))
      case c if c.isDigit || c == '-'        => parseNumber(s, i)
      case c                                  => Left(s"unexpected char '$c' at $i")

  private def parseString(s: String, i0: Int): Either[String, (String, Int)] =
    if i0 >= s.length || s(i0) != '"' then return Left(s"expected '\"' at $i0")
    val sb = new StringBuilder
    var i = i0 + 1

    while i < s.length && s(i) != '"' do
      val c = s(i)

      if c == '\\' then
        if i + 1 >= s.length then return Left(s"unterminated escape at $i")

        s(i + 1) match
          case '"'  => sb += '"';  i += 2
          case '\\' => sb += '\\'; i += 2
          case '/'  => sb += '/';  i += 2
          case 'b'  => sb += '\b'; i += 2
          case 'f'  => sb += '\f'; i += 2
          case 'n'  => sb += '\n'; i += 2
          case 'r'  => sb += '\r'; i += 2
          case 't'  => sb += '\t'; i += 2

          case 'u' =>
            if i + 6 > s.length then return Left(s"incomplete unicode escape at $i")
            val hex = s.substring(i + 2, i + 6)
            val code =
              try Integer.parseInt(hex, 16)
              catch case _: NumberFormatException => return Left(s"invalid unicode escape '\\u$hex' at $i")
            sb += code.toChar
            i += 6

          case other => return Left(s"invalid escape '\\$other' at $i")

      else if c < ' ' then
        return Left(s"unescaped control character at $i")

      else
        sb += c
        i += 1

    if i >= s.length then Left("unterminated string")
    else Right((sb.toString, i + 1))

  private def parseArray(s: String, i0: Int): Either[String, (List[Any], Int)] =
    var i = ws(s, i0 + 1)
    val items = new mutable.ArrayBuffer[Any]
    var first = true
    while i < s.length && s(i) != ']' do
      if !first then
        if s(i) != ',' then return Left(s"expected ',' at $i")
        i = ws(s, i + 1)
      first = false
      parseValue(s, i) match
        case Left(msg) => return Left(msg)
        case Right((v, j)) =>
          items += v
          i = ws(s, j)
    if i >= s.length then Left("unterminated array")
    else Right((items.toList, i + 1))

  /** Full JSON number grammar: `-`? int frac? exp?, with `int` being either
   *  `0` or a non-zero digit followed by more digits (no leading zeros). A
   *  lone `-` or any other malformed shape is rejected here rather than
   *  silently accepted as a truncated "number".
   */
  private def parseNumber(s: String, i0: Int): Either[String, (Double, Int)] =
    var i = i0
    if i < s.length && s(i) == '-' then i += 1

    if i >= s.length || !s(i).isDigit then return Left(s"invalid number at $i0")

    if s(i) == '0' then i += 1
    else while i < s.length && s(i).isDigit do i += 1

    if i < s.length && s(i) == '.' then
      i += 1
      if i >= s.length || !s(i).isDigit then return Left(s"invalid number at $i0")
      while i < s.length && s(i).isDigit do i += 1

    if i < s.length && (s(i) == 'e' || s(i) == 'E') then
      i += 1
      if i < s.length && (s(i) == '+' || s(i) == '-') then i += 1
      if i >= s.length || !s(i).isDigit then return Left(s"invalid number at $i0")
      while i < s.length && s(i).isDigit do i += 1

    val text = s.substring(i0, i)
    try Right((text.toDouble, i))
    catch case _: NumberFormatException => Left(s"invalid number '$text' at $i0")
