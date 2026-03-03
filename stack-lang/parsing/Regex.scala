package parsing

import ast.Positions.Span
import ast.Trees.RegexLit
import parsing.Tokens.{Token, TokenInfo, WithSpan}
import reporting.Reporter.error

object Regex:
  private val AllowedFlags: Set[Char] = Set('i', 'm', 's')

  def parseLiteral(item: TokenInfo): RegexLit =
    item.token match
      case Token.TaggedLiteral(name, flagsOpt, source) =>
        if name.value != "r" then
          error(s"Unknown tagged literal #${name.value}", name.span.toPos)

        val flags = validateFlags(flagsOpt)
        validatePattern(source)
        RegexLit(decodePayload(source.value), flags)(item.span)

      case other =>
        error("Expect tagged literal, found = " + other, item.span.toPos)
        RegexLit("", "")(item.span)

  private def validateFlags(flagsOpt: Option[WithSpan[String]]): String =
    flagsOpt match
      case None => ""
      case Some(WithSpan(flags, span)) =>
        val seen = scala.collection.mutable.HashSet.empty[Char]
        for (ch, idx) <- flags.zipWithIndex do
          if !AllowedFlags.contains(ch) then
            error(s"Unknown regex flag: $ch", at(span, idx))
          else if seen.contains(ch) then
            error(s"Duplicate regex flag: $ch", at(span, idx))
          else
            seen += ch
        flags

  private def validatePattern(source: WithSpan[String]): Unit =
    new Validator(source.value, source.span).validate()

  /** Decode only the delimiter escape for regex literals.
    * All other backslash sequences are preserved verbatim.
    */
  private def decodePayload(raw: String): String =
    val out = new StringBuilder(raw.length)
    var i = 0
    while i < raw.length do
      val c = raw.charAt(i)
      if c == '\\' && i + 1 < raw.length && raw.charAt(i + 1) == '"' then
        out += '"'
        i += 2
      else
        out += c
        i += 1
    out.toString()

  private def at(span: Span, offset: Int, length: Int = 1): ast.SourcePosition =
    Span(span.start + offset, length).toPos

  private final class Validator(raw: String, rawSpan: Span):
    private var i = 0

    def validate(): Unit =
      parseAlternation(top = true)
      if i < raw.length then
        error(s"Unexpected character '${raw.charAt(i)}' in regex", at(rawSpan, i))

    private def parseAlternation(top: Boolean): Unit =
      parseSequence()
      while i < raw.length && raw.charAt(i) == '|' do
        i += 1
        parseSequence()
      if !top && i < raw.length && raw.charAt(i) != ')' then
        error("Expected ')' in regex", at(rawSpan, i))

    private def parseSequence(): Unit =
      while i < raw.length && raw.charAt(i) != ')' && raw.charAt(i) != '|' do
        parsePiece()

    private def parsePiece(): Unit =
      parseAtom()
      if i < raw.length then
        raw.charAt(i) match
          case '*' | '+' | '?' =>
            i += 1
            rejectQuantifierSuffix()
          case '{' =>
            parseBounds()
          case _ =>
            ()
      end

    private def rejectQuantifierSuffix(): Unit =
      if i < raw.length then
        raw.charAt(i) match
          case '?' =>
            error("Lazy quantifiers are not supported in regex literals", at(rawSpan, i))
            i += 1
          case '+' =>
            error("Possessive quantifiers are not supported in regex literals", at(rawSpan, i))
            i += 1
          case _ =>
            ()

    private def parseAtom(): Unit =
      if i >= raw.length then
        error("Unexpected end of regex", rawSpan.endPoint.toPos)
      else
        raw.charAt(i) match
          case '(' =>
            val groupStart = i
            i += 1
            if i < raw.length && raw.charAt(i) == '?' then
              i += 1
              if i < raw.length && raw.charAt(i) == ':' then
                i += 1
              else
                error("Unsupported group syntax in regex", at(rawSpan, groupStart, math.min(2, raw.length - groupStart)))
            end
            parseAlternation(top = false)
            if i >= raw.length || raw.charAt(i) != ')' then
              error("Unclosed group in regex", at(rawSpan, groupStart))
            else
              i += 1

          case '[' =>
            parseCharClass()

          case '\\' =>
            parseEscape()

          case ')' | ']' | '*' | '+' | '?' | '{' =>
            error(s"Unexpected character '${raw.charAt(i)}' in regex", at(rawSpan, i))
            i += 1

          case _ =>
            i += 1

    private def parseCharClass(): Unit =
      val classStart = i
      i += 1
      if i < raw.length && raw.charAt(i) == '^' then
        i += 1

      var first = true
      var closed = false
      while i < raw.length && !closed do
        raw.charAt(i) match
          case ']' if !first =>
            closed = true
            i += 1

          case '\\' =>
            parseEscape()

          case _ =>
            i += 1
        first = false

      if !closed then
        error("Unclosed character class in regex", at(rawSpan, classStart))

    private def parseEscape(): Unit =
      val escapePos = i
      i += 1
      if i >= raw.length then
        error("Dangling escape at end of regex", at(rawSpan, escapePos))
      else
        val ch = raw.charAt(i)
        if ch == '"' then
          // Delimiter escape for the outer literal. This becomes a plain quote in
          // the regex payload and should not be validated as a regex escape.
          i += 1
        else
          if ch.isDigit then
            error("Back references are not supported in regex literals", at(rawSpan, escapePos, 2))
          else if ch == 'p' || ch == 'P' then
            error("Unicode classes are not supported in regex literals", at(rawSpan, escapePos, 2))
          else if isHostSpecificEscape(ch) then
            error("Host-specific escapes are not supported in regex literals", at(rawSpan, escapePos, 2))
          i += 1

    private def parseBounds(): Unit =
      val boundsStart = i
      i += 1
      val min = parseNumber()
      if min.isEmpty then
        error("Expected repetition bound in regex", at(rawSpan, boundsStart))
      if i < raw.length && raw.charAt(i) == '}' then
        i += 1
        rejectQuantifierSuffix()
      else if i < raw.length && raw.charAt(i) == ',' then
        i += 1
        val max =
          if i < raw.length && raw.charAt(i) == '}' then None
          else parseNumber()
        if i < raw.length && raw.charAt(i) != '}' && max.isEmpty then
          error("Expected upper bound in regex repetition", at(rawSpan, boundsStart))
        else if min.nonEmpty && max.nonEmpty && min.get > max.get then
          error("Invalid bounded repetition: min > max", at(rawSpan, boundsStart))
        if i >= raw.length || raw.charAt(i) != '}' then
          error("Unclosed bounded repetition in regex", at(rawSpan, boundsStart))
        else
          i += 1
          rejectQuantifierSuffix()
      else
        error("Invalid bounded repetition in regex", at(rawSpan, boundsStart))

    private def parseNumber(): Option[Int] =
      if i >= raw.length || !raw.charAt(i).isDigit then None
      else
        val start = i
        while i < raw.length && raw.charAt(i).isDigit do
          i += 1
        Some(raw.substring(start, i).toInt)

    private def isHostSpecificEscape(ch: Char): Boolean =
      ch == 'Q' || ch == 'E' || ch == 'A' || ch == 'Z' || ch == 'z' ||
      ch == 'G' || ch == 'K' || ch == 'R' || ch == 'X' || ch == 'k'
