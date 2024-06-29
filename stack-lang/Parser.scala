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
    case LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE
    case OF, IF, THEN, ELSE, VAL, VAR, FUN,  WHILE, DO, TYPE, MATCH, CASE, END
    case TAG, SEMICOL, COMMA, DOT, EOF
    case COLON, RARROW, EQL, SUBTYPE
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

    val OP_CHAR = Array('+', '-', '*', '/', '%', '|', '&', '^', '>', '<', '=', '!', ':')
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
        case '['    => Token.LBRACKET
        case ']'    => Token.RBRACKET
        case '{'    => Token.LBRACE
        case '}'    => Token.RBRACE
        case '#'    => Token.TAG
        case '.'    => Token.DOT
        case ';'    => Token.SEMICOL
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

        case c      =>
          if      isDigit(c)      then intLit()
          else if isNameStart(c)  then name()
          else if isOperator(c)   then operator()
          else if isSpace(c)      then nextToken()
          else
            error("Unexpected character: " + c, stream.tokenSpan())
            nextToken()

    def name(): Token =
      stream.eatWhile(isNameRest)

      stream.tokenEnd() match
        case "of"      => Token.OF
        case "if"      => Token.IF
        case "then"    => Token.THEN
        case "else"    => Token.ELSE
        case "match"   => Token.MATCH
        case "case"    => Token.CASE
        case "while"   => Token.WHILE
        case "do"      => Token.DO
        case "end"     => Token.END
        case "val"     => Token.VAL
        case "var"     => Token.VAR
        case "fun"     => Token.FUN
        case "type"    => Token.TYPE
        case "true"    => Token.BoolLit(true)
        case "false"   => Token.BoolLit(false)
        case name      => Token.Ident(name)

    def operator(): Token =
      stream.eatWhile(c => isOperator(c) && !stream.isComment())

      stream.tokenEnd() match
        case "="   => Token.EQL
        case ":"   => Token.COLON
        case "<:"  => Token.SUBTYPE
        case "=>"  => Token.RARROW
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
        error("Integer literal overflow: " + str, stream.tokenSpan())

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
        error("Unexpected token, found = " + actual + ", expect = " + expect, span)
      span

    def parse(): Prog =
      val p = prog()
      // With parsing errors, ensure finish scanning
      while peek()._1 != Token.EOF do next()
      p

    def prog(): Prog =
      val defs = definitions(new mutable.ArrayBuffer)
      val words = phrase()
      eat(Token.EOF)
      Prog(defs, words)

    def definitions(acc: mutable.ArrayBuffer[Def]): List[Def] =
      val (token, span) = peek()
      token match
        case Token.VAL | Token.VAR =>
          definitions(acc += valDef(token))

        case Token.FUN =>
           definitions(acc += funDef())

        case Token.TYPE =>
           definitions(acc += typeDef())

        case _ =>
          acc.toList

    def valDef(modifier: Token): ValDef =
      val mutable = modifier == Token.VAR
      val span1 = eat(modifier)
      val id = ident()

      val tpt =
        if peek()._1 == Token.COLON then
          eat(Token.COLON)
          typ()
        else
          EmptyTypeTree()(id.pos)

      eat(Token.EQL)
      val words = phrase()
      val span2 = eat(Token.SEMICOL)
      ValDef(id, tpt, words, mutable)(span1 | span2)

    def funDef(): FunDef =
      val span1 = eat(Token.FUN)
      val id = ident()
      val tparams = typeParams()
      eat(Token.LPAREN)
      val paramList = params()
      eat(Token.RPAREN)
      eat(Token.COLON)
      val resType = typ()
      eat(Token.EQL)
      val words = phrase()
      val span2 = eat(Token.SEMICOL)
      FunDef(id, tparams, paramList, resType, words)(span1 | span2)

    def typeDef(): TypeDef =
      val span1 = eat(Token.TYPE)
      val id = ident()
      val tparams = typeParams()
      eat(Token.EQL)
      val rhs = typ()
      val span2 = eat(Token.SEMICOL)
      TypeDef(id, tparams, rhs)(span1 | span2)

    def typeParams(): List[TypeParam] =
      val (token, _) = peek()
      if token != Token.LBRACKET then Nil
      else
        eat(Token.LBRACKET)
        val items = new mutable.ArrayBuffer[TypeParam]
        items += typeParam()
        while peek()._1 != Token.RBRACKET && peek()._1 != Token.EOF do
          eat(Token.COMMA)
          items += typeParam()
        eat(Token.RBRACKET)
        items.toList

    def typeParam(): TypeParam =
      val id = ident()
      val bound =
        if peek()._1 == Token.SUBTYPE then
          eat(Token.SUBTYPE)
          typ()
        else
          EmptyTypeTree()(id.pos)

      TypeParam(id, bound)(id.pos | bound.pos)

    def params(): List[Param] =
      val (token, _) = peek()
      if token == Token.RPAREN then Nil
      else paramsRest(mutable.ArrayBuffer(param()))

    def param(): Param =
      val id = ident()
      eat(Token.COLON)
      val tpt = typ()
      Param(id, tpt)(id.pos | tpt.pos)

    def paramsRest(acc: mutable.ArrayBuffer[Param]): List[Param] =
      val (token, _) = peek()
      if token == Token.RPAREN || token == Token.EOF then acc.toList
      else
        val span = eat(Token.COMMA)
        paramsRest(acc += param())

    def phrase(): Phrase = phrase(mutable.ArrayBuffer.empty[TypeDef])

    def phrase(tdefs: mutable.ArrayBuffer[TypeDef]): Phrase =
      val (token, span) = peek()
      token match
        case Token.TYPE   =>
          phrase(tdefs += typeDef())

        case _ =>

          word() match
            case Some(w) =>
              phraseRest(tdefs.toList, mutable.ArrayBuffer(w))

            case None    =>
              Phrase(tdefs.toList, words = Nil)(span)

    def phraseRest(tdefs: List[TypeDef], words: mutable.ArrayBuffer[Word]): Phrase =
      word() match
        case Some(w) =>
          phraseRest(tdefs, words += w)

        case None =>
          val pos = words.head.pos | words.last.pos
          Phrase(tdefs, words.toList)(pos)

    def word(): Option[Word] =
      val (token, span) = peek()
      token match
        case Token.LPAREN    => Some(fence())
        case Token.LBRACE    => Some(record())
        case Token.IF        => Some(ifElse())
        case Token.MATCH     => Some(patmat())
        case Token.WHILE     => Some(whileDo())
        case Token.TAG       => Some(variant())

        case _: Token.Ident  =>
          val id = ident()
          peek() match
            case (Token.EQL, _) => Some(assign(id))
            case (Token.DOT, _) => Some(select(id))
            case (Token.LBRACKET, _) => Some(typeApply(id))
            case _ => Some(id)

        case Token.VAL | Token.VAR   =>
          Some(valDef(token))

        case litToken: Token.IntLit  =>
          next()
          Some(IntLit(litToken.value)(span))

        case litToken: Token.BoolLit =>
          next()
          Some(BoolLit(litToken.value)(span))

        case token =>
          None

    def typ(): TypeTree =
      peek() match
        case (Token.LBRACE, _)   => recordType()
        case (Token.Ident("<"), _) => unionType()
        case _ =>
          val id = ident()
          if peek()._1 == Token.LBRACKET then
            appliedType(id)
          else
            id

    def recordType(): RecordType =
      val span1 = eat(Token.LBRACE)
      val fieldDecls = fields(mutable.ArrayBuffer.empty)
      val span2 = eat(Token.RBRACE)
      RecordType(fieldDecls)(span1 | span2)

    def unionType(): UnionType =
      val span1 = eat(Token.Ident("<"))
      val branchDecls = branches(mutable.ArrayBuffer.empty)
      val span2 = eat(Token.Ident(">"))
      UnionType(branchDecls)(span1 | span2)

    def appliedType(tctor: Ident): AppliedType =
      val targs = typeArgs()
      val endPos = targs.last.pos
      AppliedType(tctor, targs.toList)(tctor.pos | endPos)

    def typeArgs(): List[TypeTree] =
      eat(Token.LBRACKET)
      val targs = new mutable.ArrayBuffer[TypeTree]
      targs += typ()
      while peek()._1 != Token.RBRACKET && peek()._1 != Token.EOF do
        eat(Token.COMMA)
        targs += typ()
      val span = eat(Token.RBRACKET)
      targs.toList

    def fields(acc: mutable.ArrayBuffer[Field]): List[Field] =
      peek() match
        case (Token.RBRACE | Token.EOF, _) => acc.toList
        case _ =>
          if acc.nonEmpty then eat(Token.COMMA)
          val id = ident()
          eat(Token.COLON)
          val tp = typ()
          val field = Field(id, tp)(id.pos | tp.pos)
          fields(acc += field)

    def branches(acc: mutable.ArrayBuffer[Branch]): List[Branch] =
      peek() match
        case (Token.Ident(">") | Token.EOF, _) => acc.toList
        case _ =>
          if acc.nonEmpty then eat(Token.COMMA)
          val tag = ident()
          val tps = new mutable.ArrayBuffer[TypeTree]
          while
            peek() match
              case (Token.COMMA | Token.Ident(">") | Token.EOF, span) =>
                false
              case _ =>
                if tps.nonEmpty then
                  val id = ident()
                  if id.name != "*" then
                    error("Expect *, found = " + id.name, id.pos)
                    false
                  else
                    tps += typ()
                    true
                else
                  tps += typ()
                  true
            end match
          do ()

          val posEnd = if tps.isEmpty then tag.pos else tps.last.pos
          val branch = Branch(tag, tps.toList)(tag.pos | posEnd)
          branches(acc += branch)

    def ident(): Ident =
      val (token, span) = next()
      token match
        case id: Token.Ident =>
          Ident(id.name)(span)

        case token =>
          error("Expect identifier, found token " + token, span)
          Ident("error")(span)

    def fence(): Word =
      val span1 = eat(Token.LPAREN)
      val words = phrase()
      val span2 = eat(Token.RPAREN)
      Fence(words)(span1 | span2)

    def ifElse(): Word =
      val span1 = eat(Token.IF)
      val cond = phrase()
      eat(Token.THEN)
      val thenp = phrase()
      // else is optional
      val (token, span3) = peek()
      val elsep =
        if token == Token.ELSE then
          eat(Token.ELSE)
          phrase()
        else
          Phrase(Nil, Nil)(span3)
      val span2 = eat(Token.END)
      If(cond, thenp, elsep)(span1 | span2)

    def whileDo(): Word =
      val span1 = eat(Token.WHILE)
      val cond = phrase()
      eat(Token.DO)
      val body = phrase()
      val span2 = eat(Token.END)
      While(cond, body)(span1 | span2)

    def assign(id: Ident): Assign =
      eat(Token.EQL)
      val words = phrase()
      val span2 = eat(Token.SEMICOL)
      Assign(id, words)(id.pos | span2)

    def select(qual: Ident | Select): Select =
      eat(Token.DOT)
      val id = ident()
      val sel = Select(qual, id.name)(qual.pos | id.pos)
      peek() match
        case (Token.DOT, _) => select(sel)
        case _ => sel

    def typeApply(id: Ident): TypeApply =
      val targs = typeArgs()
      val endPos = targs.last.pos
      TypeApply(id, targs)(id.pos | endPos)

    def record(): RecordLit =
      val span1 = eat(Token.LBRACE)
      val args = namedArgs(mutable.ArrayBuffer.empty)
      val span2 = eat(Token.RBRACE)
      RecordLit(args)(span1 | span2)

    def namedArgs(acc: mutable.ArrayBuffer[NamedArg]): List[NamedArg] =
      peek() match
        case (Token.RBRACE | Token.EOF, _) => acc.toList
        case _ =>
          if acc.nonEmpty then eat(Token.COMMA)
          namedArgs(acc += namedArg())

    def namedArg(): NamedArg =
      val id = ident()
      eat(Token.EQL)
      val arg = phrase()
      NamedArg(id, arg)(id.pos | arg.pos)

    def variant(): Variant =
      val span1 = eat(Token.TAG)
      val tag = ident()
      val words = new mutable.ArrayBuffer[Word]
      while
        word() match
          case Some(w) =>
            words += w
            true
          case _ =>
            false
      do ()
      eat(Token.OF)
      val tp = typ()
      Variant(tag, words.toList, tp)(span1 | tp.pos)

    def patmat(): Match =
      val span1 = eat(Token.MATCH)
      val scrutinee = phrase()
      val caseDecls = cases(mutable.ArrayBuffer.empty)
      val span2 = eat(Token.END)
      Match(scrutinee, caseDecls)(span1 | span2)

    def cases(acc: mutable.ArrayBuffer[Case]): List[Case] =
      peek() match
        case (Token.END | Token.EOF, _) => acc.toList
        case _ =>
          val span1 = eat(Token.CASE)
          val pat = pattern()
          eat(Token.RARROW)
          val body = phrase()
          val caseDecl = Case(pat, body)(span1 | body.pos)
          cases(acc += caseDecl)

    def pattern(): Pattern =
      peek() match
       case (Token.TAG, _) =>
         val span1 = eat(Token.TAG)
         val tag = ident()
         val bindings = new mutable.ArrayBuffer[Ident]
         while
           peek() match
             case (Token.RARROW, _) =>
               false

             case (_: Token.Ident, _) =>
               bindings += ident()
               true

             case (token, span) =>
               error("Expect a name, found = " + token, span)
               next()
               false
         do ()

         val posEnd = if bindings.isEmpty then tag.pos else bindings.last.pos
         TagPat(tag, bindings.toList)(span1 | posEnd)

       case (Token.Ident("_"), span) =>
         next()
         Wildcard()(span)

       case (token, span) =>
         error("Expect a pattern, found = " + token, span)
         next()
         Wildcard()(span)
