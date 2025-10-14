package parsing

import Tokens.*
import Scanner.*

import common.StringUtil
import ast.Positions.*
import ast.Naming.*
import reporting.Reporter
import reporting.Reporter.{ error, abortInternal }

/** The scanner interface */
class Scanner(stream: CharStream)(using Reporter, Source):
  def this(code: String)(using Reporter, Source) = this(new CharStream(code))

  def eat(c: Char): Unit =
    val res = stream.eat()
    if c != res then
      error(s"Expect character $c, found : " + res, stream.lastCharSpan().toPos)

  /** Return the token, its span and the line indentation where the token ends */
  def next(): TokenInfo =
    val token = nextToken()
    val span =
      if token == Token.EOF then
        val rawSpan = stream.tokenSpan()
        Span(rawSpan.start - 1, length = 0)
      else
        stream.tokenSpan()

    token.withInfo(span, stream.lineIndent())

  def nextToken(): Token =
    if !stream.hasMore() then return Token.EOF

    // mark the start of a new token
    stream.tokenStart()

    stream.eat() match
      case '('    => Token.LPAREN
      case ')'    => Token.RPAREN
      case '['    => Token.LBRACKET
      case ']'    => Token.RBRACKET
      case '{'    => Token.LBRACE
      case '}'    => Token.RBRACE
      case '#'    => Token.TAG
      case '.'    => dots()
      case ','    => Token.COMMA

      case '-'    =>
        if stream.curCodePoint(isDigit) then intLit()
        else operator()

      case '/'    =>
        if stream.curCodePoint() == '/' then
          stream.eatLine()
          stream.tokenStart()
          nextToken()
        else
          operator()

      case '"'    =>
        stringLit()

      case '\''    =>
        charLit()

      case c      =>
        if      isDigit(c)         then intLit()
        else if isNameStart(c)     then name()
        else if isOperatorChar(c)  then operator()
        else if isSpace(c)         then nextToken()
        else
          error("Unexpected character: " + Character.toString(c), stream.tokenSpan().toPos)
          nextToken()

  def name(): Token =
    stream.eatWhile(isNameRest)

    stream.tokenEnd() match
      case "as"        => Token.AS
      case "if"        => Token.IF
      case "then"      => Token.THEN
      case "else"      => Token.ELSE
      case "match"     => Token.MATCH
      case "case"      => Token.CASE
      case "while"     => Token.WHILE
      case "do"        => Token.DO
      case "end"       => Token.END
      case "val"       => Token.VAL
      case "var"       => Token.VAR
      case "fun"       => Token.FUN
      case "type"      => Token.TYPE
      case "import"    => Token.IMPORT
      case "namespace" => Token.NSPACE
      case "with"      => Token.WITH
      case "param"     => Token.PARAM
      case "allow"     => Token.ALLOW
      case "true"      => Token.BoolLit(true)
      case "false"     => Token.BoolLit(false)
      case "def"       => Token.DEF
      case "receives"  => Token.RECEIVES
      case "pattern"   => Token.PATTERN
      case "section"   => Token.SECTION
      case "data"      => Token.DATA
      case "alias"     => Token.ALIAS
      case "begin"     => Token.BEGIN
      case "auto"      => Token.AUTO
      case "defer"     => Token.DEFER
      case "class"     => Token.CLASS
      case "new"       => Token.NEW
      case name        => Token.Ident(name)

  def operator(): Token =
    stream.eatWhile(c => isOperatorChar(c) && !stream.isComment())

    stream.tokenEnd() match
      case "="   => Token.EQL
      case ":"   => Token.COLON
      case "<:"  => Token.SUBTYPE
      case "=>"  => Token.RARROW
      case name  => Token.Ident(name)

  def dots(): Token =
    stream.eatWhile(_ == '.')

    stream.tokenEnd() match
      case "."    => Token.DOT
      case name   => Token.Ident(name)

  def stringLit(): Token =
    var isLastEscape = false
    def isValidChar(c: Int) =
      if isLastEscape then
        isLastEscape = false
        true
      else
        isLastEscape = c == '\\'
        c != '"'

    stream.eatWhile(c => c != '\n' && isValidChar(c))
    if stream.curCodePoint() == '\n' then
      error("Missing closing double quote: string cannot span multiple lines", stream.tokenSpan().toPos)
    else
      eat('\"')
    val rawString = stream.tokenEnd()
    val content = if rawString.size <= 1 then "" else rawString.substring(1, rawString.size - 1)

    try
      new Token.StringLit(StringUtil.unescape(content))
    catch
      case e: StringUtil.UnicodeEscapeError =>
        // Map the offset in the content to the source position
        // +1 for the opening quote, +e.offset for position in content
        val errorStart = stream.tokenSpan().start + 1 + e.offset
        val errorSpan = Span(errorStart, e.length)
        error(e.message, errorSpan.toPos)
        new Token.StringLit("")  // Return empty string as dummy value

  def charLit(): Token =
    if stream.curCodePoint() == '\\' then
      stream.eat()
      // Check if it's a unicode escape \u{...}
      if stream.curCodePoint() == 'u' then
        stream.eat()
        if stream.curCodePoint() == '{' then
          stream.eat()
          // Consume hex digits until '}'
          while stream.hasMore() && stream.curCodePoint() != '}' && stream.curCodePoint() != '\'' do
            stream.eat()
          if stream.curCodePoint() == '}' then
            stream.eat()
      // else: simple escape like \b \f \n \r \t \' \\
    else
      stream.eat()
    eat('\'')
    val rawString = stream.tokenEnd()
    val content = rawString.substring(1, rawString.size - 1)

    try
      new Token.CharLit(StringUtil.unescapeChar(content))
    catch
      case e: StringUtil.UnicodeEscapeError =>
        // Map the offset in the content to the source position
        // +1 for the opening quote, +e.offset for position in content
        val errorStart = stream.tokenSpan().start + 1 + e.offset
        val errorSpan = Span(errorStart, e.length)
        error(e.message, errorSpan.toPos)
        new Token.CharLit(0.toChar)  // Return a dummy value

  def intLit(): Token.IntLit =
    stream.eatWhile(isDigit)
    val intStr = stream.tokenEnd()
    val value = str2Int(intStr)
    new Token.IntLit(value)

  def str2Int(str: String): Int =
    val first = str(0)
    val length = str.size
    val isNegative = first == '-'

    var sum: Int = 0
    if !isNegative then sum = first - '0'
    var overflow = false

    var i = 1
    while i < length do
      val c = str(i)
      val v = c - '0'
      sum = sum * 10 + (if isNegative then -v else v)

      if !isNegative & sum < 0 then overflow = true
      else if isNegative & sum > 0 then overflow = true

      i += 1
    end while

    if overflow then
      error("Integer literal overflow: " + str, stream.tokenSpan().toPos)

    sum
  end str2Int

