package parsing

import ast.Trees.RegexLit
import ast.Positions.Span
import parsing.Tokens.{ Token, TokenInfo, WithSpan }
import reporting.Reporter.error

object Regex:
  private val AllowedFlags: Set[Char] = Set('i', 'm', 's')

  def parseLiteral(item: TokenInfo): RegexLit =
    item.token match
      case Token.TaggedLiteral(name, flagsOpt, source) =>
        if name.value != "r" then
          error(s"Unknown tagged literal #${name.value}", name.span.toPos)

        val flags = validateFlags(flagsOpt)
        val pattern = decodePayload(source)
        RegexLit(pattern, flags)(item.span)

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
            error(s"Unknown regex flag: $ch", Span(span.start + idx, 1).toPos)
          else if seen.contains(ch) then
            error(s"Duplicate regex flag: $ch", Span(span.start + idx, 1).toPos)
          else
            seen += ch
        flags

  /** Decode only the delimiter escape for regex literals.
    * All other backslash sequences are preserved verbatim.
    */
  private def decodePayload(source: WithSpan[String]): String =
    val raw = source.value
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
