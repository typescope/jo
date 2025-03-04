/************************************************************************
 *                                                                      *
 * The parser for the stack-oriented language.                          *
 *                                                                      *
 ************************************************************************/
package parsing

import ast.Ast.*
import ast.Positions
import ast.Positions.*

import reporting.Reporter
import reporting.Reporter.{ error, warn }

import Tokens.*
import Parser.SyntaxError

import scala.collection.mutable

/***********************************************************************
 *
 * Parsing
 *
 ***********************************************************************/

object Parser:
  def main(args: Array[String]): Unit =
    Reporter.monitor:
      val nss = Parser.parse(args.toList)
      for ns <- nss do
        println(ns.source + ":")
        println(ns.show)
        println

  def parse(sourceFiles: List[String])(using Reporter): List[Namespace] =
    for file <- sourceFiles.sorted yield
      Parser.parse(file)

  /** Parse the supplied code */
  def parse(path: String)(using rp: Reporter): Namespace =
    val source = Reporter.source(path)
    val parser = new Parser(source.content)(using rp, source)
    parser.parse()

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

class Parser(code: String)(using reporter: Reporter, source: Source):
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

  def repeated[T](parseItem: => Option[T]): List[T] =
    val items = new mutable.ArrayBuffer[T]
    var continue = true
    while continue do
      parseItem match
        case Some(item) =>
          items += item

        case None =>
          continue = false
    end while
    items.toList

  def oneOrMore[T](parseItem: () => T, sep: Token): List[T] =
    val items = new mutable.ArrayBuffer[T]
    items += parseItem()
    var continue = peek() == sep
    while continue do
      next()
      items += parseItem()
      continue = peek() == sep

    items.toList

  def parse(): Namespace =
    val nspace = namespace()
    // With parsing errors, ensure finish scanning
    while peek() != Token.EOF do eat(Token.EOF)
    nspace

  def namespace(): Namespace =
    val item = peek()
    val id =
      item match
        case Token.NSPACE =>
          next()
          qualid()

        case _ =>
          Ident("__empty__")(Span(0, 0))

    val imports = repeated:
      if peek() == Token.IMPORT then Some(importStat())
      else None

    val defs = repeated:
        if peek() == Token.TYPE then Some(typeDef())
        else if peek() == Token.FUN then Some(funDef())
        else if peek() == Token.PARAM then Some(paramDef())
        else None

    val endSpan = if defs.isEmpty then id.span else defs.last.span

    Namespace(id, imports, defs, source.file)(id.span | endSpan)

  def qualid(): RefTree =
    var qual: RefTree = ident()
    while peek() == Token.DOT do
      next()
      val id = ident()
      qual = Select(qual, id.name)(qual.span | id.span)

    qual

  def importStat(): Import =
    val info = eat(Token.IMPORT)
    val id = qualid()
    Import(id)(info.span | id.span)

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

  def defDef(needBody: Boolean): FunDef =
    val defToken = eat(Token.DEF)
    val id = ident()
    val tparams = typeParams()
    val paramList = paramSection()
    val resType =
      if peek() == Token.COLON then
        eat(Token.COLON)
        typ()
      else
        EmptyTypeTree()(id.span)

    val body =
      if needBody then
        eat(Token.EQL)
        block(defToken.indent)
      else
        Block(Nil)(resType.span)

    eatEndOpt(defToken.indent)

    val preParamCount = 0
    FunDef(id, tparams, paramList, resType, body, preParamCount)(defToken.span | body.span)

  def paramDef(): ParamDef =
    val token = eat(Token.PARAM)
    val id = ident()
    eat(Token.COLON)
    val tpt = typ()
    val default =
      if peek() == Token.EQL then
        eat(Token.EQL)
        Some(block(token.indent))
      else
        None
    ParamDef(id, tpt, default)(token.span | tpt.span)

  def paramSection(): List[Param] =
    if peek() == Token.LPAREN then params() else Nil

  def typeDef(): TypeDef =
    val typeItem = eat(Token.TYPE)
    val id = ident()
    val tparams = typeParams()
    var isBound = false
    val rhs =
      if peek() == Token.EQL then
        eat(Token.EQL)
        typ()
      else if peek() == Token.SUBTYPE then
        isBound = true
        eat(Token.SUBTYPE)
        typ()
      else
        isBound = true
        EmptyTypeTree()(id.span)
    TypeDef(id, tparams, rhs, isBound)(typeItem.span | rhs.span)

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

  def params(typeOptional: Boolean = false): List[Param] =
    eat(Token.LPAREN)
    val list =
      if peek() == Token.RPAREN then Nil
      else paramsRest(mutable.ArrayBuffer(param(typeOptional)), typeOptional)
    eat(Token.RPAREN)
    list

  def param(typeOptional: Boolean): Param =
    val id = ident()
    val tpt =
      if peek() == Token.COLON then
        eat(Token.COLON)
        typ()
      else
        EmptyTypeTree()(id.span)
    Param(id, tpt)(id.span | tpt.span)

  def paramsRest(acc: mutable.ArrayBuffer[Param], typeOptional: Boolean): List[Param] =
    val token = peek()
    if token == Token.RPAREN || token == Token.EOF then acc.toList
    else
      eat(Token.COMMA)
      paramsRest(acc += param(typeOptional), typeOptional)

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

  def blockRest(phrases: mutable.ArrayBuffer[Word], limitIndent: Indent): Block =
    val item = peekItem()
    def finalResult: Block =
      if phrases.isEmpty then
        Block(phrases = Nil)(peekItem().span)
      else
        val span = phrases.head.span | phrases.last.span
        Block(phrases.toList)(span)

    if limitIndent.isUnindent(item.indent) then finalResult
    else
      try
        phrase() match
          case Some(phrs) => blockRest(phrases += phrs, limitIndent)
          case None       => finalResult

      catch case error: SyntaxError =>
        skipLine()
        blockRest(phrases, limitIndent)

  def optWithClause(expr: Word): Word =
    if peek() == Token.WITH then
      eat(Token.WITH)
      val args = oneOrMore(withArg, Token.COMMA)
      optAllowClause(expr, args)
    else
      optAllowClause(expr, bindings = Nil)

  def withArg(): WithArg =
    val id = qualid()
    eat(Token.EQL)
    val rhs = expr()
    WithArg(id, rhs)(id.span | rhs.span)

  def optAllowClause(expr: Word, bindings: List[WithArg]): Word =
    if peek() == Token.ALLOW then
      eat(Token.ALLOW)
      peek() match
        case Token.Ident("none") =>
          val token = next()
          With(expr, bindings, allow = Some(Nil))(expr.span | token.span)

        case _ =>
          val params = oneOrMore(qualid, Token.COMMA)
          With(expr, bindings, allow = Some(params))(expr.span | params.last.span)

      end match

    else if bindings.nonEmpty then
      With(expr, bindings, allow = None)(expr.span | bindings.last.span)

    else
      expr

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

    val isFirstTokenInLine = item.span.toPos.startLine != words.last.pos.endLine

    def finalResult: Word =
      if words.size == 1 then
        words.head
      else
        val span = words.head.span | words.last.span
        Expr(words.toList)(span)

    if item.token == Token.EOF || lineIndent.isUnindent(item.indent) && isFirstTokenInLine then
      finalResult

    else if lineIndent.isIndent(item.indent) && isFirstTokenInLine then
      val Block(phrases) = block(lineIndent)
      words ++= phrases
      finalResult

    else word() match
      case Some(w) =>
        exprRest(words += w, lineIndent)

      case None =>
        finalResult

  def isLambda(): Boolean =
    val token0 = peek(0)
    val token1 = peek(1)
    val token2 = peek(2)
    val token3 = peek(3)
    token0 == Token.LPAREN && (
      token1.isInstanceOf[Token.Ident] && (token2 == Token.COLON || token2 == Token.COMMA)
      || token1 == Token.RPAREN && token2 == Token.RARROW
      || token1.isInstanceOf[Token.Ident] && token2 == Token.RPAREN && token3 == Token.RARROW
    )

  def word(): Option[Word] =
    val item = peekItem()

    def optSelectAndApply(word: Word): Some[Word] =
      val item = peekItem()

      item.token match
        case Token.DOT      => optSelectAndApply(select(word))
        case Token.LBRACKET => optSelectAndApply(typeApply(word))

        case Token.LPAREN if item.span.followsImmediate(word.span) =>
          optSelectAndApply(apply(word))

        case _ => Some(word)

    item.token match
      case Token.LBRACE => optSelectAndApply(record())

      case Token.TAG    => optSelectAndApply(variant())

      case Token.LPAREN =>
        if isLambda() then Some(lambda()) else optSelectAndApply(fence())

      case _: Token.Ident =>
        val id = ident()
        optSelectAndApply(id)

      case lit: Token.IntLit  =>
        next()
        optSelectAndApply(IntLit(lit.value)(item.span))

      case lit: Token.BoolLit =>
        next()
        optSelectAndApply(BoolLit(lit.value)(item.span))

      case lit: Token.CharLit  =>
        next()
        optSelectAndApply(CharLit(lit.value)(item.span))

      case lit: Token.StringLit  =>
        next()
        optSelectAndApply(StringLit(lit.value)(item.span))

      case Token.OBJECT =>
        optSelectAndApply(objectLit())

      case token =>
        None

  def phrase(): Option[Word] =
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
        word().map: w =>
          if w.isInstanceOf[RefTree] && peek() == Token.ASSIGN then
            assign(w.asInstanceOf[RefTree], item.indent)
          else
            val expr = exprRest(mutable.ArrayBuffer(w), item.indent)
            optWithClause(expr)

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
      case Token.OBJECT   => objectType()
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
        val id = qualid()
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

  def objectType(): ObjectType =
    val objToken = eat(Token.OBJECT)
    eat(Token.LBRACE)
    val decls: List[ValDef | FunDef] = repeated:
        if peek() == Token.DEF then
          Some(defDef(needBody = false))

        else if peek() == Token.VAL || peek() == Token.VAR then
          val mod = next()
          val mutable = mod.token == Token.VAR
          val id = ident()
          eat(Token.COLON)
          val tpt = typ()
          val body = Block(phrases = Nil)(id.span)
          Some(ValDef(id, tpt, body, mutable)(mod.span | tpt.span))

        else None
    val endToken = eat(Token.RBRACE)
    ObjectType(decls)(objToken.span | endToken.span)

  def appliedType(tctor: RefTree): AppliedType =
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

  def fields(acc: mutable.ArrayBuffer[Param]): List[Param] =
    peek() match
      case Token.RBRACE | Token.EOF => acc.toList
      case _ =>
        if acc.nonEmpty then eat(Token.COMMA)
        val id = ident()
        eat(Token.COLON)
        val tp = typ()
        val field = Param(id, tp)(id.span | tp.span)
        fields(acc += field)

  def branches(acc: mutable.ArrayBuffer[Branch]): List[Branch] =
    peek() match
      case Token.Ident(">") | Token.EOF => acc.toList
      case _ =>
        if acc.nonEmpty then eat(Token.COMMA)
        val tag = ident()
        val params = paramSection()
        val spanEnd = if params.isEmpty then tag.span else params.last.span
        val branch = Branch(tag, params)(tag.span | spanEnd)
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
    val lparen = peekItem()
    val paramList = params(typeOptional = true)
    eat(Token.RARROW)
    val body = block(lparen.indent)
    Lambda(paramList, body)(lparen.span | body.span)

  def fence(): Word =
    val lparen = eat(Token.LPAREN)
    val nested = phrase() match
      case Some(p) =>
        Block(p :: Nil)(p.span)

      case None =>
        error("Phrase expected within parentheses", lparen.span.toPos)
        Block(Nil)(lparen.span)

    val rparen = eat(Token.RPAREN)
    // having span covering `(` is important for checking alignment
    val span = lparen.span | rparen.span
    if !span.toPos.isOneLine then
      warn("Use indented syntax when parentheses span multiple lines", span.toPos)
    Fence(nested)(span)

  def ifElse(): Word =
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

  def whileDo(): Word =
    val whileItem = eat(Token.WHILE)
    val cond = expr()
    val doItem = eat(Token.DO)
    val body = block(doItem.indent)

    eatEndOpt(whileItem.indent)

    While(cond, body)(whileItem.span | body.span)

  def assign(ref: RefTree, limitIndent: Indent): Assign =
    eat(Token.ASSIGN)
    val rhs = block(limitIndent)
    Assign(ref, rhs)(ref.span | rhs.span)

  def select(qual: Word): Select =
    eat(Token.DOT)
    val id = ident()
    val sel = Select(qual, id.name)(qual.span | id.span)
    peek() match
      case Token.DOT => select(sel)
      case _ => sel

  def typeApply(fun: Word): TypeApply =
    val targs = typeArgs()
    val last = targs.last
    TypeApply(fun, targs)(fun.span | last.span)

  def apply(fun: Word): Apply =
    val (args, span) = termArgs()
    Apply(fun, args)(fun.span | span)

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

  def termArgs(): (List[Word], Span) =
    val acc: mutable.ArrayBuffer[Word] = mutable.ArrayBuffer.empty
    val startItem = eat(Token.LPAREN)
    acc += expr()
    var token = peek()
    while
      token == Token.COMMA
    do
      eat(Token.COMMA)
      acc += expr()
      token = peek()

    val endItem = eat(Token.RPAREN)
    val span = startItem.span | endItem.span
    (acc.toList, span)

  def variant(): Variant =
    val tagSign = eat(Token.TAG)
    val tag = ident()
    val (args, span) = if peek() == Token.LPAREN then termArgs() else (Nil, tag.span)
    val tp =
      if peek() == Token.AS then
        eat(Token.AS)
        typ()
      else
        EmptyTypeTree()(span.endPoint)

    Variant(tag, args, tp)(tagSign.span | tp.span)

  def objectLit(): Object =
    val objToken = eat(Token.OBJECT)
    eat(Token.LBRACE)
    val members: List[ValDef | FunDef] = repeated:
        if peek() == Token.DEF then Some(defDef(needBody = true))
        else if peek() == Token.VAL then Some(valDef(Token.VAL))
        else if peek() == Token.VAR then Some(valDef(Token.VAR))
        else None
    val endToken = eat(Token.RBRACE)
    Object(members)(objToken.span | endToken.span)

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

  def product_pattern(): Pattern =
    val tagSign = eat(Token.TAG)
    val tag = ident()
    val bindings = if peek() == Token.LPAREN then pattern_bindings() else Nil
    val spanEnd = if bindings.isEmpty then tag.span else bindings.last.span
    TagPat(tag, bindings)(tagSign.span | spanEnd)

  def pattern_bindings(): List[Ident] =
    val bindings = new mutable.ArrayBuffer[Ident]
    eat(Token.LPAREN)
    bindings += ident()
    var token = peek()
    while
      token == Token.COMMA
    do
      eat(Token.COMMA)
      bindings += ident()
      token = peek()

    eat(Token.RPAREN)
    bindings.toList

  def pattern(): Pattern =
    peek() match
     case Token.TAG =>
       product_pattern()

     case Token.Ident("_") =>
       val item = next()
       Wildcard()(item.span)

     case _ =>
       val item = next()
       error("Expect a pattern, found = " + item.token, item.span.toPos)
       Wildcard()(item.span)
