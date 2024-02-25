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
    case LBRACE, RBRACE, IF, THEN, ELSE, FI, SEMICOL, VAL, FUN, EQL, EOF
    case IntLit(value: Int)
    case BoolLit(value: Boolean)
    case Ident(name: String)

  def parse(code: String): Ast.Prog = new StackLangParser(code).parse()

  trait Scanner { def next(): Token }
  trait Parser { def parse(): Ast.Prog }

  private def err(msg: String) = throw new Exception(msg)

  class StackLangScanner(code: String) extends Scanner:
    private val LEN = code.length
    private var index: Int = 0
    private  val sb = new StringBuilder

    def curChar() = code(index)

    def eat(): Char =
      val c = curChar()
      index += 1
      c

    def eatWhile(pred: Char => Boolean)(action: Char => Unit): Unit =
      while index < LEN && pred(curChar()) do
        action(eat())

    def eatLine(): Unit =
      while index < LEN && curChar() != '\n' do
        eat()

    def peek(pred: Char => Boolean): Boolean =
      index < LEN - 1 && pred(code(index + 1))

    def peekIsNot(c: Char): Boolean =
      index >= LEN - 1 && code(index + 1) != c

    def next(): Token =
      if index >= LEN then return Token.EOF

      val c = eat()

      c match
        case '{'    => Token.LBRACE
        case '}'    => Token.RBRACE
        case ';'    => Token.SEMICOL

        case '-'    =>
          if peek(isDigit) then intLit('-')
          else operatorOrKeyword('-')

        case '/'    =>
          if peekIsNot('/') then
            operatorOrKeyword('/')
          else
            eatLine()
            next()

        case _      =>
          if      isDigit(c)      then intLit(c)
          else if isNameStart(c)  then nameOrKeyword(c)
          else if isOperator(c)   then operatorOrKeyword(c)
          else if isSpace(c)      then next()
          else
            err("Unexpected character: " + c)

    def nameOrKeyword(first: Char): Token =
      sb.clear()
      sb += first
      eatWhile(isNameRest)(c => sb += c)

      sb.toString() match
        case "if"      => Token.IF
        case "then"    => Token.THEN
        case "else"    => Token.ELSE
        case "fi"      => Token.FI
        case "val"     => Token.VAL
        case "fun"     => Token.FUN
        case "true"    => Token.BoolLit(true)
        case "false"   => Token.BoolLit(false)
        case name      => Token.Ident(name)

    def operatorOrKeyword(first: Char): Token =
      sb.clear()
      sb += first

      def isNotComment(c: Char) = c != '/' || peekIsNot('/')

      eatWhile(c => isOperator(c) && isNotComment(c)): c =>
        sb += c

      sb.toString() match
        case "="   => Token.EQL
        case name  => Token.Ident(name)

    def intLit(first: Char): Token.IntLit =
      // better error message
      sb.clear()
      sb += first
      val isNegative = first == '-'

      var sum: Int = 0
      if !isNegative then sum = first - '0'
      var overflow = false
      eatWhile(isDigit): c =>
        sb += c
        val v = c - '0'
        sum = sum * 10 + (if isNegative then -v else v)
        if !isNegative & sum < 0 then overflow = true
        else if isNegative & sum > 0 then overflow = true

      if overflow then
        err("Integer literal overflow: " + sb)

      // While an operator may follow immediately a number, a name may not.
      val c = curChar()
      if isNameStart(c) then
        err("Unexpected char following int literal: " + c)

      new Token.IntLit(sum)

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
      eat(Token.EQL)
      val words = phrase()
      eat(Token.SEMICOL)
      Ast.Def.FunDef(id.name, words)

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
        case Token.LBRACE    => Some(proc())
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

    def proc(): Ast.Word =
      eat(Token.LBRACE)
      val words = phrase()
      eat(Token.RBRACE)
      Ast.Word.Proc(words)

    def ifStat(): Ast.Word =
      eat(Token.IF)
      val cond = phrase()
      eat(Token.THEN)
      val thenp = phrase()
      eat(Token.ELSE)
      val elsep = phrase()
      eat(Token.FI)
      Ast.Word.IfStat(cond, thenp, elsep)
