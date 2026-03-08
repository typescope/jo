package parsing

import Tokens.{Token, TokenInfo, WithSpan}
import common.StringUtil

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
    private var index = 0        // UTF-16 code-unit index in `raw`
    private var offset = 0       // UTF-8 byte offset in `raw`
    private val groupNames = mutable.LinkedHashMap.empty[String, Ident]

    def validate(): List[Ident] =
      parseAlternation(top = true)
      if !atEnd then
        error(s"Unexpected character '${curChar}' in regex", atSpan(rawSpan, offset, curByteLen).toPos)
      groupNames.values.toList

    private def atEnd: Boolean =
      index >= raw.length

    private def curCodePoint: Int =
      raw.codePointAt(index)

    private def curByteLen: Int =
      StringUtil.utf8CodePointLength(curCodePoint)

    private def curChar: Char =
      curCodePoint.toChar

    private def advance(): Unit =
      val cp = raw.codePointAt(index)
      index += Character.charCount(cp)
      offset += StringUtil.utf8CodePointLength(cp)

    private def parseAlternation(top: Boolean): Unit =
      parseSequence()
      while !atEnd && curChar == '|' do
        advance()
        parseSequence()
      if !top && !atEnd && curChar != ')' then
        error("Expected ')' in regex", atSpan(rawSpan, offset, curByteLen).toPos)

    private def parseSequence(): Unit =
      while !atEnd && curChar != ')' && curChar != '|' do
        parsePiece()

    private def parsePiece(): Unit =
      parseAtom()
      if !atEnd then
        curChar match
          case '*' | '+' | '?' =>
            advance()
            rejectQuantifierSuffix()
          case '{' =>
            parseBounds()
          case _ =>
            ()

    private def rejectQuantifierSuffix(): Unit =
      if !atEnd then
        curChar match
          case '?' =>
            advance()
          case '+' =>
            error("Possessive quantifiers are not supported in regex literals", atSpan(rawSpan, offset, curByteLen).toPos)
            advance()
          case _ =>
            ()

    private def parseAtom(): Unit =
      if atEnd then
        error("Unexpected end of regex", rawSpan.endPoint.toPos)
      else
        curChar match
          case '(' =>
            val groupStart = offset
            advance()
            if !atEnd && curChar == '?' then
              advance()
              if !atEnd && curChar == ':' then
                advance()
              else if !atEnd && curChar == '<' then
                advance()
                if atEnd then
                  error("Unclosed group in regex", atSpan(rawSpan, groupStart, 1).toPos)
                else if curChar == '>' then
                  error("Empty capture name in regex", atSpan(rawSpan, offset, curByteLen).toPos)
                  advance()
                else if !isValidGroupNameStart(curChar) then
                  error(s"Unexpected character '${curChar}' in regex, expected group name", atSpan(rawSpan, offset, curByteLen).toPos)
                else
                  val nameStart = offset
                  val name = new StringBuilder
                  while !atEnd && curChar != '>' do
                    name += curChar
                    advance()
                  if atEnd then
                    error("Unclosed group in regex", atSpan(rawSpan, groupStart, 1).toPos)
                  else if name.isEmpty then
                    error("Empty capture name in regex", atSpan(rawSpan, nameStart, 1).toPos)
                    advance()
                  else
                    val text = name.toString()
                    val nameLen = offset - nameStart
                    if !isValidGroupName(text) then
                      error("Invalid capture group name in regex", atSpan(rawSpan, nameStart, nameLen).toPos)
                    else if groupNames.contains(text) then
                      error(s"Duplicate capture group name: $text", atSpan(rawSpan, nameStart, nameLen).toPos)
                    else
                      groupNames(text) = Ident(text)(atSpan(rawSpan, nameStart, nameLen))
                    advance()
              else
                error("Unsupported group syntax in regex", atSpan(rawSpan, groupStart, 2).toPos)
            parseAlternation(top = false)
            if atEnd || curChar != ')' then
              error("Unclosed group in regex", atSpan(rawSpan, groupStart, 1).toPos)
            else
              advance()

          case '[' =>
            parseCharClass()

          case '\\' =>
            parseEscape()

          case ')' | ']' | '*' | '+' | '?' | '{' =>
            error(s"Unexpected character '${curChar}' in regex", atSpan(rawSpan, offset, curByteLen).toPos)
            advance()

          case _ =>
            advance()

    private def parseCharClass(): Unit =
      val classStart = offset
      advance()
      if !atEnd && curChar == '^' then
        advance()

      var first = true
      var closed = false
      while !atEnd && !closed do
        curChar match
          case ']' if !first =>
            closed = true
            advance()

          case '\\' =>
            parseEscape()

          case _ =>
            advance()
        first = false

      if !closed then
        error("Unclosed character class in regex", atSpan(rawSpan, classStart, 1).toPos)

    private def parseEscape(): Unit =
      val escapePos = offset
      advance()
      if atEnd then
        error("Dangling escape at end of regex", atSpan(rawSpan, escapePos, 1).toPos)
      else
        val ch = curChar
        if ch == '"' then
          // Delimiter escape for the outer literal. This becomes a plain quote in
          // the regex payload and should not be validated as a regex escape.
          advance()
        else
          val escLen = 1 + curByteLen
          if ch.isDigit then
            error("Back references are not supported in regex literals", atSpan(rawSpan, escapePos, escLen).toPos)
          else if ch == 'p' || ch == 'P' then
            error("Unicode classes are not supported in regex literals", atSpan(rawSpan, escapePos, escLen).toPos)
            advance()
            if !atEnd && curChar == '{' then
              advance()
              while !atEnd && curChar != '}' do
                advance()
              if !atEnd && curChar == '}' then
                advance()
          else if !isSupportedEscape(ch) then
            error("Unsupported escape in regex literal", atSpan(rawSpan, escapePos, escLen).toPos)
            advance()
          else
            advance()

    private def parseBounds(): Unit =
      val boundsStart = offset
      advance()
      val min = parseNumber()
      if min.isEmpty then
        error("Expected repetition bound in regex", atSpan(rawSpan, boundsStart, 1).toPos)
      if !atEnd && curChar == '}' then
        advance()
        rejectQuantifierSuffix()
      else if !atEnd && curChar == ',' then
        advance()
        val max =
          if !atEnd && curChar == '}' then None
          else parseNumber()
        if !atEnd && curChar != '}' && max.isEmpty then
          error("Expected upper bound in regex repetition", atSpan(rawSpan, boundsStart, 1).toPos)
        else if min.nonEmpty && max.nonEmpty && min.get > max.get then
          error("Invalid bounded repetition: min > max", atSpan(rawSpan, boundsStart, 1).toPos)
        if atEnd || curChar != '}' then
          error("Unclosed bounded repetition in regex", atSpan(rawSpan, boundsStart, 1).toPos)
        else
          advance()
          rejectQuantifierSuffix()
      else
        error("Invalid bounded repetition in regex", atSpan(rawSpan, boundsStart, 1).toPos)

    private def parseNumber(): Option[Int] =
      if atEnd || !curChar.isDigit then None
      else
        val start = index
        while !atEnd && curChar.isDigit do
          advance()
        Some(raw.substring(start, index).toInt)

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