object Scanner:
  class CharStream(code: String)(using source: Source):
    /** Length of Unicode basic units in the string */
    private val LEN = code.length

    /** Current index of in the string in terms of Unicode basic units */
    private var index: Int = 0

    /** Offset from start of the string in utf8 encoding */
    private var offset: Int = 0

    /** Current line number, starting from 0 */
    private var lineNum: Int = 0

    /** Indentation of the current line */
    private var lineIndentation: Int = countStartingSpace()

    /** The offset of the current line in utf8 encoding */
    private var curLineOffset: Int = offset

    /** Starting index of the current token in the string in terms of Unicode basic units */
    private var curTokenIndex: Int = -1

    /** Starting offset for the current token in utf8 encoding */
    private var curTokenOffset: Int = -1

    /** Used to create token content */
    private val sb = new StringBuilder

    // add line offset for the starting line
    source.addLineOffset(index)

    def curCodePoint(): Int = code.codePointAt(index)

    def nextCodePoint(): Int =
      val nextIndex = index + Character.charCount(curCodePoint())
      code.codePointAt(nextIndex)

    def eat(): Int =
      val c = curCodePoint()
      offset += StringUtil.utf8CodePointLength(c)
      index += Character.charCount(c)

      if c == '\n' then
        curLineOffset = offset
        lineNum += 1
        source.addLineOffset(offset)
        lineIndentation = countStartingSpace()
      else if !hasMore() then
        source.addLineOffset(offset)
      c

    /** Count starting space from the current position
      *
      * TODO: don't treat tab as 2 spaces
      */
    def countStartingSpace(): Int =
      var count = 0
      var curIndex = index
      while
        curIndex < LEN
        && (code(curIndex) == ' ' || code(curIndex) == '\t')
      do
        count = count + 1
        // 1 tab = 2 space
        if code(curIndex) == '\t' then count = count + 1
        curIndex = curIndex + 1
      end while
      count

    def eatWhile(pred: Int => Boolean): Unit =
      while hasMore() && pred(curCodePoint()) do
        eat()

    def eatLine(): Unit =
      eatWhile(c => c != '\n')
      eat()

    def curCodePoint(pred: Int => Boolean): Boolean =
      hasMore() && pred(curCodePoint())

    def isComment(): Boolean =
      index < LEN - 1 && curCodePoint() == '/' && nextCodePoint() == '/'

    def hasMore(): Boolean = index < LEN

    def tokenStart(): Unit =
      curTokenIndex = index
      curTokenOffset = offset

    def tokenEnd(): String =
      if curTokenIndex == -1 then
        abortInternal("Token is not marked by calling tokenStart()")

      sb.clear()
      var i = curTokenIndex
      while i < index do
        sb += code(i)
        i += 1

      sb.toString()

    def tokenSpan(): Span = Span(curTokenOffset, offset - curTokenOffset)

    def lastCharSpan(): Span = Span(index - 1, 1)

    def lineIndent(): Indent =
      val tokenIndent = curTokenOffset - curLineOffset
      Indent(lineNum, lineIndentation, tokenIndent)
