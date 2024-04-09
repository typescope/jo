/************************************************************************
 *                                                                      *
 * The parser for the stack-oriented language.                          *
 *                                                                      *
 ************************************************************************/

import scala.collection.mutable

import Ast.*
import Reporter.*

/***********************************************************************
 *
 * Parsing
 *
 ***********************************************************************/

object Parsing:

  enum Token:
    case LPAREN, RPAREN, IF, THEN, ELSE, END, SEMICOL, VAL, FUN, EQL, EOF, COMMA
    case IntLit(value: Int)
    case BoolLit(value: Boolean)
    case Ident(name: String)

    def withSpan(span: Span): (Token, Span) = (this, span)

  def parse(code: String)(using Reporter): Prog =
    new StackLangParser(code).parse()

  trait Scanner:
    def next(): (Token, Span)

  trait Parser:
    def parse(): Prog

  object Scanner:
    def isNameStart(c: Char): Boolean =
      isLetter(c) || c == '_'

    def isNameRest(c: Char): Boolean =
      isNameStart(c) || isDigit(c)

    val OP_CHAR = Array('+', '-', '*', '/', '%', '|', '&', '^', '>', '<', '=', '!')
    def isOperator(c: Char): Boolean =
      OP_CHAR.indexOf(c) >= 0

    def isSpace(c: Char): Boolean =
      c == ' ' || c == '\n' || c == '\t'

    def isDigit(c: Char): Boolean =
      c >= '0' && c <= '9'

    def isLetter(c: Char): Boolean =
      c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'

  class CharStream(code: String)(using reporter: Reporter):
    private val LEN = code.length
    private var index: Int = 0

    private var curTokenStart: Int = -1
    private val sb = new StringBuilder

    def curChar() = code(index)

    def eat(): Char =
      val c = curChar()
      index += 1
      if c == '\n' then reporter.addLineOffset(index)
      else if !hasMore() then reporter.addLineOffset(index)
      c

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


  class StackLangScanner(stream: CharStream)(using Reporter) extends Scanner:
    import Scanner.{ isDigit, isLetter, isNameStart, isNameRest, isSpace, isOperator }

    def this(code: String)(using Reporter) = this(new CharStream(code))

    def next(): (Token, Span) = nextToken().withSpan(stream.tokenSpan())

    def nextToken(): Token =
      if !stream.hasMore() then return Token.EOF

      // mark the start of a new token
      stream.tokenStart()

      stream.eat() match
        case '('    => Token.LPAREN
        case ')'    => Token.RPAREN
        case ';'    => Token.SEMICOL
        case ','    => Token.COMMA

        case '-'    =>
          if stream.curChar(isDigit) then intLit()
          else operator()

        case '/'    =>
          if stream.curChar() == '/' then
            stream.eatLine()
            nextToken()
          else
            operator()

        case c      =>
          if      isDigit(c)      then intLit()
          else if isNameStart(c)  then name()
          else if isOperator(c)   then operator()
          else if isSpace(c)      then nextToken()
          else
            abort("Unexpected character: " + c, stream.tokenSpan())

    def name(): Token =
      stream.eatWhile(isNameRest)

      stream.tokenEnd() match
        case "if"      => Token.IF
        case "then"    => Token.THEN
        case "else"    => Token.ELSE
        case "end"     => Token.END
        case "val"     => Token.VAL
        case "fun"     => Token.FUN
        case "true"    => Token.BoolLit(true)
        case "false"   => Token.BoolLit(false)
        case name      => Token.Ident(name)

    def operator(): Token =
      stream.eatWhile(c => isOperator(c) && !stream.isComment())

      stream.tokenEnd() match
        case "="   => Token.EQL
        case name  => Token.Ident(name)

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
        abort("Integer literal overflow: " + str, stream.tokenSpan())

      sum
    end str2Int

   /**
     * A scanner that supports peeking one token ahead.
     */
  class LookAheadScanner(scanner: Scanner) extends Scanner:
    var peekedToken: Option[(Token, Span)] = None
    def next() =
      peekedToken match
        case None =>
          scanner.next()
        case Some(token) =>
          peekedToken = None
          token

    def peek() =
      peekedToken match
        case None =>
          val token = scanner.next()
          peekedToken = Some(token)
          token
        case Some(token) =>
          token

  class StackLangParser(code: String)(using Reporter) extends Parser:
    val scanner = new LookAheadScanner(new StackLangScanner(code))

    def next() = scanner.next()
    def peek() = scanner.peek()
    def eat(expect: Token): Span =
      val (actual, span) = next()
      if actual != expect then
        abort("Unexpected token, found = " + actual + ", expect = " + expect, span)
      span

    def parse(): Prog = prog()

    def prog(): Prog =
      val defs = definitions(new mutable.ArrayBuffer)
      val words = phrase()
      eat(Token.EOF)
      Prog(defs, words)

    def definitions(acc: mutable.ArrayBuffer[Def]): List[Def] =
      val (token, span) = peek()
      token match
        case Token.VAL => definitions(acc += valDef())
        case Token.FUN => definitions(acc += funDef())
        case _         => acc.toList

    def valDef(): Def =
      val span1 = eat(Token.VAL)
      val id = ident()
      eat(Token.EQL)
      val words = phrase()
      val span2 = eat(Token.SEMICOL)
      Def.ValDef(id, words).withPos(span1 | span2)

    def funDef(): Def =
      val span1 = eat(Token.FUN)
      val id = ident()
      eat(Token.LPAREN)
      val paramList = params()
      eat(Token.RPAREN)
      eat(Token.EQL)
      val words = phrase()
      val span2 = eat(Token.SEMICOL)
      Def.FunDef(id, paramList, words).withPos(span1 | span2)

    def params(): List[Word.Ident] =
      val (token, _) = peek()
      if token == Token.RPAREN then Nil
      else paramsRest(mutable.ArrayBuffer(ident()))

    def paramsRest(acc: mutable.ArrayBuffer[Word.Ident]): List[Word.Ident] =
      val (token, _) = peek()
      if token == Token.RPAREN then acc.toList
      else
        eat(Token.COMMA)
        paramsRest(acc += ident())

    def phrase(): List[Word] =
      word() match
        case Some(w) => phraseRest(mutable.ArrayBuffer(w))
        case None    =>
          val (token, span) = peek()
          abort("Expect a word, found token " + token, span)

    def phraseRest(words: mutable.ArrayBuffer[Word]): List[Word] =
      word() match
        case Some(w) => phraseRest(words += w)
        case None    => words.toList

    def word(): Option[Word] =
      val (token, span) = peek()
      token match
        case _: Token.Ident  => Some(ident())
        case Token.LPAREN    => Some(fence())
        case Token.IF        => Some(ifword())

        case litToken: Token.IntLit  =>
          next()
          Some(Word.IntLit(litToken.value).withPos(span))

        case litToken: Token.BoolLit =>
          next()
          Some(Word.BoolLit(litToken.value).withPos(span))

        case token =>
          None

    def ident(): Word.Ident =
      val (token, span) = next()
      token match
        case id: Token.Ident => new Word.Ident(id.name).withPos(span)
        case token => abort("Expect identifier, found token " + token, span)

    def fence(): Word =
      val span1 = eat(Token.LPAREN)
      val words = phrase()
      val span2 = eat(Token.RPAREN)
      Word.Fence(words).withPos(span1 | span2)

    def ifword(): Word =
      val span1 = eat(Token.IF)
      val cond = phrase()
      eat(Token.THEN)
      val thenp = phrase()
      // else is optional
      val (token, _) = peek()
      val elsep =
        if token == Token.ELSE then
          eat(Token.ELSE)
          phrase()
        else
          Nil
      val span2 = eat(Token.END)
      Word.If(cond, thenp, elsep).withPos(span1 | span2)
