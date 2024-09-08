/************************************************************************
 *                                                                      *
 * The parser for the stack-oriented language.                          *
 *                                                                      *
 ************************************************************************/

import scala.collection.mutable

import Ast.*
import Reporter.*
import Tokens.*
import Parser.SyntaxError

/***********************************************************************
 *
 * Parsing
 *
 ***********************************************************************/

object Parser:
  /** Parse the supplied code */
  def parse(code: String)(using Reporter): Prog =
    new Parser(code).parse()

   /** A scanner that supports peeking tokens ahead. */
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
  end LookAheadScanner

  class SyntaxError extends Exception
end Parser

class Parser(code: String)(using Reporter):
  val scanner = new Parser.LookAheadScanner(new Scanner(code))

  def next(): TokenInfo = scanner.next()
  def peek(): Token = scanner.peek(0)
  def peek(i: Int): Token = scanner.peek(i)
  def peekItem(): TokenInfo = scanner.peekItem(0)
  def eat(expect: Token): TokenInfo =
    val item = next()
    if item.token != expect then
      error("Unexpected token, found = " + item.token + ", expect = " + expect, item.span.toPos)
    item

  def skipLine() =
    val item = next()
    val limitIndent = item.indent
    while
      !limitIndent.isUnindent(peekItem().indent)
      && item.token != Token.EOF
    do
      next()

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
      warn(s"${item.token} is not aligned with ${reference.token}", item.span.toPos)


  def parse(): Prog =
    val p = prog()
    // With parsing errors, ensure finish scanning
    while peek() != Token.EOF do next()
    p

  def prog(): Prog =
    val blk = block(Indent(line = -1, indent = -1))
    eat(Token.EOF)
    Prog(blk.phrases)(blk.span)

  def valDef(modifier: Token): ValDef =
    val mutable = modifier == Token.VAR
    val mod = eat(modifier)
    val id = ident()

    val tpt =
      if peek() == Token.COLON then
        eat(Token.COLON)
        typ()
      else
        EmptyTypeTree()(id.span)

    eat(Token.EQL)
    val rhs = block(mod.indent)
    ValDef(id, tpt, rhs, mutable)(mod.span | rhs.span)

  def funDef(): FunDef =
    val fun = eat(Token.FUN)
    val preParamList = paramSection()
    val id = ident()
    val tparams = typeParams()
    val postParamList = paramSection()
    val resType =
      if peek() == Token.COLON then
        eat(Token.COLON)
        typ()
      else
        EmptyTypeTree()(id.span)

    eat(Token.EQL)
    val body = block(fun.indent)

    eatEndOpt(fun.indent)

    val paramList= preParamList ++ postParamList
    FunDef(id, tparams, paramList, resType, body, preParamList.size)(fun.span | body.span)

  def paramSection(): List[Param] =
    if peek() == Token.LPAREN then params() else Nil

  def typeDef(): TypeDef =
    val typeItem = eat(Token.TYPE)
    val id = ident()
    val tparams = typeParams()
    eat(Token.EQL)
    val rhs = typ()
    TypeDef(id, tparams, rhs)(typeItem.span | rhs.span)

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
        EmptyTypeTree()(id.span)

    TypeParam(id, bound)(id.span | bound.span)

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
    Param(id, tpt)(id.span | tpt.span)

  def paramsRest(acc: mutable.ArrayBuffer[Param]): List[Param] =
    val token = peek()
    if token == Token.RPAREN || token == Token.EOF then acc.toList
    else
      eat(Token.COMMA)
      paramsRest(acc += param())

  /** Parse a block within the indentation */
  def block(limitIndent: Indent): Block =
    val blk = blockRest(mutable.ArrayBuffer(), limitIndent)
    // check alignment of phrases in a block
    blk.phrases match
      case first :: rest =>
        val refPos = first.pos
        for phrs <- rest do
          if
            phrs.pos.startLineColumn != refPos.startLineColumn
            || phrs.pos.startLine == refPos.startLine
          then
            val diagnosis = s"expect offset = ${refPos.startLineColumn}, found = ${phrs.pos.startLineColumn}"
            error(s"The phrase is not vertically aligned in block, $diagnosis", phrs.pos)
      case _ =>
    blk

  def blockRest(phrases: mutable.ArrayBuffer[Phrase], limitIndent: Indent): Block =
    val item = peekItem()

    if limitIndent.isUnindent(item.indent) then
      if phrases.isEmpty then
        Block(phrases = Nil)(peekItem().span)
      else
        val span = phrases.head.span | phrases.last.span
        Block(phrases.toList)(span)
    else
      try
        phrase() match
          case Some(phrs) =>
            blockRest(phrases += phrs, limitIndent)

          case None =>
            val span =
              if phrases.nonEmpty then
                phrases.head.span | phrases.last.span
              else
                item.span
            Block(phrases.toList)(span)

      catch case error: SyntaxError =>
        skipLine()
        blockRest(phrases, limitIndent)

  def expr(): Word =
    val item = peekItem()
    word() match
      case Some(w) =>
        exprRest(mutable.ArrayBuffer(w), item.indent)

      case None =>
        error("Expect an expression, found " + item.token, item.span.toPos)
        throw new SyntaxError

  /** An expression ends with unindentation */
  def exprRest(words: mutable.ArrayBuffer[Word], lineIndent: Indent): Word =
    val item = peekItem()
    def finalResult: Word =
      if words.size == 1 then
        words.head
      else
        val span = words.head.span | words.last.span
        Expr(words.toList)(span)

    if item.token == Token.EOF || lineIndent.isUnindent(item.indent) then
      finalResult

    else if lineIndent.isIndent(item.indent) then
      val first = finalResult
      words.clear()

      val Block(phrases) = block(lineIndent)
      for phrase <- first :: phrases do
        phrase match
          case word: Word        => words += word
          case _                 => words += Block(phrase :: Nil)(phrase.span)
      finalResult

    else word() match
      case Some(w) =>
        exprRest(words += w, lineIndent)

      case None =>
        finalResult

  def isAssign(): Boolean =
    val token0 = peek(0)
    val token1 = peek(1)
    token0.isInstanceOf[Token.Ident] && token1 == Token.EQL

  def isLambda(): Boolean =
    val token0 = peek(0)
    val token1 = peek(1)
    val token2 = peek(2)
    token0 == Token.LPAREN && (
      token1.isInstanceOf[Token.Ident] && (token2 == Token.COLON || token2 == Token.COMMA)
      || token1 == Token.RPAREN && token2 == Token.RARROW
    )

  def word(): Option[Word] =
    val item = peekItem()

    def continue(word: Word): Some[Word] =
      peek() match
        case Token.DOT  => continue(select(word))
        case _ => Some(word)

    item.token match
      case Token.LBRACE => continue(record())
      case Token.TAG    => continue(variant())

      case Token.LPAREN =>
        if isLambda() then continue(lambda()) else continue(fence())

      case _: Token.Ident if !isAssign() =>
        val id = ident()
        peek() match
          case Token.LBRACKET => continue(typeApply(id))
          case _              => continue(id)

      case litToken: Token.IntLit  =>
        next()
        Some(IntLit(litToken.value)(item.span))

      case litToken: Token.BoolLit =>
        next()
        Some(BoolLit(litToken.value)(item.span))

      case token =>
        None

  def phrase(): Option[Phrase] =
    val item = peekItem()
    item.token match
      case Token.IF        => Some(ifElse())
      case Token.MATCH     => Some(patmat())
      case Token.WHILE     => Some(whileDo())

      case Token.VAL | Token.VAR   =>
        Some(valDef(item.token))

      case Token.FUN   =>
        Some(funDef())

      case Token.TYPE =>
        Some(typeDef())

      case token =>
        if isAssign() then
          val id = ident()
          Some(assign(id, item.indent))
        else
          word().map: w =>
            exprRest(mutable.ArrayBuffer(w), item.indent)

  def typ(): TypeTree =
    val tps = simpleTypes()
    val item = peekItem()
    item.token match
      case Token.RARROW =>
        next()
        val resType = typ()
        FunctionType(tps, resType)(tps.head.span | resType.span)

      case token =>
        if tps.size > 1 then
          error("`=>` expected, found = " + token, item.span.toPos)
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
        val arrow = next()
        val resType = typ()
        FunctionType(paramTypes = Nil, resType)(arrow.span | resType.span)

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
    RecordType(fieldDecls)(lbrace.span | rbrace.span)

  def unionType(): UnionType =
    val less = eat(Token.Ident("<"))
    val branchDecls = branches(mutable.ArrayBuffer.empty)
    val big = eat(Token.Ident(">"))
    UnionType(branchDecls)(less.span | big.span)

  def appliedType(tctor: Ident): AppliedType =
    val targs = typeArgs()
    val last = targs.last
    AppliedType(tctor, targs.toList)(tctor.span | last.span)

  def typeArgs(): List[TypeTree] =
    eat(Token.LBRACKET)
    val targs = new mutable.ArrayBuffer[TypeTree]
    targs += typ()
    while peek() != Token.RBRACKET && peek() != Token.EOF do
      eat(Token.COMMA)
      targs += typ()
    eat(Token.RBRACKET)
    targs.toList

  def fields(acc: mutable.ArrayBuffer[Field]): List[Field] =
    peek() match
      case Token.RBRACE | Token.EOF => acc.toList
      case _ =>
        if acc.nonEmpty then eat(Token.COMMA)
        val id = ident()
        eat(Token.COLON)
        val tp = typ()
        val field = Field(id, tp)(id.span | tp.span)
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
              FunctionType(tps1, resType)(tps1.head.span | resType.span) :: Nil

            case _ => tps1

        val spanEnd = if tps2.isEmpty then tag.span else tps2.last.span
        val branch = Branch(tag, tps2)(tag.span | spanEnd)
        branches(acc += branch)

  def ident(): Ident =
    val item = next()
    item.token match
      case id: Token.Ident =>
        Ident(id.name)(item.span)

      case token =>
        error("Expect identifier, found token " + token, item.span.toPos)
        Ident("error")(item.span)

  def lambda(): Word =
    val paren = peekItem()
    val paramList = params()
    eat(Token.RARROW)
    val body = block(paren.indent)
    Lambda(paramList, body)(paren.span | body.span)

  def fence(): Word =
    val lparen = eat(Token.LPAREN)
    val word = expr()
    val rparen = eat(Token.RPAREN)
    // having span covering `(` is important for checking alignment
    val span = lparen.span | rparen.span
    Block(word :: Nil)(span)

  def ifElse(): Phrase =
    val ifItem = eat(Token.IF)
    val cond = expr()
    val thenItem = eat(Token.THEN)
    val thenp = block(thenItem.indent)
    checkAlign(ifItem, thenItem)

    // else is optional
    val nextItem = peekItem()
    val elsep =
      if nextItem.token == Token.ELSE then
        eat(Token.ELSE)
        checkAlign(ifItem, nextItem)
        block(nextItem.indent)
      else
        Block(phrases = Nil)(thenp.span)

    eatEndOpt(ifItem.indent)

    If(cond, thenp, elsep)(ifItem.span | elsep.span)

  def whileDo(): Phrase =
    val whileItem = eat(Token.WHILE)
    val cond = expr()
    val doItem = eat(Token.DO)
    val body = block(doItem.indent)

    eatEndOpt(whileItem.indent)

    While(cond, body)(whileItem.span | body.span)

  def assign(id: Ident, limitIndent: Indent): Assign =
    eat(Token.EQL)
    val rhs = block(limitIndent)
    Assign(id, rhs)(id.span | rhs.span)

  def select(qual: Word): Select =
    eat(Token.DOT)
    val id = ident()
    val sel = Select(qual, id.name)(qual.span | id.span)
    peek() match
      case Token.DOT => select(sel)
      case _ => sel

  def typeApply(id: Ident): TypeApply =
    val targs = typeArgs()
    val last = targs.last
    TypeApply(id, targs)(id.span | last.span)

  def record(): RecordLit =
    val lbrace = eat(Token.LBRACE)
    val args = namedArgs(mutable.ArrayBuffer.empty)
    val rbrace = eat(Token.RBRACE)
    RecordLit(args)(lbrace.span | rbrace.span)

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
    val arg = expr()
    NamedArg(id, arg)(id.span | arg.span)

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
    Variant(tag, words.toList, tp)(tagSign.span | tp.span)

  def patmat(): Match =
    val matchItem = eat(Token.MATCH)
    val scrutinee = expr()
    val caseDecls = cases(mutable.ArrayBuffer.empty)

    eatEndOpt(matchItem.indent)

    val span2 = if caseDecls.isEmpty then scrutinee.span else caseDecls.last.span
    Match(scrutinee, caseDecls)(matchItem.span | span2)

  def cases(acc: mutable.ArrayBuffer[(Case, TokenInfo)]): List[Case] =
    if peek() == Token.CASE then
      val caseItem = eat(Token.CASE)

      if acc.nonEmpty then
        checkAlign(acc.head._2, caseItem)

      val pat = pattern()
      eat(Token.RARROW)
      val body = block(caseItem.indent)
      val caseDecl = Case(pat, body)(caseItem.span | body.span)
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
             error("Expect a name, found = " + item.token, item.span.toPos)
             false
       do ()

       val spanEnd = if bindings.isEmpty then tag.span else bindings.last.span
       TagPat(tag, bindings.toList)(tagSign.span | spanEnd)

     case Token.Ident("_") =>
       val item = next()
       Wildcard()(item.span)

     case _ =>
       val item = next()
       error("Expect a pattern, found = " + item.token, item.span.toPos)
       Wildcard()(item.span)
