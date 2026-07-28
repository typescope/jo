package tool

import scala.collection.mutable

/** Minimal recursive-descent JSON parser for single-object lines (JSONL). */
object Json:
  def parseObj(input: String): Either[String, Map[String, Any]] =
    parseObject(input, 0) match
      case Right((obj, _)) => Right(obj)
      case Left(msg)       => Left(msg)

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
      if s(i) == '\\' && i + 1 < s.length then
        s(i + 1) match
          case '"'  => sb += '"';  i += 2
          case '\\' => sb += '\\'; i += 2
          case '/'  => sb += '/';  i += 2
          case 'n'  => sb += '\n'; i += 2
          case 'r'  => sb += '\r'; i += 2
          case 't'  => sb += '\t'; i += 2
          case c    => sb += c;    i += 2
      else
        sb += s(i)
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

  private def parseNumber(s: String, i0: Int): Either[String, (String, Int)] =
    var i = i0
    if i < s.length && s(i) == '-' then i += 1
    while i < s.length && s(i).isDigit do i += 1
    Right((s.substring(i0, i), i))
