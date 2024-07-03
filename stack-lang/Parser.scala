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
  /** Support for indentation syntax
    *
    * The indentation syntax is motivated for avoiding explicit semicolon
    * or `end` to end a construct.
    *
    * With indentation-syntax, a phrase ends if a token is beyond the limit
    * indentation.
    *
    *
    * - For a val or fun definition, the limit is the line indentation of `val`
    *   and `fun`.
    *
    * - For an indented block with `:`, the limit is the line indentation of `:`.
    *
    * - For while/do, limit for condition is -1, for the body is the line
    *   indentation of `do`.
    *
    * - For if/then/else, limit for condition is -1, for the branches are the
    *   line indentations of then and else respectively.
    *
    * - For case definitions, the limit is the line indentaion of `case`.
    *
    * The indentation info is the same for all tokens of the same line.
    */
  class Indent(private val line: Int, private val indent: Int):
    /** Whether the other indentation is a unindentation to the current one */
    def isUnindent(other: Indent): Boolean =
      this.line != other.line && other.indent <= this.indent

    def isSame(other: Indent): Boolean = this.indent == other.indent

  val IndentAcceptAll = Indent(-1, -1)

  enum Token:
    case LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE
    case OF, IF, THEN, ELSE, VAL, VAR, FUN,  WHILE, DO, TYPE, MATCH, CASE, END
    case TAG, COMMA, DOT, EOF
    case COLON, RARROW, EQL, SUBTYPE
    case IntLit(value: Int)
    case BoolLit(value: Boolean)
    case Ident(name: String)

    def withInfo(span: Span, indent: Indent): TokenInfo =
      TokenInfo(this, span, indent)

  def parse(code: String)(using Reporter): Prog =
    new StackLangParser(code).parse()


  /** The indent info is the same for all tokens of the same line */
  case class TokenInfo(token: Token, pos: Span, indent: Indent)

  trait Scanner:
    def next(): TokenInfo

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

    /** Current line number, starting from 0 */
    private var lineNum: Int = 0

    /** Indentation of the current line */
    private var lineIndentation: Int = countStartingSpace()

    /** Starting offset for the current token */
    private var curTokenStart: Int = -1

    /** Used to create token content */
    private val sb = new StringBuilder

    def curChar() = code(index)

    def eat(): Char =
      val c = curChar()
      index += 1
      if c == '\n' then
        lineNum += 1
        reporter.addLineOffset(index)
        lineIndentation = countStartingSpace()
      else if !hasMore() then
        reporter.addLineOffset(index)
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

    def lineIndent(): Indent = Indent(lineNum, lineIndentation)

  class StackLangScanner(stream: CharStream)(using Reporter) extends Scanner:
    import Scanner.{ isDigit, isLetter, isNameStart, isNameRest, isSpace, isOperator }

    def this(code: String)(using Reporter) = this(new CharStream(code))

    /** Return the token, its span and the line indentation where the token ends */
    def next(): TokenInfo =
      nextToken().withInfo(stream.tokenSpan(), stream.lineIndent())

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
     * A scanner that supports peeking tokens ahead.
     */
  class LookAheadScanner(scanner: Scanner):
    var peekedTokens: mutable.ListBuffer[TokenInfo] = new mutable.ListBuffer

    /** Return the token, its span and the line indentation where the token ends */
    def next(): TokenInfo =
      if peekedTokens.isEmpty then
        scanner.next()
      else
        peekedTokens.remove(0)

    def peekItem(i: Int): TokenInfo =
      var isEOF = false
      while peekedTokens.size <= i && !isEOF do
        val item = scanner.next()
        isEOF = item.token == Token.EOF
        peekedTokens.append(item)

      if isEOF then peekedTokens.last else peekedTokens(i)

    def peek(i: Int): Token = peekItem(i).token

  class StackLangParser(code: String)(using Reporter) extends Parser:
    val scanner = new LookAheadScanner(new StackLangScanner(code))

    def next(): TokenInfo = scanner.next()
    def peek(): Token = scanner.peek(0)
    def peek(i: Int): Token = scanner.peek(i)
    def peekItem(): TokenInfo = scanner.peekItem(0)
    def eat(expect: Token): TokenInfo =
      val item = next()
      if item.token != expect then
        error("Unexpected token, found = " + item.token + ", expect = " + expect, item.pos)
      item

    /** Eat the next `end` if the indentation matches */
    def eatEndOpt(indent: Indent) =
      val peekedItem = peekItem()
      if
        peekedItem.token == Token.END
        && peekedItem.indent.isSame(indent)
      then
        eat(Token.END)

    def checkAlign(reference: TokenInfo, item: TokenInfo): Unit =
      if !reference.indent.isSame(item.indent) then
        warn(s"${item.token} is not aligned with ${reference.token}", item.pos)


    def parse(): Prog =
      val p = prog()
      // With parsing errors, ensure finish scanning
      while peek() != Token.EOF do next()
      p

    def prog(): Prog =
      val defs = definitions(new mutable.ArrayBuffer)
      val words = phrase(IndentAcceptAll)
      eat(Token.EOF)
      Prog(defs, words)

    def definitions(acc: mutable.ArrayBuffer[Def]): List[Def] =
      val token = peek()
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
      val mod = eat(modifier)
      val id = ident()

      val tpt =
        if peek() == Token.COLON then
          eat(Token.COLON)
          typ()
        else
          EmptyTypeTree()(id.pos)

      eat(Token.EQL)
      val rhs = phrase(mod.indent)
      ValDef(id, tpt, rhs, mutable)(mod.pos | rhs.pos)

    def funDef(): FunDef =
      val fun = eat(Token.FUN)
      val id = ident()
      val tparams = typeParams()
      val paramList = params()
      val resType =
        if peek() == Token.COLON then
          eat(Token.COLON)
          typ()
        else
          EmptyTypeTree()(id.pos)

      eat(Token.EQL)
      val body = phrase(fun.indent)

      eatEndOpt(fun.indent)

      FunDef(id, tparams, paramList, resType, body)(fun.pos | body.pos)

    def typeDef(): TypeDef =
      val typeItem = eat(Token.TYPE)
      val id = ident()
      val tparams = typeParams()
      eat(Token.EQL)
      val rhs = typ()
      TypeDef(id, tparams, rhs)(typeItem.pos | rhs.pos)

    def typeParams(): List[TypeParam] =
      if peek() != Token.LBRACKET then Nil
      else
        eat(Token.LBRACKET)
        val items = new mutable.ArrayBuffer[TypeParam]
        items += typeParam()
        while peek() != Token.RBRACKET && peek() != Token.EOF do
          eat(Token.COMMA)
          items += typeParam()
        eat(Token.RBRACKET)
        items.toList

    def typeParam(): TypeParam =
      val id = ident()
      val bound =
        if peek() == Token.SUBTYPE then
          eat(Token.SUBTYPE)
          typ()
        else
          EmptyTypeTree()(id.pos)

      TypeParam(id, bound)(id.pos | bound.pos)

    def params(): List[Param] =
      eat(Token.LPAREN)
      val list =
        if peek() == Token.RPAREN then Nil
        else paramsRest(mutable.ArrayBuffer(param()))
      eat(Token.RPAREN)
      list

    def param(): Param =
      val id = ident()
      eat(Token.COLON)
      val tpt = typ()
      Param(id, tpt)(id.pos | tpt.pos)

    def paramsRest(acc: mutable.ArrayBuffer[Param]): List[Param] =
      val token = peek()
      if token == Token.RPAREN || token == Token.EOF then acc.toList
      else
        val span = eat(Token.COMMA)
        paramsRest(acc += param())

    /** Parse a phrase within the indentation */
    def phrase(limitIndent: Indent): Phrase =
      phrase(mutable.ArrayBuffer.empty[TypeDef], limitIndent)

    def phrase(tdefs: mutable.ArrayBuffer[TypeDef], limitIndent: Indent): Phrase =
      val item = peekItem()
      if limitIndent.isUnindent(item.indent) then
        Phrase(tdefs = Nil, words = Nil)(item.pos.point)

      else item.token match
        case Token.TYPE   =>
          phrase(tdefs += typeDef(), limitIndent)

        case _ =>

          word() match
            case Some(w) =>
              phraseRest(tdefs.toList, mutable.ArrayBuffer(w), limitIndent)

            case None    =>
              Phrase(tdefs.toList, words = Nil)(item.pos.point)

    def phraseRest(
        tdefs: List[TypeDef],
        words: mutable.ArrayBuffer[Word],
        limitIndent: Indent
      ): Phrase =
      val item = peekItem()
      if limitIndent.isUnindent(item.indent) then
        val pos = words.head.pos | words.last.pos
        Phrase(tdefs, words.toList)(pos)
      else word() match
        case Some(w) =>
          phraseRest(tdefs, words += w, limitIndent)

        case None =>
          val pos = words.head.pos | words.last.pos
          Phrase(tdefs, words.toList)(pos)

    def word(): Option[Word] =
      val item = peekItem()
      item.token match
        case Token.LPAREN    => Some(lambdaOrFence())
        case Token.LBRACE    => Some(record())
        case Token.IF        => Some(ifElse())
        case Token.MATCH     => Some(patmat())
        case Token.WHILE     => Some(whileDo())
        case Token.TAG       => Some(variant())

        case _: Token.Ident  =>
          val id = ident()
          peek() match
            case Token.EQL => Some(assign(id, item.indent))
            case Token.DOT => Some(select(id))
            case Token.LBRACKET => Some(typeApply(id))
            case _ => Some(id)

        case Token.VAL | Token.VAR   =>
          Some(valDef(item.token))

        case litToken: Token.IntLit  =>
          next()
          Some(IntLit(litToken.value)(item.pos))

        case litToken: Token.BoolLit =>
          next()
          Some(BoolLit(litToken.value)(item.pos))

        case token =>
          None

    def typ(): TypeTree =
      val tps = simpleTypes()
      val item = peekItem()
      item.token match
        case Token.RARROW =>
          next()
          val resType = typ()
          FunctionType(tps, resType)(tps.head.pos | resType.pos)

        case token =>
          if tps.size > 1 then
            error("`=>` expected, found = " + token, item.pos)
            tps.head
          else
            tps.head

    def simpleTypes(): List[TypeTree] =
      val tps = new mutable.ArrayBuffer[TypeTree]
      tps += simpleType()
      while peek() == Token.Ident("*") do
        next()
        tps += simpleType()

      tps.toList

    def simpleType(): TypeTree =
      peek() match
        case Token.LBRACE   => recordType()
        case Token.Ident("<") => unionType()

        case Token.LPAREN   =>
          next()
          val tp = typ()
          eat(Token.RPAREN)
          tp

        case Token.RARROW   =>
          val span = next()._2
          val resType = typ()
          FunctionType(paramTypes = Nil, resType)(span | resType.pos)

        case _ =>
          val id = ident()
          if peek() == Token.LBRACKET then
            appliedType(id)
          else
            id

    def recordType(): RecordType =
      val lbrace = eat(Token.LBRACE)
      val fieldDecls = fields(mutable.ArrayBuffer.empty)
      val rbrace = eat(Token.RBRACE)
      RecordType(fieldDecls)(lbrace.pos | rbrace.pos)

    def unionType(): UnionType =
      val less = eat(Token.Ident("<"))
      val branchDecls = branches(mutable.ArrayBuffer.empty)
      val big = eat(Token.Ident(">"))
      UnionType(branchDecls)(less.pos | big.pos)

    def appliedType(tctor: Ident): AppliedType =
      val targs = typeArgs()
      val endPos = targs.last.pos
      AppliedType(tctor, targs.toList)(tctor.pos | endPos)

    def typeArgs(): List[TypeTree] =
      eat(Token.LBRACKET)
      val targs = new mutable.ArrayBuffer[TypeTree]
      targs += typ()
      while peek() != Token.RBRACKET && peek() != Token.EOF do
        eat(Token.COMMA)
        targs += typ()
      val span = eat(Token.RBRACKET)
      targs.toList

    def fields(acc: mutable.ArrayBuffer[Field]): List[Field] =
      peek() match
        case Token.RBRACE | Token.EOF => acc.toList
        case _ =>
          if acc.nonEmpty then eat(Token.COMMA)
          val id = ident()
          eat(Token.COLON)
          val tp = typ()
          val field = Field(id, tp)(id.pos | tp.pos)
          fields(acc += field)

    def branches(acc: mutable.ArrayBuffer[Branch]): List[Branch] =
      peek() match
        case Token.Ident(">") | Token.EOF => acc.toList
        case _ =>
          if acc.nonEmpty then eat(Token.COMMA)
          val tag = ident()
          val tps1 =
            if peek() == Token.COMMA || peek() == Token.Ident(">") then Nil
            else simpleTypes()

          val tps2 =
            peek() match
              case Token.RARROW if tps1.nonEmpty =>
                next()
                val resType = typ()
                FunctionType(tps1, resType)(tps1.head.pos | resType.pos) :: Nil

              case _ => tps1

          val posEnd = if tps2.isEmpty then tag.pos else tps2.last.pos
          val branch = Branch(tag, tps2)(tag.pos | posEnd)
          branches(acc += branch)

    def ident(): Ident =
      val item = next()
      item.token match
        case id: Token.Ident =>
          Ident(id.name)(item.pos)

        case token =>
          error("Expect identifier, found token " + token, item.pos)
          Ident("error")(item.pos)

    def lambdaOrFence(): Word =
      val token1 = peek(1)
      val token2 = peek(2)
      if
        token1.isInstanceOf[Token.Ident] && token2 == Token.COLON
        || token1 == Token.RPAREN && token2 == Token.RARROW
      then
        lambda()
      else
        fence()

    def lambda(): Word =
      val paren = peekItem()
      val paramList = params()
      eat(Token.RARROW)
      val body = phrase(paren.indent)
      Lambda(paramList, body)(paren.pos | body.pos)

    def fence(): Word =
      val lparen = eat(Token.LPAREN)
      val words = phrase(IndentAcceptAll)
      val rparen = eat(Token.RPAREN)
      Fence(words)(lparen.pos | rparen.pos)

    def ifElse(): Word =
      val ifItem = eat(Token.IF)
      val cond = phrase(IndentAcceptAll)
      val thenItem = eat(Token.THEN)
      val thenp = phrase(thenItem.indent)
      checkAlign(ifItem, thenItem)

      // else is optional
      val nextItem = peekItem()
      val elsep =
        if nextItem.token == Token.ELSE then
          eat(Token.ELSE)
          checkAlign(ifItem, nextItem)
          phrase(nextItem.indent)
        else
          Phrase(Nil, Nil)(thenp.pos)

      eatEndOpt(ifItem.indent)

      If(cond, thenp, elsep)(ifItem.pos | elsep.pos)

    def whileDo(): Word =
      val whileItem = eat(Token.WHILE)
      val cond = phrase(IndentAcceptAll)
      val doItem = eat(Token.DO)
      val body = phrase(doItem.indent)

      eatEndOpt(whileItem.indent)

      While(cond, body)(whileItem.pos | body.pos)

    def assign(id: Ident, limitIndent: Indent): Assign =
      eat(Token.EQL)
      val rhs = phrase(limitIndent)
      Assign(id, rhs)(id.pos | rhs.pos)

    def select(qual: Ident | Select): Select =
      eat(Token.DOT)
      val id = ident()
      val sel = Select(qual, id.name)(qual.pos | id.pos)
      peek() match
        case Token.DOT => select(sel)
        case _ => sel

    def typeApply(id: Ident): TypeApply =
      val targs = typeArgs()
      val endPos = targs.last.pos
      TypeApply(id, targs)(id.pos | endPos)

    def record(): RecordLit =
      val lbrace = eat(Token.LBRACE)
      val args = namedArgs(mutable.ArrayBuffer.empty)
      val rbrace = eat(Token.RBRACE)
      RecordLit(args)(lbrace.pos | rbrace.pos)

    def namedArgs(acc: mutable.ArrayBuffer[NamedArg]): List[NamedArg] =
      peek() match
        case Token.RBRACE | Token.EOF => acc.toList
        case _ =>
          // TODO: comma is not necessary for multi-line syntax
          if acc.nonEmpty then eat(Token.COMMA)
          namedArgs(acc += namedArg())

    def namedArg(): NamedArg =
      val id = ident()
      eat(Token.EQL)
      val arg = phrase(IndentAcceptAll)
      NamedArg(id, arg)(id.pos | arg.pos)

    def variant(): Variant =
      val tagSign = eat(Token.TAG)
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
      Variant(tag, words.toList, tp)(tagSign.pos | tp.pos)

    def patmat(): Match =
      val matchItem = eat(Token.MATCH)
      val scrutinee = phrase(IndentAcceptAll)
      val caseDecls = cases(mutable.ArrayBuffer.empty)

      eatEndOpt(matchItem.indent)

      val span2 = if caseDecls.isEmpty then scrutinee.pos else caseDecls.last.pos
      Match(scrutinee, caseDecls)(matchItem.pos | span2)

    def cases(acc: mutable.ArrayBuffer[(Case, TokenInfo)]): List[Case] =
      if peek() == Token.CASE then
        val caseItem = eat(Token.CASE)

        if acc.nonEmpty then
          checkAlign(acc.head._2, caseItem)

        val pat = pattern()
        eat(Token.RARROW)
        val body = phrase(caseItem.indent)
        val caseDecl = Case(pat, body)(caseItem.pos | body.pos)
        cases(acc += caseDecl -> caseItem)
      else
        acc.map(_._1).toList

    def pattern(): Pattern =
      peek() match
       case Token.TAG =>
         val tagSign = eat(Token.TAG)
         val tag = ident()
         val bindings = new mutable.ArrayBuffer[Ident]
         while
           peek() match
             case Token.RARROW =>
               false

             case _: Token.Ident =>
               bindings += ident()
               true

             case _ =>
               val item = next()
               error("Expect a name, found = " + item.token, item.pos)
               false
         do ()

         val posEnd = if bindings.isEmpty then tag.pos else bindings.last.pos
         TagPat(tag, bindings.toList)(tagSign.pos | posEnd)

       case Token.Ident("_") =>
         val item = next()
         Wildcard()(item.pos)

       case _ =>
         val item = next()
         error("Expect a pattern, found = " + item.token, item.pos)
         Wildcard()(item.pos)
