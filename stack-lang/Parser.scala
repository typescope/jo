/************************************************************************
 *                                                                      *
 * The parser for the stack-oriented language.                          *
 *                                                                      *
 ************************************************************************/

import scala.collection.mutable


/***********************************************************************
 *
 * Parsing
 *
 ***********************************************************************/

object Parsing:

  enum Token:
    case LPAREN, RPAREN, IF, THEN, ELSE, END, SEMICOL, VAL, FUN, EQL, EOF
    case IntLit(value: Int)
    case BoolLit(value: Boolean)
    case Ident(name: String)

  def parse(code: String): Ast.Prog = new StackLangParser(code).parse()

  trait Scanner { def next(): Token     }
  trait Parser  { def parse(): Ast.Prog }

  private def err(msg: String) = throw new Exception(msg)

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

  class CharStream(code: String):
    private val LEN = code.length
    private var index: Int = 0

    private var curTokenStart: Int = -1
    private val sb = new StringBuilder

    def curChar() = code(index)

    def eat(): Char =
      val c = curChar()
      index += 1
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
      if curTokenStart == -1 then err("Token is not marked by calling tokenStart()")

      sb.clear()
      var i = curTokenStart
      while i < index do
        sb += code(i)
        i += 1

      curTokenStart = index
      sb.toString()

  class StackLangScanner(chars: CharStream) extends Scanner:
    import Scanner.{ isDigit, isLetter, isNameStart, isNameRest, isSpace, isOperator }

    def this(code: String) = this(new CharStream(code))

    def next(): Token =
      if !chars.hasMore() then return Token.EOF

      // mark the start of a new token
      chars.tokenStart()

      chars.eat() match
        case '('    => Token.LPAREN
        case ')'    => Token.RPAREN
        case ';'    => Token.SEMICOL

        case '-'    =>
          if chars.curChar(isDigit) then intLit()
          else operator()

        case '/'    =>
          if chars.curChar() == '/' then
            chars.eatLine()
            next()
          else
            operator()

        case c      =>
          if      isDigit(c)      then intLit()
          else if isNameStart(c)  then name()
          else if isOperator(c)   then operator()
          else if isSpace(c)      then next()
          else
            err("Unexpected character: " + c)

    def name(): Token =
      chars.eatWhile(isNameRest)

      chars.tokenEnd() match
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
      chars.eatWhile(c => isOperator(c) && !chars.isComment())

      chars.tokenEnd() match
        case "="   => Token.EQL
        case name  => Token.Ident(name)

    def intLit(): Token.IntLit =
      chars.eatWhile(isDigit)
      val intStr = chars.tokenEnd()
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
        err("Integer literal overflow: " + str)

      sum
    end str2Int

   /**
     * A scanner that supports peeking one token ahead.
     */
  class LookAheadScanner(scanner: Scanner) extends Scanner:
    var peekedToken: Option[Token] = None
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

  class StackLangParser(code: String) extends Parser:
    val scanner = new LookAheadScanner(new StackLangScanner(code))

    def next() = scanner.next()
    def peek() = scanner.peek()
    def eat(expect: Token): Unit =
      val actual = next()
      if actual != expect then
        err("Unexpected token, found = " + actual + ", expect = " + expect)

    def parse(): Ast.Prog = prog()

    def prog(): Ast.Prog =
      val defs = definitions(new mutable.ArrayBuffer)
      val words = phrase()
      eat(Token.EOF)
      Ast.Prog(defs, words)

    def definitions(acc: mutable.ArrayBuffer[Ast.Def]): List[Ast.Def] =
      peek() match
      case Token.VAL => definitions(acc += valDef())
      case Token.FUN => definitions(acc += funDef())
      case _         => acc.toList

    def valDef(): Ast.Def =
      eat(Token.VAL)
      val id = ident()
      eat(Token.EQL)
      val words = phrase()
      eat(Token.SEMICOL)
      Ast.Def.ValDef(id.name, words)

    def funDef(): Ast.Def =
      eat(Token.FUN)
      val id = ident()
      eat(Token.LPAREN)
      val paramList = params()
      eat(Token.RPAREN)
      eat(Token.EQL)
      val words = phrase(Nil)
      eat(Token.SEMICOL)
      Ast.Def.FunDef(id, paramList, words)

    def params(): List[Ident] =
      if peek() == Token.RPAREN then acc.toList
      else paramsRest(mutable.ArrayBuffer(ident()))

    def paramsRest(acc: mutable.ArrayBuffer[Ident]): List[Ident] =
      if peek() == Token.RPAREN then acc.toList
      else
        eat(Token.COMMA)
        paramsRest(acc += ident())

    def phrase(): List[Ast.Word] =
      word() match
        case Some(w) => phraseRest(mutable.ArrayBuffer(w))
        case None    =>
          err("Expect a word, found token " + peek())

    def phraseRest(words: mutable.ArrayBuffer[Ast.Word]): List[Ast.Word] =
      word() match
        case Some(w) => phraseRest(words += w)
        case None    => words.toList

    def word(): Option[Ast.Word] =
      peek() match
        case _: Token.Ident  => Some(ident())
        case Token.LBRACE    => Some(fence())
        case Token.IF        => Some(ifStat())

        case litToken: Token.IntLit  =>
          next()
          Some(Ast.Word.IntLit(litToken.value))

        case litToken: Token.BoolLit =>
          next()
          Some(Ast.Word.BoolLit(litToken.value))

        case token =>
          None

    def ident(): Ast.Word.Ident =
      next() match
        case id: Token.Ident => new Ast.Word.Ident(id.name)
        case token => err("Expect identifier, found token " + token)

    def fence(): Ast.Word =
      eat(Token.LPAREN)
      val words = phrase()
      eat(Token.RPAREN)
      Ast.Word.Fence(words)

    def ifStat(): Ast.Word =
      eat(Token.IF)
      val cond = phrase()
      eat(Token.THEN)
      val thenp = phrase()
      // else is optional
      val elsep =
        if peek() == Token.ELSE then
          eat(Token.ELSE)
          phrase()
        else
          Nil
      eat(Token.END)
      Ast.Word.IfStat(cond, thenp, elsep)
