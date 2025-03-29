package parsing

import Tokens.*
import Scanner.*

import common.StringUtil
import ast.Positions.*
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
      case '.'    => Token.DOT
      case ','    => Token.COMMA

      case '-'    =>
        if stream.curChar(isDigit) then intLit()
        else operator()

      case '/'    =>
        if stream.curChar() == '/' then
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
        if      isDigit(c)      then intLit()
        else if isNameStart(c)  then name()
        else if isOperator(c)   then operator()
        else if isSpace(c)      then nextToken()
        else
          error("Unexpected character: " + c, stream.tokenSpan().toPos)
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
      case "object"    => Token.OBJECT
      case "def"       => Token.DEF
      case "receives"  => Token.RECEIVES
      case name        => Token.Ident(name)

  def operator(): Token =
    stream.eatWhile(c => isOperator(c) && !stream.isComment())

    stream.tokenEnd() match
      case "="   => Token.EQL
      case ":"   => Token.COLON
      case "<:"  => Token.SUBTYPE
      case "=>"  => Token.RARROW
      case name  => Token.Ident(name)

  def stringLit(): Token =
    var isLastEscape = false
    def isValidChar(c: Char) =
      if isLastEscape then
        isLastEscape = false
        true
      else
        isLastEscape = c == '\\'
        c != '"'

    stream.eatWhile(c => c != '\n' && isValidChar(c))
    if stream.curChar() == '\n' then
      error("Missing closing double quote: string cannot span multiple lines", stream.tokenSpan().toPos)
    else
      eat('\"')
    val rawString = stream.tokenEnd()
    val content = if rawString.size <= 1 then "" else rawString.substring(1, rawString.size - 1)
    new Token.StringLit(StringUtil.unescape(content))

  def charLit(): Token =
    if stream.curChar() == '\\' then
      // only support one char: \b \f \n \r \t \' \\
      stream.eat()
    stream.eat()
    eat('\'')
    val rawString = stream.tokenEnd()
    val content = rawString.substring(1, rawString.size - 1)
    new Token.CharLit(StringUtil.unescapeChar(content))

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
  def isNameStart(c: Char): Boolean =
    isLetter(c) || c == '_'

  def isNameRest(c: Char): Boolean =
    isNameStart(c) || isDigit(c)

  val OP_CHAR = Array('+', '-', '*', '/', '%', '|', '&', '^', '>', '<', '=', ':', '?', '!')
  def isOperator(c: Char): Boolean =
    OP_CHAR.indexOf(c) >= 0

  def isSpace(c: Char): Boolean =
    c == ' ' || c == '\n' || c == '\t'

  def isDigit(c: Char): Boolean =
    c >= '0' && c <= '9'

  def isLetter(c: Char): Boolean =
    c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'

  class CharStream(code: String)(using source: Source):
    private val LEN = code.length
    private var index: Int = 0

    /** Current line number, starting from 0 */
    private var lineNum: Int = 0

    /** Indentation of the current line */
    private var lineIndentation: Int = countStartingSpace()

    /** The offset of the current line */
    private var curLineOffset: Int = index

    /** Starting offset for the current token */
    private var curTokenStart: Int = -1

    /** Used to create token content */
    private val sb = new StringBuilder

    // add line offset for the starting line
    source.addLineOffset(index)

    def curChar() = code(index)

    def eat(): Char =
      val c = curChar()
      index += 1
      if c == '\n' then
        curLineOffset = index
        lineNum += 1
        source.addLineOffset(index)
        lineIndentation = countStartingSpace()
      else if !hasMore() then
        source.addLineOffset(index)
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

    def eatWhile(pred: Char => Boolean): Unit =
      while hasMore() && pred(curChar()) do
        eat()

    def eatLine(): Unit =
      eatWhile(c => c != '\n')
      eat()

    def curChar(pred: Char => Boolean): Boolean =
      hasMore() && pred(curChar())

    def isComment(): Boolean =
      index < LEN - 1 && curChar() == '/' && code(index + 1) == '/'

    def hasMore(): Boolean = index < LEN

    def tokenStart(): Unit =
      curTokenStart = index

    def tokenEnd(): String =
      if curTokenStart == -1 then
        abortInternal("Token is not marked by calling tokenStart()")

      sb.clear()
      var i = curTokenStart
      while i < index do
        sb += code(i)
        i += 1

      sb.toString()

    def tokenSpan(): Span = Span(curTokenStart, index - curTokenStart)

    def lastCharSpan(): Span = Span(index - 1, 1)

    def lineIndent(): Indent =
      val tokenIndent = curTokenStart - curLineOffset
      Indent(lineNum, lineIndentation, tokenIndent)
