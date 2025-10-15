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

  extension (token: Token) def withPos: TokenInfo =
    val span =
      if token == Token.EOF then
        val rawSpan = stream.tokenSpan()
        Span(rawSpan.start - 1, length = 0)
      else
        stream.tokenSpan()

    token.withInfo(span, stream.lineIndent())

  /** Return the token, its span and the line indentation where the token ends */
  def next(): TokenInfo =
    // mark the start of a new token
    stream.tokenStart()

    if !stream.hasMore() then return Token.EOF.withPos

    stream.eat() match
      case '('    => Token.LPAREN.withPos
      case ')'    => Token.RPAREN.withPos
      case '['    => Token.LBRACKET.withPos
      case ']'    => Token.RBRACKET.withPos
      case '{'    => Token.LBRACE.withPos
      case '}'    => Token.RBRACE.withPos
      case '#'    => Token.TAG.withPos
      case '.'    => dots().withPos
      case ','    => Token.COMMA.withPos

      case '-'    =>
        if stream.curCodePoint(isDigit) then
          val firstDigit = stream.eat()
          intLit(firstDigit).withPos
        else
          operator().withPos

      case '/'    =>
        if stream.curCodePoint() == '/' then
          stream.eatLine()
          stream.tokenStart()
          next()
        else
          operator().withPos

      case '"'    =>
        stringLit()

      case '\''    =>
        charLit().withPos

      case c      =>
        if      isDigit(c)         then intLit(c).withPos
        else if isNameStart(c)     then name().withPos
        else if isOperatorChar(c)  then operator().withPos
        else if isSpace(c)         then next()
        else
          error("Unexpected character: " + Character.toString(c), stream.tokenSpan().toPos)
          next()

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

  def stringLit(): TokenInfo =
    // First quote already consumed, check if this is multi-line (""" or more)
    if stream.hasMore() && stream.curCodePoint() == '"' then
      // Peek ahead to see if it's 2 quotes (empty string) or 3+ quotes (multiline)
      // Need to check if there's a third character before peeking
      val thirdChar = if stream.hasNextCodePoint() then stream.nextCodePoint() else -1

      if thirdChar == '"' then
        // At least 3 quotes - this is a multi-line string
        stream.eat() // consume second "
        stream.eat() // consume third "
        var quoteCount = 3

        // Count additional quotes
        while stream.curCodePoint() == '"' do
          quoteCount += 1
          stream.eat()

        // Multi-line string must start on a new line
        // Check if there are any characters before newline and report error
        if stream.hasMore() && stream.curCodePoint() != '\n' then
          val errorStart = stream.tokenSpan().endOffset
          var length = 0
          while stream.hasMore() && stream.curCodePoint() != '\n' do
            length += StringUtil.utf8CodePointLength(stream.curCodePoint())
            stream.eat()
          error("Multi-line string must start on a new line after opening quotes", Span(errorStart, length).toPos)

        // Take span and indent before consuming \n
        val span = stream.tokenSpan()
        val indent = stream.lineIndent()

        // Consume the newline after opening quotes
        if stream.hasMore() && stream.curCodePoint() == '\n' then
          stream.eat()

        // Emit opening marker for multi-line string
        return Token.StringStart(quoteCount).withInfo(span, indent)
      else
        // Just 2 quotes: empty string "" - don't consume the second quote
        return Token.StringStart(1).withPos

    // Single-line string - emit StringStart(1)
    Token.StringStart(1).withPos

  /** Read next string token for string parsing
    *
    * @returns StringEnd or StringLine
    *
    * For single-line (quoteCount == 1): reads until closing quote or newline/EOF
    * For multi-line (quoteCount >= 3): reads raw line content, parser handles indentation/continuation
    */
  def nextString(quoteCount: Int): TokenInfo =
    stream.tokenStart()

    if quoteCount == 1 then
      // Single-line string - read until closing quote or return StringEnd if already at end
      // First check if we're at a position right after string content (empty token means we should return StringEnd)
      if !stream.hasMore() then
        // EOF reached - don't report error here, let parser handle it
        return Token.EOF.withPos

      // Check if content is empty (immediate closing quote or we're being called after StringLine was returned)
      val firstChar = stream.curCodePoint()
      if firstChar == '"' then
        // Empty string or being called after returning StringLine
        stream.eat() // consume the quote
        return Token.StringEnd.withInfo(stream.tokenSpan(), stream.lineIndent())

      // Read content until closing quote
      while stream.hasMore() do
        val c = stream.curCodePoint()

        if c == '\\' then
          // Escape sequence - consume backslash and next character
          stream.eat()
          if stream.hasMore() then stream.eat()

        else if c == '"' then
          val str = stream.tokenEnd()
          val contentToken = Token.StringLine(str).withPos
          // DON'T consume the closing quote yet - next call will handle it
          return contentToken

        else if c == '\n' then
          val str = stream.tokenEnd()
          // Don't report error here - let parser handle it
          return Token.StringLine(str).withPos

        else
          stream.eat()
      end while

      // Reached EOF without closing quote
      val str = stream.tokenEnd()
      // Don't report error here - let parser handle it
      return (if str.isEmpty then Token.EOF else Token.StringLine(str)).withPos

    else
      // Multi-line string
      var consecutiveQuotes = 0

      while stream.hasMore() do
        val c = stream.curCodePoint()

        // Check for newline
        if c == '\n' then
          val str = stream.tokenEnd()
          val item = Token.StringLine(str).withPos
          // consume \n after taking position
          stream.eat()
          return item

        // Check for closing quotes
        if c == '"' then
          consecutiveQuotes += 1
          stream.eat()

          if consecutiveQuotes == quoteCount then
            // Found closing delimiter - remove quotes we collected
            val str = stream.tokenEnd()
            val prefix = str.substring(0, str.size - quoteCount)
            if !prefix.forall(c => c == ' ' || c == '\t') then
              error("Closing delimiter line must contain only whitespace", stream.tokenSpan().toPos)

            val rawSpan = stream.tokenSpan()
            val start = rawSpan.endOffset - quoteCount
            val span = Span(start, quoteCount)

            return Token.StringEnd.withInfo(span, stream.lineIndent(start))

        else
          consecutiveQuotes = 0
          stream.eat()
      end while

      val str = stream.tokenEnd()

      // Reached EOF - return what we have as a line
      (if str.isEmpty then Token.EOF else Token.StringLine(str)).withPos
    end if

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
      else
        // Simple escape like \b \f \n \r \t \' \\ or unknown escape
        stream.eat()
    else
      stream.eat()
    eat('\'')
    val rawString = stream.tokenEnd()
    val content = rawString.substring(1, rawString.size - 1)

    try
      new Token.CharLit(StringUtil.unescapeChar(content))
    catch
      case e: StringUtil.EscapeError =>
        // Map the offset in the content to the source position
        // +1 for the opening quote, +e.offset for position in content
        val errorStart = stream.tokenSpan().start + 1 + e.offset
        val errorSpan = Span(errorStart, e.length)
        error(e.message, errorSpan.toPos)
        new Token.CharLit(0)  // Return a dummy value

  def intLit(firstDigit: Int): Token.IntLit =
    // Check for hexadecimal prefix 0x or 0X
    // firstDigit is the first digit that has already been consumed
    if firstDigit == '0' then
      val c = stream.curCodePoint()
      if c == 'x' || c == 'X' then
        // This is a hex literal: 0x...
        stream.eat() // consume 'x' or 'X'
        stream.eatWhile(c => StringUtil.isHexDigit(c))
        val hexStr = stream.tokenEnd()
        // hexStr could be "-0x..." or "0x..."
        val prefixLen = if hexStr(0) == '-' then 3 else 2
        if hexStr.length <= prefixLen then // Only "-0x" or "0x" with no digits
          error("Hexadecimal literal must have at least one digit", stream.tokenSpan().toPos)
          new Token.IntLit(0)
        else
          val value = hexStr2Int(hexStr)
          new Token.IntLit(value)
      else
        // Regular decimal starting with 0
        stream.eatWhile(isDigit)
        val intStr = stream.tokenEnd()
        val value = str2Int(intStr)
        new Token.IntLit(value)
    else
      // Regular decimal
      stream.eatWhile(isDigit)
      val intStr = stream.tokenEnd()
      val value = str2Int(intStr)
      new Token.IntLit(value)

  def hexStr2Int(str: String): Int =
    // str is like "0x1F" or "-0xFF"
    val isNegative = str(0) == '-'
    val prefixLen = if isNegative then 3 else 2 // Skip "-0x" or "0x"
    val hexDigits = str.substring(prefixLen)
    val length = hexDigits.size

    if length > 8 then
      error("Hexadecimal literal too long (max 8 hex digits): " + hexDigits, stream.tokenSpan().toPos)
      return 0

    var sum: Int = 0
    var i = 0
    while i < length do
      val c = hexDigits(i)
      val v = if c >= '0' && c <= '9' then c - '0'
              else if c >= 'a' && c <= 'f' then c - 'a' + 10
              else if c >= 'A' && c <= 'F' then c - 'A' + 10
              else 0 // Should not happen due to eatWhile check
      sum = (sum << 4) | v
      i += 1
    end while

    if isNegative then -sum else sum
  end hexStr2Int

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

    def hasNextCodePoint(): Boolean =
      val nextIndex = index + Character.charCount(curCodePoint())
      nextIndex < LEN

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

      assert(tokenIndent >= 0, "tokenIndent = " + tokenIndent + ", line = " + lineNum + ", did you forget to call .tokenStart or call .lineIndent just after consuming newline")

      Indent(lineNum, lineIndentation, tokenIndent)

    /** Some tokens will customize start point, e.g. StringEnd */
    def lineIndent(tokenStartOffset: Int): Indent =
      val tokenIndent = tokenStartOffset - curLineOffset

      assert(tokenIndent >= 0, "tokenIndent = " + tokenIndent + ", line = " + lineNum + ", tokenStartOffset = " + tokenStartOffset)

      Indent(lineNum, lineIndentation, tokenIndent)
