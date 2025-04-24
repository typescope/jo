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
  def parse(path: String)(using rp: Reporter): Namespace = try
    val source = Reporter.source(path)
    val parser = new Parser(source.content)(using rp, source)
    parser.parse()
  catch case ex: java.nio.file.NoSuchFileException =>
    Reporter.abortInternal("Source not found: " + path)

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
    val item = peekItem()
    if item.token != expect then
      error("Unexpected token, found = " + item.token + ", expect = " + expect, item.span.toPos)
      throw new SyntaxError
    next()

  def skipIndented(limitIndent: Indent) =
    var item = peekItem()
    while
      !limitIndent.isUnindent(item.indent) && item.token != Token.EOF && {
        item = next()
        true
      }
    do
      item = peekItem()

  def skipUntil(tokens: Set[Token]) =
    var token = peek()
    while
      !tokens.contains(token) && token != Token.EOF && {
        next()
        true
      }
    do
      token = peek()

  /** Eat the next `end` if the indentation matches */
  def eatEndOpt(indent: Indent) =
    val peekedItem = peekItem()
    if
      peekedItem.token == Token.END
      && peekedItem.indent.isSameIndent(indent)
    then
      eat(Token.END)

  /** Eat optional comma */
  def eatCommaOpt() =
    if peek() == Token.COMMA then next()

  def checkAlign(reference: TokenInfo, item: TokenInfo, allowSameLine: Boolean = false): Unit =
    val refIndent = reference.indent
    val itemIndent = item.indent

    if
      !refIndent.isSameIndent(itemIndent)
      && !(allowSameLine && refIndent.isSameLine(itemIndent))
    then
      val diagnosis = s"expect offset = ${refIndent.tokenOffset}, found = ${itemIndent.tokenOffset}"
      warn(s"${item.token} is not aligned with ${reference.token}, $diagnosis", item.span.toPos)

  def repeated[T](skipToIfError: Set[Token])(parseItem: => Option[T]): List[T] =
    val items = new mutable.ArrayBuffer[T]
    var continue = true
    while continue do
      try
        parseItem match
          case Some(item) =>
            items += item

          case None =>
            continue = false
      catch case _: SyntaxError =>
        skipUntil(skipToIfError)
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
    skipUntil(Set(Token.EOF))
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

    val errorSkipImport = Set(Token.IMPORT, Token.TYPE, Token.FUN, Token.PARAM, Token.PATTERN)
    val imports = repeated(errorSkipImport):
      if peek() == Token.IMPORT then Some(importStat())
      else None

    val errorSkipDef = Set(Token.TYPE, Token.FUN, Token.PARAM, Token.PATTERN)
    val defs = repeated(errorSkipDef):
        if peek() == Token.TYPE then Some(typeDef())
        else if peek() == Token.FUN then Some(funDef())
        else if peek() == Token.PARAM then Some(paramDef())
        else if peek() == Token.PATTERN then Some(patDef())
        else
          val item = peekItem()
          if item.token != Token.EOF then
            error("Expect a definition, found = " + item.token, item.span.toPos)
            throw new SyntaxError
          else
            None

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

    val receiveParams = optReceiveParams()

    eat(Token.EQL)
    val body = block(fun.indent)

    eatEndOpt(fun.indent)

    val paramList = preParamList ++ postParamList
    FunDef(id, tparams, paramList, resType, receiveParams, body, preParamList.size)(fun.span | body.span)

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

    val receiveParams = optReceiveParams()

    val body =
      if needBody then
        eat(Token.EQL)
        block(defToken.indent)
      else
        Block(Nil)(resType.span)

    eatEndOpt(defToken.indent)

    val preParamCount = 0
    FunDef(id, tparams, paramList, resType, receiveParams, body, preParamCount)(defToken.span | body.span)

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

  def patDef(): PatDef =
    val pat = eat(Token.PATTERN)
    val preParamList = paramSection()
    val id = ident()
    val tparams = typeParams()
    val postParamList = paramSection()

    eat(Token.COLON)
    val resType = typ()

    eat(Token.EQL)
    val item = peekItem()
    if pat.indent.isUnindent(item.indent) then
       error("Expect pattern, found nothing before the unindentation", item.span.toPos)
       throw new SyntaxError

    val body = pattern()

    eatEndOpt(pat.indent)

    val paramList = preParamList ++ postParamList
    PatDef(id, tparams, paramList, resType, body, preParamList.size)(pat.span | body.span)

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
    blockRest(mutable.ArrayBuffer(), limitIndent, peekItem())

  def blockRest(phrases: mutable.ArrayBuffer[Word], limitIndent: Indent, refToken: TokenInfo): Block =
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
          case Some(phrase) =>
            checkAlign(refToken, item)
            blockRest(phrases += phrase, limitIndent, refToken)

          case None => finalResult

      catch case error: SyntaxError =>
        skipIndented(limitIndent)
        blockRest(phrases, limitIndent, refToken)

  def withClause(expr: Word): Word =
    eat(Token.WITH)
    val args = oneOrMore(withArg, Token.COMMA)
    With(expr, args)(expr.span | args.last.span)

  def withArg(): WithArg =
    val id = qualid()
    eat(Token.EQL)
    val rhs = expr()
    WithArg(id, rhs)(id.span | rhs.span)

  def allowClause(expr: Word): Word =
    eat(Token.ALLOW)
    peek() match
      case Token.Ident("none") =>
        val token = next()
        Allow(expr, params = Nil)(expr.span | token.span)

      case _ =>
        val params = oneOrMore(qualid, Token.COMMA)
        Allow(expr, params)(expr.span | params.last.span)

  def typeAscribe(expr: Word): Word =
    eat(Token.AS)
    val tpt = typ()
    TypeAscribe(expr, tpt)(expr.span | tpt.span)

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

      case Token.TAG    =>
        val tok = next()
        val id = ident()
        val tag = Tag(id)(tok.span | id.span)
        optSelectAndApply(tag)

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
          if w.isInstanceOf[RefTree] && peek() == Token.EQL then
            assign(w.asInstanceOf[RefTree], item.indent)
          else
            val expr = exprRest(mutable.ArrayBuffer(w), item.indent)

            def simplePhrase(word: Word): Word =
              val nextItem = peekItem()
              if !item.indent.isUnindent(nextItem.indent) then
                if peek() == Token.WITH then
                  simplePhrase(withClause(word))
                else if peek() == Token.ALLOW then
                  simplePhrase(allowClause(word))
                else if peek() == Token.AS then
                  simplePhrase(typeAscribe(word))
                else
                  word
              else
                word
              end if
            end simplePhrase

            val phraseRes = simplePhrase(expr)

            // Phrase is supposed to consume the whole line and all indented
            val stopItem = peekItem()
            if item.indent.isFirstOfLine && item.indent.isIndent(stopItem.indent) then
              error("Unexpected indented token " + stopItem.token, stopItem.span.toPos)
              skipIndented(item.indent)

            phraseRes

  def typ(): TypeTree =
    val startItem = peekItem()
    val tps = simpleTypes()
    val item = peekItem()
    item.token match
      case Token.RARROW =>
        next()
        val resType = typ()
        val params = optReceiveParams().getOrElse(Nil)
        val endSpan = if params.isEmpty then resType.span else params.last.span
        FunctionType(tps, resType, params)(startItem.span | endSpan)

      case Token.Ident("|") if tps.size == 1 =>
        next()
        val head = tps.head
        val rest = oneOrMore(simpleType, Token.Ident("|"))
        UnionType(head :: rest)(head.span | rest.last.span)

      case token =>
        if tps.size > 1 then
          error("`=>` expected, found = " + token, item.span.toPos)
          tps.head
        else
          tps.head

  def typesInParens(): List[TypeTree] =
    eat(Token.LPAREN)
    val tps =
      if peek() == Token.RPAREN then
        Nil
      else
        oneOrMore(typ, Token.COMMA)

    eat(Token.RPAREN)
    tps

  def simpleTypes(): List[TypeTree] =
    if peek() == Token.LPAREN then
      typesInParens()
    else
      simpleType() :: Nil

  def simpleType(): TypeTree =
    peek() match
      case Token.OBJECT   => objectType()
      case Token.LBRACE   => recordType()
      case Token.TAG      => tagType()

      case Token.LPAREN   =>
        next()
        val tp = typ()
        eat(Token.RPAREN)
        tp

      case Token.RARROW   =>
        val arrow = next()
        val resType = typ()
        val params = optReceiveParams().getOrElse(Nil)
        val endSpan = if params.isEmpty then resType.span else params.last.span
        FunctionType(paramTypes = Nil, resType, params)(arrow.span | endSpan)

      case _ =>
        val id = qualid()
        if peek() == Token.LBRACKET then
          appliedType(id)
        else
          id

  def optReceiveParams(): Option[List[RefTree]] =
    if peek() == Token.RECEIVES then
      eat(Token.RECEIVES)

      peek() match
        case Token.Ident("none") =>
          next()
          Some(Nil)

        case _ =>
          Some(oneOrMore(() => qualid(), Token.COMMA))
    else
      None

  def tagType(): TypeTree =
    val item = eat(Token.TAG)
    val tag = ident()
    val params = tagTypeParams()
    val spanEnd = if params.isEmpty then tag.span else params.last.span
    TagType(tag, params)(item.span | spanEnd)

  def tagTypeParams(): List[Param] =
    if peek() != Token.LPAREN then Nil
    else
      next()
      val params = new mutable.ArrayBuffer[Param]
      while peek() != Token.RPAREN do
        if params.nonEmpty then eat(Token.COMMA)

        if peek(1) == Token.COLON then
          val id = ident()
          next()
          val tp = typ()
          params += Param(id, tp)(id.span | tp.span)
        else
          val tp = typ()
          val id = Ident("_" + (params.size + 1))(tp.span)
          params += Param(id, tp)(id.span | tp.span)
      end while

      eat(Token.RPAREN)
      params.toList

  def fields(acc: mutable.ArrayBuffer[Param]): List[Param] =
    peek() match
      case Token.RBRACE | Token.EOF => acc.toList
      case _ =>
        if acc.nonEmpty then eatCommaOpt()
        val id = ident()
        eat(Token.COLON)
        val tp = typ()
        val field = Param(id, tp)(id.span | tp.span)
        fields(acc += field)

  def recordType(): RecordType =
    val lbrace = eat(Token.LBRACE)
    val fieldDecls = fields(mutable.ArrayBuffer.empty)
    val rbrace = eat(Token.RBRACE)
    RecordType(fieldDecls)(lbrace.span | rbrace.span)

  def objectType(): ObjectType =
    val objToken = eat(Token.OBJECT)
    eat(Token.LBRACE)
    val errorSkip = Set(Token.VAL, Token.VAR, Token.DEF)
    val decls: List[ValDef | FunDef] = repeated(errorSkip):
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
    val (targs, endSpan) = typeArgs()
    AppliedType(tctor, targs.toList)(tctor.span | endSpan)

  def typeArgs(): (List[TypeTree], Span) =
    val startToken = eat(Token.LBRACKET)
    val targs = new mutable.ArrayBuffer[TypeTree]
    targs += typ()
    while peek() != Token.RBRACKET && peek() != Token.EOF do
      eat(Token.COMMA)
      targs += typ()
    val endToken = eat(Token.RBRACKET)
    (targs.toList, startToken.span | endToken.span)

  def ident(): Ident =
    val item = next()
    item.token match
      case id: Token.Ident =>
        Ident(id.name)(item.span)

      case token =>
        error("Expect identifier, found token " + token, item.span.toPos)
        throw new SyntaxError

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
    checkAlign(ifItem, thenItem, allowSameLine = true)

    // else is optional
    val nextItem = peekItem()
    val elsep =
      if nextItem.token == Token.ELSE then
        eat(Token.ELSE)
        checkAlign(ifItem, nextItem, allowSameLine = true)
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
    eat(Token.EQL)
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
    val (targs, endSpan) = typeArgs()
    TypeApply(fun, targs)(fun.span | endSpan)

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
    val startItem = eat(Token.LPAREN)
    if peek() == Token.RPAREN then
      val endItem = eat(Token.RPAREN)
      return (Nil, startItem.span | endItem.span)

    val acc: mutable.ArrayBuffer[Word] = mutable.ArrayBuffer.empty
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

  def objectLit(): Object =
    val objToken = eat(Token.OBJECT)
    eat(Token.LBRACE)
    val errorSkip = Set(Token.DEF, Token.VAL, Token.VAR)
    val members: List[ValDef | FunDef] = repeated(errorSkip):
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

  def applyPattern(apply: Tag | Ident): Word =
    val bindings = patternArgs()
    val spanEnd = bindings.last.span
    Apply(apply, bindings)(apply.span | spanEnd)

  def patternArgs(): List[Word] =
    val nested = new mutable.ArrayBuffer[Word]
    eat(Token.LPAREN)
    nested += pattern()
    var token = peek()
    while
      token == Token.COMMA
    do
      eat(Token.COMMA)
      nested += pattern()
      token = peek()

    eat(Token.RPAREN)
    nested.toList

  def typePattern(id: Ident): Word =
    eat(Token.COLON)
    val tpt = simpleType()
    TypeAscribe(id, tpt)(id.span | tpt.span)

  def pattern(): Word =
    val indent = peekItem().indent

    val words = new mutable.ArrayBuffer[Word]
    words += simplePattern()
    var item = peekItem()
    while isSimplePatternStart(item.token) && !indent.isUnindent(item.indent) do
      words += simplePattern()
      item = peekItem()

    if words.size == 1 then words(0)
    else Expr(words.toList)(words.head.span | words.last.span)

  def isSimplePatternStart(token: Token): Boolean =
    token == Token.TAG || token.isInstanceOf[Token.Ident] || token == Token.LPAREN

  def simplePattern(): Word =
    val item = peekItem()

    item.token match
      case Token.TAG =>
        val tagSign = next()
        val id = ident()
        val tag = Tag(id)(tagSign.span | id.span)

        val item = peekItem()
        if item.token == Token.LPAREN && item.span.followsImmediate(id.span) then
          applyPattern(tag)
        else
          tag

      case Token.Ident(name) =>
        val item = next()
        val id = Ident(name)(item.span)

        val itemNext = peekItem()
        itemNext.token match
          case Token.COLON => typePattern(id)

          case Token.LPAREN if itemNext.span.followsImmediate(id.span)  =>
            applyPattern(id)

          case Token.Ident("@") =>
            next()
            val nested = simplePattern()
            Assign(id, nested)(nested.span | id.span)

          case _ => id

      case Token.IntLit(value) =>
        next()
        IntLit(value)(item.span)

      case Token.BoolLit(value) =>
        next()
        BoolLit(value)(item.span)

      case Token.CharLit(value) =>
        next()
        CharLit(value)(item.span)

      case Token.StringLit(value) =>
        next()
        StringLit(value)(item.span)

      case Token.LPAREN =>
        next()
        val pat = pattern()
        eat(Token.RPAREN)
        pat

      case _ =>
        val item = next()
        error("Expect a pattern, found = " + item.token, item.span.toPos)
        throw new SyntaxError
