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
      error(s"Expect character $c, found : " + Character.toString(res), stream.lastCharSpan().toPos)

  extension (token: Token) def withPos(indent: Indent): TokenInfo =
    val span =
      if token == Token.EOF then
        val rawSpan = stream.tokenSpan()
        Span(rawSpan.start - 1, length = 0)
      else
        stream.tokenSpan()

    token.withInfo(span, indent)

  /** Return the token, its span and the line indentation where the token ends */
  def next(): TokenInfo =
    // mark the start of a new token
    val indent = stream.tokenStart()

    if !stream.hasMore() then return Token.EOF.withPos(indent)

    stream.eat() match
      case '('    => Token.LPAREN.withPos(indent)
      case ')'    => Token.RPAREN.withPos(indent)
      case '['    => Token.LBRACKET.withPos(indent)
      case ']'    => Token.RBRACKET.withPos(indent)
      case '{'    => Token.LBRACE.withPos(indent)
      case '}'    => Token.RBRACE.withPos(indent)
      case '.'    => dots().withPos(indent)
      case ','    => Token.COMMA.withPos(indent)

      case '-'    =>
        if stream.curCodePoint(isDigit) then
          val firstDigit = stream.eat()
          number(firstDigit).withPos(indent)
        else
          operator().withPos(indent)

      case '/'    =>
        if stream.curCodePoint() == '/' then
          // Check if this is a multiline comment (//[, ///[, etc.)
          val slashCount = eatSlashes()
          if stream.curCodePoint() == '[' then
            // This is a multiline comment opening
            stream.eat() // consume '['
            val openingSpan = stream.tokenSpan()
            eatMultilineComment(slashCount, openingSpan)
            next()
          else
            // Single-line comment
            stream.eatLine()
            next()
        else
          operator().withPos(indent)

      case '"'    =>
        stringLit().withPos(indent)

      case '\''    =>
        charLit().withPos(indent)

      case c      =>
        if      isDigit(c)         then number(c).withPos(indent)
        else if isNameStart(c)     then name().withPos(indent)
        else if isOperatorChar(c)  then operator().withPos(indent)
        else if isSpace(c)         then next()
        else
          error("Unexpected character: " + Character.toString(c), stream.tokenSpan().toPos)
          next()

  def name(): Token =
    stream.eatWhile(isNameRest)

    stream.tokenEnd() match
      case "as"        => Token.AS
      case "if"        => Token.IF
      case "is"        => Token.IS
      case "then"      => Token.THEN
      case "else"      => Token.ELSE
      case "match"     => Token.MATCH
      case "case"      => Token.CASE
      case "while"     => Token.WHILE
      case "for"       => Token.FOR
      case "in"        => Token.IN
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
      case "union"     => Token.UNION
      case "alias"     => Token.ALIAS
      case "begin"     => Token.BEGIN
      case "auto"      => Token.AUTO
      case "defer"     => Token.DEFER
      case "class"     => Token.CLASS
      case "object"    => Token.OBJECT
      case "private"   => Token.PRIVATE
      case "new"       => Token.NEW
      case "interface" => Token.INTERFACE
      case "view"      => Token.VIEW
      case "like"      => Token.LIKE
      case name        => Token.Name(name)

  def operator(): Token =
    stream.eatWhile(c => isOperatorChar(c) && !stream.isComment())

    stream.tokenEnd() match
      case "="   => Token.EQL
      case ":"   => Token.COLON
      case "<:"  => Token.SUBTYPE
      case "=>"  => Token.RARROW
      case name  => Token.Operator(name)

  def dots(): Token =
    stream.eatWhile(_ == '.')

    stream.tokenEnd() match
      case "."    => Token.DOT
      case name   => Token.Operator(name)

  def stringLit(): Token =
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

        // Consume the newline after opening quotes
        if stream.hasMore() && stream.curCodePoint() == '\n' then
          stream.eat()

        // Emit opening marker for multi-line string
        return Token.StringStart(quoteCount)
      else
        // Just 2 quotes: empty string "" - don't consume the second quote
        return Token.StringStart(1)

    // Single-line string - emit StringStart(1)
    Token.StringStart(1)

  /** Read next string token for string parsing
    *
    * @returns StringEnd or StringLine
    *
    * For single-line (quoteCount == 1): reads until closing quote or newline/EOF
    * For multi-line (quoteCount >= 3): reads raw line content, parser handles indentation/continuation
    */
  def nextString(quoteCount: Int): TokenInfo =
    val indent = stream.tokenStart()

    if quoteCount == 1 then
      // Single-line string - read until closing quote or return StringEnd if already at end
      // First check if we're at a position right after string content (empty token means we should return StringEnd)
      if !stream.hasMore() then
        // EOF reached - don't report error here, let parser handle it
        return Token.EOF.withPos(indent)

      // Check if content is empty (immediate closing quote or we're being called after StringLine was returned)
      val firstChar = stream.curCodePoint()
      if firstChar == '"' then
        // Empty string or being called after returning StringLine
        stream.eat() // consume the quote
        return Token.StringEnd.withPos(indent)

      // Check if we're at the start of an interpolation \{
      if firstChar == '\\' && stream.hasNextCodePoint() && stream.nextCodePoint() == '{' then
        stream.eat() // consume \
        stream.eat() // consume {
        return Token.InterpolationStart.withPos(indent)

      // Read content until closing quote
      while stream.hasMore() do
        val c = stream.curCodePoint()

        if c == '\\' then
          // Check if this is an interpolation \{
          if stream.hasNextCodePoint() && stream.nextCodePoint() == '{' then
            // Found interpolation - return content before it, don't consume \{
            val str = stream.tokenEnd()
            return Token.StringLine(str).withPos(indent)
          else
            // Regular escape sequence - consume backslash and next character
            stream.eat()
            if stream.hasMore() then stream.eat()

        else if c == '"' then
          val str = stream.tokenEnd()
          val contentToken = Token.StringLine(str).withPos(indent)
          // DON'T consume the closing quote yet - next call will handle it
          return contentToken

        else if c == '\n' then
          val str = stream.tokenEnd()
          // Don't consume \n - let parser detect error and stop parsing the string
          // Don't report error here - let parser handle it
          return Token.StringLine(str).withPos(indent)

        else
          stream.eat()
      end while

      // Reached EOF without closing quote
      val str = stream.tokenEnd()
      // Don't report error here - let parser handle it
      return (if str.isEmpty then Token.EOF else Token.StringLine(str)).withPos(indent)

    else
      // Multi-line string

      // Check if we're at the start of an interpolation \{
      if stream.hasMore() && stream.curCodePoint() == '\\' &&
         stream.hasNextCodePoint() && stream.nextCodePoint() == '{' then
        stream.eat() // consume \
        stream.eat() // consume {
        return Token.InterpolationStart.withPos(indent)

      var consecutiveQuotes = 0

      while stream.hasMore() do
        val c = stream.curCodePoint()

        // Check for interpolation \{
        if c == '\\' && stream.hasNextCodePoint() && stream.nextCodePoint() == '{' then
          // Found interpolation - return content before it, don't consume \{
          val str = stream.tokenEnd()
          return Token.StringLine(str).withPos(indent)

        // Check for newline
        if c == '\n' then
          val str = stream.tokenEnd()
          val item = Token.StringLine(str).withPos(indent)
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
      (if str.isEmpty then Token.EOF else Token.StringLine(str)).withPos(indent)
    end if

  def charLit(): Token =
    if stream.curCodePoint() == '\\' then
      stream.eat()
      if stream.hasMore() then
        stream.eat()
      else
        error("Unexpected end of file", stream.tokenSpan().toPos)
        return Token.CharLit(0)
    else
      stream.eat()

    if stream.hasMore() then
      eat('\'')
    else
      error("Unexpected end of file", stream.tokenSpan().toPos)
      return Token.CharLit(0)

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

  /** Validate underscore placement in number literals
    *
    * Returns true if the number has valid underscore placement
    * Reports error and returns false otherwise
    */
  def validateNumberUnderscores(numStr: String, isHex: Boolean): Boolean =
    if !numStr.contains('_') then
      return true

    // Find the position after the sign and hex prefix (if any)
    var start = 0
    if numStr.length > 0 && numStr(0) == '-' then
      start = 1

    if isHex then
      // Skip "0x" or "0X" prefix
      if numStr.length > start + 2 && numStr(start) == '0' && (numStr(start + 1) == 'x' || numStr(start + 1) == 'X') then
        start += 2

    // Check for leading underscore after prefix
    if numStr.length > start && numStr(start) == '_' then
      error("Number literal cannot start with underscore", stream.tokenSpan().toPos)
      return false

    // Check for trailing underscore
    if numStr.last == '_' then
      error("Number literal cannot end with underscore", stream.tokenSpan().toPos)
      return false

    // Check for consecutive underscores and underscore around decimal point or exponent
    var i = start
    while i < numStr.length do
      val c = numStr(i)
      if c == '_' then
        // Check for consecutive underscores
        if i + 1 < numStr.length && numStr(i + 1) == '_' then
          error("Number literal cannot have consecutive underscores", stream.tokenSpan().toPos)
          return false
        // For non-hex literals, check for underscore around decimal point or exponent
        if !isHex then
          // Check for underscore immediately before decimal point or exponent
          if i + 1 < numStr.length then
            val next = numStr(i + 1)
            if next == '.' || next == 'e' || next == 'E' then
              error("Underscore cannot appear immediately before decimal point or exponent", stream.tokenSpan().toPos)
              return false
          // Check for underscore immediately after decimal point, exponent, or sign in exponent
          if i > 0 then
            val prev = numStr(i - 1)
            if prev == '.' || prev == 'e' || prev == 'E' || prev == '+' || prev == '-' then
              error("Underscore cannot appear immediately after decimal point, exponent, or sign", stream.tokenSpan().toPos)
              return false
      i += 1

    true
  end validateNumberUnderscores

  def number(firstDigit: Int): Token.IntLit | Token.FloatLit =
    /** Check if current position indicates a float literal (has . or e/E) */
    def isFloatLiteral(): Boolean =
      val c = stream.curCodePoint()
      if c == '.' then
        // Decimal point must be followed by a digit
        // Valid: "3.14"
        // Invalid: "3." (no digit), "3.toString" (method call), "3.e5" (no fractional digits)
        if stream.hasNextCodePoint() then
          val next = stream.nextCodePoint()
          isDigit(next)  // Must be a digit
        else
          false  // "3." at EOF is not a valid float
      else if c == 'e' || c == 'E' then
        true  // Exponent without decimal point: "3e5" is valid float
      else
        false  // No decimal point or exponent: it's an int

    /** Check if a character is a digit or underscore (for number literals) */
    def isDigitOrUnderscore(c: Int): Boolean = isDigit(c) || c == '_'

    // Check for hexadecimal prefix 0x or 0X
    // firstDigit is the first digit that has already been consumed
    if firstDigit == '0' then
      val c = stream.curCodePoint()
      if c == 'x' || c == 'X' then
        // This is a hex literal: 0x...
        stream.eat() // consume 'x' or 'X'
        stream.eatWhile(c => StringUtil.isHexDigit(c) || c == '_')
        val hexStr = stream.tokenEnd()
        // hexStr could be "-0x..." or "0x..."
        val prefixLen = if hexStr(0) == '-' then 3 else 2
        if hexStr.length <= prefixLen then // Only "-0x" or "0x" with no digits
          error("Hexadecimal literal must have at least one digit", stream.tokenSpan().toPos)
          new Token.IntLit("0")(isHex = false)
        else
          validateNumberUnderscores(hexStr, isHex = true)
          new Token.IntLit(hexStr)(isHex = true)
      else
        // Regular decimal starting with 0
        stream.eatWhile(isDigitOrUnderscore)
        if isFloatLiteral() then floatLit() else intLit()
    else
      // Regular decimal
      stream.eatWhile(isDigitOrUnderscore)
      if isFloatLiteral() then floatLit() else intLit()
  end number

  def intLit(): Token.IntLit =
    val str = stream.tokenEnd()
    validateNumberUnderscores(str, isHex = false)
    new Token.IntLit(str)(isHex = false)

  def floatLit(): Token.FloatLit =
    /** Check if a character is a digit or underscore (for number literals) */
    def isDigitOrUnderscore(c: Int): Boolean = isDigit(c) || c == '_'

    // We're already past the integer part, now parse decimal point and/or exponent
    val c = stream.curCodePoint()

    // Parse decimal point and fractional part
    if c == '.' then
      stream.eat() // consume '.'
      stream.eatWhile(isDigitOrUnderscore)

    // Parse exponent part (e or E followed by optional +/- and digits)
    val c2 = stream.curCodePoint()
    if c2 == 'e' || c2 == 'E' then
      stream.eat() // consume 'e' or 'E'
      val c3 = stream.curCodePoint()
      if c3 == '+' || c3 == '-' then
        stream.eat() // consume '+' or '-'
      stream.eatWhile(isDigitOrUnderscore)

    val floatStr = stream.tokenEnd()
    validateNumberUnderscores(floatStr, isHex = false)
    new Token.FloatLit(floatStr)


  /** Eat consecutive slashes starting from current position
    *
    * Assumes first '/' has already been consumed and we're at the second '/'
    *
    * Returns the total count of slashes
    */
  def eatSlashes(): Int =
    var count = 1 // Already consumed first '/'
    while stream.hasMore() && stream.curCodePoint() == '/' do
      count += 1
      stream.eat()
    count

  /** Consume a multiline comment with exact slash count matching
    *
    * Looks for closing delimiter //], ///], etc. matching the opening //[, ///[, etc.
    *
    * Assumes opening slashes and '[' have already been consumed
    */
  def eatMultilineComment(slashCount: Int, openingSpan: Span): Unit =
    while stream.hasMore() do
      val c = stream.curCodePoint()

      if c == '/' then
        // Eat consecutive slashes starting from this one
        stream.eat() // consume the first slash
        val closingCount = eatSlashes()
        if closingCount == slashCount && stream.curCodePoint() == ']' then
          // Found exact matching closing delimiter
          stream.eat() // consume ']'
          return
        // Otherwise, continue (the slashes have been consumed by eatSlashes)
      else
        stream.eat()
    end while

    // Reached EOF without finding closing delimiter
    error(s"Unclosed multiline comment (expected ${slashCount} slashes followed by ] to close)", openingSpan.toPos)
  end eatMultilineComment

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

    def tokenStart(): Indent =
      curTokenIndex = index
      curTokenOffset = offset

      val tokenIndent = curTokenOffset - curLineOffset
      assert(tokenIndent >= 0, "tokenIndent = " + tokenIndent + ", line = " + lineNum)

      Indent(lineNum, lineIndentation, tokenIndent)

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

    def lastCharSpan(): Span = Span(offset - 1, 1)

    /** Some tokens will customize start point, e.g. StringEnd */
    def lineIndent(tokenStartOffset: Int): Indent =
      val tokenIndent = tokenStartOffset - curLineOffset

      assert(tokenIndent >= 0, "tokenIndent = " + tokenIndent + ", line = " + lineNum + ", tokenStartOffset = " + tokenStartOffset)

      Indent(lineNum, lineIndentation, tokenIndent)
