package parsing

import Tokens.{Token, TokenInfo, WithSpan}

import ast.Positions.{Source, Span}
import ast.Trees.*
import reporting.Reporter
import reporting.Reporter.error

import scala.collection.mutable

object Regex:
  private val AllowedFlags: Set[Char] = Set('i', 'm', 's')

  def parseLiteral(item: TokenInfo)(using Source, Reporter): RegexLit =
    item.token match
      case Token.TaggedLiteral(name, flagsOpt, source) =>
        if name.value != "r" then
          error(s"Unknown tagged literal #${name.value}", name.span.toPos)

        val flags = validateFlags(flagsOpt)
        val groupNames = validatePattern(source)
        RegexLit(decodePayload(source.value), flags, groupNames)(item.span)

      case other =>
        error("Expect tagged literal, found = " + other, item.span.toPos)
        RegexLit("", "", Nil)(item.span)

  private def validateFlags(flagsOpt: Option[WithSpan[String]])(using Source, Reporter): String =
    flagsOpt match
      case None => ""
      case Some(WithSpan(flags, span)) =>
        val seen = mutable.Set.empty[Char]
        for (ch, idx) <- flags.zipWithIndex do
          if !AllowedFlags.contains(ch) then
            error(s"Unknown regex flag: $ch", atSpan(span, idx, 1).toPos)
          else if seen.contains(ch) then
            error(s"Duplicate regex flag: $ch", atSpan(span, idx, 1).toPos)
          else
            seen += ch
        flags

  private def validatePattern(source: WithSpan[String])(using Source, Reporter): List[Ident] =
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

  private def atSpan(span: Span, offset: Int, length: Int): Span =
    Span(span.start + offset, length)

  private final class Validator(raw: String, rawSpan: Span)(using Source, Reporter):
    private var i = 0
    private val groupNames = mutable.LinkedHashMap.empty[String, Ident]

    def validate(): List[Ident] =
      parseAlternation(top = true)
      if i < raw.length then
        error(s"Unexpected character '${raw.charAt(i)}' in regex", atSpan(rawSpan, i, 1).toPos)
      groupNames.values.toList

    private def parseAlternation(top: Boolean): Unit =
      parseSequence()
      while i < raw.length && raw.charAt(i) == '|' do
        i += 1
        parseSequence()
      if !top && i < raw.length && raw.charAt(i) != ')' then
        error("Expected ')' in regex", atSpan(rawSpan, i, 1).toPos)

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

    private def rejectQuantifierSuffix(): Unit =
      if i < raw.length then
        raw.charAt(i) match
          case '?' =>
            i += 1
          case '+' =>
            error("Possessive quantifiers are not supported in regex literals", atSpan(rawSpan, i, 1).toPos)
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
              else if i < raw.length && raw.charAt(i) == '<' then
                i += 1
                if i >= raw.length then
                  error("Unclosed group in regex", atSpan(rawSpan, groupStart, 1).toPos)
                else if raw.charAt(i) == '>' then
                  error("Empty capture name in regex", atSpan(rawSpan, i, 1).toPos)
                  i += 1
                else if !isValidGroupNameStart(raw.charAt(i)) then
                  error(s"Unexpected character '${raw.charAt(i)}' in regex, expected group name", atSpan(rawSpan, i, 1).toPos)
                else
                  val nameStart = i
                  val name = new StringBuilder
                  while i < raw.length && raw.charAt(i) != '>' do
                    name += raw.charAt(i)
                    i += 1
                  if i >= raw.length then
                    error("Unclosed group in regex", atSpan(rawSpan, groupStart, 1).toPos)
                  else if name.isEmpty then
                    error("Empty capture name in regex", atSpan(rawSpan, nameStart, 1).toPos)
                    i += 1
                  else
                    val text = name.toString()
                    if !isValidGroupName(text) then
                      error("Invalid capture group name in regex", atSpan(rawSpan, nameStart, text.length).toPos)
                    else if groupNames.contains(text) then
                      error(s"Duplicate capture group name: $text", atSpan(rawSpan, nameStart, text.length).toPos)
                    else
                      groupNames(text) = Ident(text)(atSpan(rawSpan, nameStart, text.length))
                    i += 1
              else
                error("Unsupported group syntax in regex", atSpan(rawSpan, groupStart, math.min(2, raw.length - groupStart)).toPos)
            parseAlternation(top = false)
            if i >= raw.length || raw.charAt(i) != ')' then
              error("Unclosed group in regex", atSpan(rawSpan, groupStart, 1).toPos)
            else
              i += 1

          case '[' =>
            parseCharClass()

          case '\\' =>
            parseEscape()

          case ')' | ']' | '*' | '+' | '?' | '{' =>
            error(s"Unexpected character '${raw.charAt(i)}' in regex", atSpan(rawSpan, i, 1).toPos)
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
        error("Unclosed character class in regex", atSpan(rawSpan, classStart, 1).toPos)

    private def parseEscape(): Unit =
      val escapePos = i
      i += 1
      if i >= raw.length then
        error("Dangling escape at end of regex", atSpan(rawSpan, escapePos, 1).toPos)
      else
        val ch = raw.charAt(i)
        if ch == '"' then
          // Delimiter escape for the outer literal. This becomes a plain quote in
          // the regex payload and should not be validated as a regex escape.
          i += 1
        else
          if ch.isDigit then
            error("Back references are not supported in regex literals", atSpan(rawSpan, escapePos, 2).toPos)
          else if ch == 'p' || ch == 'P' then
            error("Unicode classes are not supported in regex literals", atSpan(rawSpan, escapePos, 2).toPos)
            i += 1
            if i < raw.length && raw.charAt(i) == '{' then
              i += 1
              while i < raw.length && raw.charAt(i) != '}' do
                i += 1
              if i < raw.length && raw.charAt(i) == '}' then
                i += 1
          else if !isSupportedEscape(ch) then
            error("Unsupported escape in regex literal", atSpan(rawSpan, escapePos, 2).toPos)
            i += 1
          else
            i += 1

    private def parseBounds(): Unit =
      val boundsStart = i
      i += 1
      val min = parseNumber()
      if min.isEmpty then
        error("Expected repetition bound in regex", atSpan(rawSpan, boundsStart, 1).toPos)
      if i < raw.length && raw.charAt(i) == '}' then
        i += 1
        rejectQuantifierSuffix()
      else if i < raw.length && raw.charAt(i) == ',' then
        i += 1
        val max =
          if i < raw.length && raw.charAt(i) == '}' then None
          else parseNumber()
        if i < raw.length && raw.charAt(i) != '}' && max.isEmpty then
          error("Expected upper bound in regex repetition", atSpan(rawSpan, boundsStart, 1).toPos)
        else if min.nonEmpty && max.nonEmpty && min.get > max.get then
          error("Invalid bounded repetition: min > max", atSpan(rawSpan, boundsStart, 1).toPos)
        if i >= raw.length || raw.charAt(i) != '}' then
          error("Unclosed bounded repetition in regex", atSpan(rawSpan, boundsStart, 1).toPos)
        else
          i += 1
          rejectQuantifierSuffix()
      else
        error("Invalid bounded repetition in regex", atSpan(rawSpan, boundsStart, 1).toPos)

    private def parseNumber(): Option[Int] =
      if i >= raw.length || !raw.charAt(i).isDigit then None
      else
        val start = i
        while i < raw.length && raw.charAt(i).isDigit do
          i += 1
        Some(raw.substring(start, i).toInt)

    private def isSupportedEscape(ch: Char): Boolean =
      ch == '.' || ch == '*' || ch == '+' || ch == '?' ||
      ch == '(' || ch == ')' || ch == '[' || ch == ']' ||
      ch == '{' || ch == '}' || ch == '|' || ch == '\\' ||
      ch == 'n' || ch == 'r' || ch == 't' || ch == 'f' ||
      ch == 'd' || ch == 'D' || ch == 'w' || ch == 'W' ||
      ch == 's' || ch == 'S'

    private def isValidGroupName(name: String): Boolean =
      def isAlpha(ch: Char): Boolean =
        (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
      def isDigit(ch: Char): Boolean =
        ch >= '0' && ch <= '9'

      name.nonEmpty
      && isValidGroupNameStart(name.charAt(0))
      && name.forall(ch => isAlpha(ch) || isDigit(ch) || ch == '_')

    private def isValidGroupNameStart(ch: Char): Boolean =
      (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch == '_'
