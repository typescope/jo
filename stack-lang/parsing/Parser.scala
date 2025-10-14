/************************************************************************
 *                                                                      *
 * The parser for the stack-oriented language.                          *
 *                                                                      *
 ************************************************************************/
package parsing

import ast.Trees.*
import ast.Naming
import ast.Positions
import ast.Positions.*

import reporting.Reporter
import reporting.Reporter.{ error, warn }
import reporting.Config

import common.IO
import common.StringUtil

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
    given Reporter = Reporter.createReporter()
    val options = Config.reportTime :: Config.fatalWarnings :: Nil
    val (config, sources) = cli.OptionParser.parseConfig(args, options)
    given Config = config

    Reporter.monitor():

      val nss = Parser.parse(sources)
      for ns <- nss do
        println(ns.source + ":")
        println(ns.show)
        println

  def parse(sourceFiles: List[String])(using Reporter): List[Namespace] = {
    for file <- sourceFiles.sorted yield
      Parser.parse(file)  <| file
  } <| "parsing"

  /** Parse the supplied code */
  def parse(path: String)(using rp: Reporter): Namespace = try
    val source = Reporter.source(path)
    val defaultModuleName = StringUtil.toPascalCase(IO.fileNameNoExt(path))
    val parser = new Parser(source.content)(using rp, source)
    parser.parse(defaultModuleName)
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

    /** For multi-line string parsing - bypasses lookahead buffer */
    def nextString(quoteCount: Int): TokenInfo =
      assert(peekedTokens.isEmpty, "peekedTokens must be empty when calling nextString")
      scanner.nextString(quoteCount)
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

  /** Parse a multi-line string starting with StringStart(n) where n >= 3
    * Collects StringLine tokens, handles indentation stripping and line continuation
    */
  def multiLineString(openMarker: TokenInfo): String =
    val Token.StringStart(quoteCount) = openMarker.token: @unchecked

    // Multi-line string
    val lines = mutable.ListBuffer[(String, Span)]()
    var baseIndent: Int = 0
    var done = false

    // Collect all lines until closing marker
    while !done do
      val nextItem = scanner.nextString(quoteCount)
      nextItem.token match
        case Token.StringLine(content) =>
          lines += content -> nextItem.span

        case Token.StringEnd =>
          // Found closing marker - determine indentation from last line
          baseIndent = nextItem.indent.tokenOffset
          done = true

        case other =>
          error(s"Unexpected token in multi-line string: $other", openMarker.span.toPos)
          return ""

    // Strip indentation and handle line continuation
    val result = new StringBuilder
    var i = 0
    var previousLineContinuation = false

    while i < lines.length do
      val (line, span) = lines(i)

      val indent = line.prefixLength(c => c == ' ' || c == '\t')

      if indent < baseIndent && line.trim.nonEmpty then
        // Calculate the position of this line for error reporting
        error(s"Line has insufficient indentation (expected at least $baseIndent spaces)", span.toPos)

      // Strip base indentation
      var stripped = if line.length >= baseIndent then line.substring(baseIndent) else line

      // If previous line had line continuation, trim leading whitespace from this line
      if previousLineContinuation then
        stripped = stripped.dropWhile(c => c == ' ' || c == '\t')

      // Check for line continuation
      if stripped.endsWith("\\") then
        previousLineContinuation = true
        // Remove backslash and trim next line's leading whitespace
        val withoutBackslash = stripped.dropRight(1)
        result ++= withoutBackslash
      else
        result ++= stripped
        if i < lines.length - 1 then result += '\n'

      i += 1

    try
      StringUtil.unescape(result.toString)
    catch
      case e: StringUtil.EscapeError =>
        error(e.message, openMarker.span.toPos)
        ""

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
      && indent.isSameIndent(peekedItem.indent)
    then
      eat(Token.END)

  /** Eat optional comma */
  def eatCommaOpt() =
    if peek() == Token.COMMA then next()

  def checkAlign(reference: TokenInfo, item: TokenInfo, allowSameLine: Boolean = false): Unit =
    val refIndent = reference.indent
    val itemIndent = item.indent

    if
      !refIndent.isAligned(itemIndent)
      && !(allowSameLine && refIndent.isSameLine(itemIndent))
    then
      val diagnosis = s"expect offset = ${refIndent.tokenOffset}, found = ${itemIndent.tokenOffset}"
      warn(s"${item.token} is not aligned with ${reference.token}, $diagnosis", item.span.toPos)

  /** The caller must consume at least one token if syntax error is thrown */
  def repeated[T](parseItem: => Option[T]): List[T] =
    val items = new mutable.ArrayBuffer[T]
    var continue = peek() != Token.EOF
    while continue do
      val firstToken = peekItem()
      try
        parseItem match
          case Some(item) =>
            items += item
            continue = peek() != Token.EOF

          case None =>
            continue = false

      catch case ex: SyntaxError =>
        skipIndented(firstToken.indent)
        None
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

  def parse(defaultModuleName: String): Namespace =
    val nspace = namespace(defaultModuleName)
    // With parsing errors, ensure finish scanning
    skipUntil(Set(Token.EOF))
    nspace

  def namespace(defaultModuleName: String): Namespace =
    val item = peek()
    val id =
      item match
        case Token.NSPACE =>
          next()
          qualid()

        case _ =>
          Ident(defaultModuleName)(Span(0, 0))

    val imports = repeated:
      if peek() == Token.IMPORT then Some(importStat())
      else None

    val defs = repeated:
      if peek() == Token.EOF then None
      else Some(parseTopLevelDef())

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

  def parseTopLevelDef(): Def =
    val mods = modifiers()
    val item = peekItem()

    if item.token == Token.TYPE then typeDef(mods)
    else if item.token == Token.DEF then funDef(mods)
    else if item.token == Token.PARAM then paramDef(mods)
    else if item.token == Token.PATTERN then patDef(mods)
    else if item.token == Token.DATA then dataDef(mods)
    else if item.token == Token.ALIAS then aliasDef(mods)
    else if item.token == Token.SECTION then section(mods)
    else if item.token == Token.CLASS then classDef(mods)
    else
      error("Expect a definition, found = " + item.token, item.span.toPos)
      next()
      throw new SyntaxError

  def aliasDef(mods: List[Modifier]): AliasDef =
    val info = eat(Token.ALIAS)
    val item = next()
    val kind =
      item.token match
        case Token.DEF     => AliasKind.Def
        case Token.PARAM   => AliasKind.Param
        case Token.PATTERN => AliasKind.Pattern
        case _ =>
          error("Expect def/param/pattern, found = " + item.token, item.span.toPos)
          throw new SyntaxError
      end match

    val name = ident()
    eat(Token.EQL)
    val id = qualid()
    AliasDef(name, kind, id)(info.span | id.span).withMods(mods)

  def section(mods: List[Modifier]): Section =
    val secToken = eat(Token.SECTION)
    val id = ident()
    val defs = repeated:
      val item = peekItem()
      if secToken.isUnindent(item) then None
      else Some(parseTopLevelDef())

    eatEndOpt(secToken.indent)

    val endSpan = if defs.isEmpty then id.span else defs.last.span

    Section(id, defs)(id.span | endSpan).withMods(mods)

  def modifiers(): List[Modifier] =
    peek() match
      case Token.AUTO =>
        val item = next()
        Modifier.Auto()(item.span) :: modifiers()

      case Token.DEFER =>
        val item = next()
        Modifier.Defer()(item.span) :: modifiers()

      case _ =>
        Nil

  def valDef(modifier: Token, mods: List[Modifier]): ValDef =
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
    ValDef(id, tpt, rhs, mutable)(mod.span | rhs.span).withMods(mods)

  def funDef(mods: List[Modifier]): FunDef =
    val fun = eat(Token.DEF)
    val preParamList = paramSection()
    val id = ident()
    val tparams = typeParams()
    val postParamList = paramSection()
    val autos = autoSection()

    val resType =
      if peek() == Token.COLON then
        eat(Token.COLON)
        typ()
      else
        EmptyTypeTree()(id.span)

    val receiveParams = optReceiveParams()

    val body =
      val deferred = mods.exists(_.isInstanceOf[Modifier.Defer])
      if deferred && peek() != Token.EQL then
        Block(Nil)(id.span)
      else
        eat(Token.EQL)
        block(fun.indent)

    eatEndOpt(fun.indent)

    val paramList = preParamList ++ postParamList
    FunDef(id, tparams, paramList, autos, resType, receiveParams, body, preParamList.size)(fun.span | body.span).withMods(mods)

  def defDef(needBody: Boolean): FunDef =
    val defToken = eat(Token.DEF)
    val id = ident()
    val tparams = typeParams()
    val paramList = paramSection()
    val autos = autoSection()
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
    FunDef(id, tparams, paramList, autos, resType, receiveParams, body, preParamCount)(defToken.span | body.span)

  def paramDef(mods: List[Modifier]): ParamDef =
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
    ParamDef(id, tpt, default)(token.span | tpt.span).withMods(mods)

  def patDef(mods: List[Modifier]): PatDef =
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
       error("Expect cases, found nothing before the unindentation", item.span.toPos)
       throw new SyntaxError

    val cases: List[Case] =
      if item.token != Token.CASE then
        error("expect CASE, found = " + item.token, item.span.toPos)
        skipIndented(pat.indent)
        Nil
      else
        var count = 0
        repeated:
          if peek() == Token.CASE then
            val caseToken = next()
            val pat = pattern()
            val caseDef = Case(pat, Block(Nil)(pat.span))(caseToken.span | pat.span)

            if count > 0 then checkAlign(item, caseToken)

            count += 1
            Some(caseDef)

          else
            None
      end if

    val bodySpan = if cases.isEmpty then resType.span else cases.last.span

    eatEndOpt(pat.indent)

    val paramList = preParamList ++ postParamList
    PatDef(id, tparams, paramList, resType, cases, preParamList.size)(pat.span | bodySpan).withMods(mods)

  def paramSection(): List[Param] =
    if peek() == Token.LPAREN && peek(1) != Token.AUTO then params() else Nil

  def autoSection(): List[Param] =
    if peek() == Token.LPAREN then
      next()
      eat(Token.AUTO)
      val list = paramsRest(mutable.ArrayBuffer(param(typeOptional = false)), typeOptional = false)
      eat(Token.RPAREN)
      list

    else
      Nil

  def classDef(mods: List[Modifier]): ClassDef =
    val klass = eat(Token.CLASS)
    val id = ident()
    val tparams = typeParams()

    val members: List[ValDef | FunDef] = repeated:
      val item = peekItem()
      if klass.indent.isUnindent(item.indent) then
        None

      else if item.token == Token.DEF then
        Some(defDef(needBody = true))

      else if peek() == Token.VAL || peek() == Token.VAR then
        val mod = next()
        val mutable = mod.token == Token.VAR
        val id = ident()
        eat(Token.COLON)
        val tpt = typ()
        val body = Block(phrases = Nil)(id.span)
        Some(ValDef(id, tpt, body, mutable)(mod.span | tpt.span))

      else None

    eatEndOpt(klass.indent)

    val lastSpan =
      if members.nonEmpty then members.last.span
      else if tparams.nonEmpty then tparams.last.span
      else id.span

    ClassDef(id, tparams, members)(klass.span | lastSpan).withMods(mods)

  def typeDef(mods: List[Modifier]): TypeDef =
    val typeItem = eat(Token.TYPE)
    val preTypeParams = typeParams()
    val id = ident()
    val postTypeParams = typeParams()
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
    val tparams = preTypeParams ++ postTypeParams
    TypeDef(id, tparams, rhs, isBound, preTypeParams.size)(typeItem.span | rhs.span).withMods(mods)

  def dataDef(mods: List[Modifier]): Def =
    val data = eat(Token.DATA)
    val id = ident()
    val tparams = typeParams()

    if peek() == Token.EQL then
      next()

      def branch(): TagType =
        val id = ident()
        val paramList = paramSection()
        val endSpan = if paramList.isEmpty then id.span else paramList.last.span
        TagType(id, paramList)(id.span | endSpan)

      val branches = oneOrMore(branch, Token.Ident("|"))
      EnumDef(id, tparams, branches)(data.span | branches.last.span).withMods(mods)

    else
      val paramList = paramSection()
      DataDef(id, tparams, paramList)(data.span | id.span).withMods(mods)

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

    if peek() == Token.SUBTYPE then
      val sub = eat(Token.SUBTYPE)
      val tpt = typ()
      error("Type bounds are not supported", (sub.span | tpt.span).toPos)

    val bound = EmptyTypeTree()(id.span)
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
            if phrases.nonEmpty then checkAlign(refToken, item)
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
    val tpt = simpleType()
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

    def isBinaryOperator(item: TokenInfo): Boolean =
      item.token match
        case Token.Ident(name) => Naming.isBinaryOperator(name)
        case _ => false

    if item.token == Token.EOF || lineIndent.isOutdent(item.indent) then
      finalResult

    else if item.indent.isFirstOfLine then
      if isBinaryOperator(item) then
        // continue if the next line is an operator
        word() match
          case Some(w) =>
            val res = exprRest(words += w, item.indent)

            // Check no more nested lines
            val nextItem = peekItem()
            if lineIndent.isIndent(nextItem.indent) then
              error("Unexpected indented line", nextItem.span.toPos)
              throw new SyntaxError

            else if !lineIndent.isOutdent(nextItem.indent) && isBinaryOperator(nextItem) then
              error("Unaligned operator", nextItem.span.toPos)
              throw new SyntaxError

            else
              res

          case None =>
            error("A word expected, found = " + item.token, item.span.toPos)
            throw new SyntaxError

      else if lineIndent.isIndent(item.indent) then
        val Block(phrases) = block(lineIndent)
        words ++= phrases
        finalResult

      else
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

        case Token.LBRACKET if item.span.followsImmediate(word.span) =>
          optSelectAndApply(bracketApply(word))

        case Token.LPAREN if item.span.followsImmediate(word.span) =>
          optSelectAndApply(apply(word))

        case _ => Some(word)

    item.token match
      case Token.LBRACE =>
        peek(1) match
          case Token.VAL | Token.VAR | Token.DEF =>
            optSelectAndApply(objectLit())

          case _ =>
            optSelectAndApply(record())

      case Token.LBRACKET => optSelectAndApply(list())

      case Token.TAG    =>
        val tok = next()
        val id = ident()
        val tag = Tag(id)(tok.span | id.span)
        optSelectAndApply(tag)

      case Token.LPAREN =>
        if isLambda() then Some(lambda()) else optSelectAndApply(fence())

      case _: Token.Ident =>
        val id = ident()
        if peek() == Token.RARROW then
          val arrow = eat(Token.RARROW)
          val body = block(arrow.indent)
          val params = Param(id, EmptyTypeTree()(id.span))(id.span) :: Nil
          Some(Lambda(params, body)(id.span | body.span))
        else
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

      case Token.StringStart(_) =>
        next()
        val value = multiLineString(item)
        optSelectAndApply(StringLit(value)(item.span))

      case Token.NEW =>
        optSelectAndApply(newExpr())

      case Token.BEGIN =>
        next()
        val blk = block(item.indent)
        eatEndOpt(item.indent)
        // No selection or type/term apply on do-block
        Some(blk)

      case token =>
        None

  def phrase(): Option[Word] =
    val item = peekItem()
    item.token match
      case Token.IF        => Some(ifElse())
      case Token.MATCH     => Some(patmat())
      case Token.WHILE     => Some(whileDo())

      case Token.VAL | Token.VAR  =>
        Some(valDef(item.token, mods = Nil))

      case Token.DEF =>
        Some(funDef(mods = Nil))

      case Token.PATTERN =>
        Some(patDef(mods = Nil))

      case Token.TYPE =>
        Some(typeDef(mods = Nil))

      case Token.AUTO | Token.DEFER =>
        val mods = modifiers()
        peek() match
          case Token.VAL | Token.VAR =>
            Some(valDef(item.token, mods))

          case Token.DEF =>
            Some(funDef(mods))

          case token =>
            error("Expect start of value or function definitions, found = " + token, peekItem().span.toPos)
            throw new SyntaxError

      case token =>
        word().map: w =>
          if (w.isInstanceOf[RefTree] || w.isInstanceOf[BracketApply]) && peek() == Token.EQL then
            assign(w, item.indent)

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
          val simpleTypes =
            tps.head :: repeated:
              simpleTypeOpt()

          if simpleTypes.size == 1 then simpleTypes.head
          else ExprType(simpleTypes)(simpleTypes.head.span | simpleTypes.last.span)

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
    simpleTypeOpt() match
      case Some(tpt) => tpt
      case None =>
        error("Expect a type, found = " + peek(), peekItem().span.toPos)
        throw new SyntaxError

  def simpleTypeOpt(): Option[TypeTree] =
    peek() match
      case Token.LBRACE   =>
        peek(1) match
          case Token.VAL | Token.VAR | Token.DEF =>
            Some(objectType())

          case _ =>
            Some(recordType())

      case Token.TAG      => Some(tagType())

      case Token.LPAREN   =>
        next()
        val tp = typ()
        eat(Token.RPAREN)
        Some(tp)

      case Token.RARROW   =>
        val arrow = next()
        val resType = typ()
        val params = optReceiveParams().getOrElse(Nil)
        val endSpan = if params.isEmpty then resType.span else params.last.span
        val funType = FunctionType(paramTypes = Nil, resType, params)(arrow.span | endSpan)
        Some(funType)

      case _: Token.Ident =>
        val id = qualid()
        if peek() == Token.LBRACKET then
          Some(appliedType(id))
        else
          Some(id)

      case _ =>
        None

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
    val startToken = eat(Token.LBRACE)
    var count = 0
    val decls: List[ValDef | FunDef] = repeated:
      if count > 0 then eatCommaOpt()

      if peek() == Token.DEF then
        count += 1
        val methodDecl = defDef(needBody = false)
        Some(methodDecl)

      else if peek() == Token.VAL || peek() == Token.VAR then
        count += 1
        val mod = next()
        val mutable = mod.token == Token.VAR
        val id = ident()
        eat(Token.COLON)
        val tpt = typ()
        val body = Block(phrases = Nil)(id.span)
        Some(ValDef(id, tpt, body, mutable)(mod.span | tpt.span))

      else None
    val endToken = eat(Token.RBRACE)
    ObjectType(decls)(startToken.span | endToken.span)

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
        p

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
      // outdent else belongs to outer if/else
      if nextItem.token == Token.ELSE && !ifItem.indent.isOutdent(nextItem.indent) then
        val elseItem = eat(Token.ELSE)
        // if cond then
        // else if cond then
        // else
        val tokenInfo = ifItem.copy(indent = ifItem.indent.lineStart)
        checkAlign(tokenInfo, nextItem, allowSameLine = true)
        val blk = block(nextItem.indent)
        eatEndOpt(elseItem.indent)
        blk
      else
        // TODO: change to {} causes crash for native backends
        val blk = Block(phrases = Nil)(thenp.span.endPoint)
        eatEndOpt(ifItem.indent)
        blk

    If(cond, thenp, elsep)(ifItem.span | elsep.span)

  def whileDo(): Word =
    val whileItem = eat(Token.WHILE)
    val cond = expr()
    val doItem = eat(Token.DO)
    val body = block(doItem.indent)

    eatEndOpt(whileItem.indent)

    While(cond, body)(whileItem.span | body.span)

  def assign(lhs: Word, limitIndent: Indent): Assign =
    eat(Token.EQL)
    val rhs = block(limitIndent)
    Assign(lhs, rhs)(lhs.span | rhs.span)

  def select(qual: Word): Select =
    eat(Token.DOT)
    val id = ident()
    val sel = Select(qual, id.name)(qual.span | id.span)
    peek() match
      case Token.DOT => select(sel)
      case _ => sel

  def bracketApply(fun: Word): Word =
    peek(1) match
      case Token.Ident(name) if Naming.isCapitalized(name) =>
        eat(Token.LBRACKET)
        val targs = oneOrMore(() => typ(), Token.COMMA)
        val endToken = eat(Token.RBRACKET)
        TypeApply(fun, targs)(fun.span | endToken.span)

      case _ =>
        eat(Token.LBRACKET)
        val args = oneOrMore(() => expr(), Token.COMMA)
        val endToken = eat(Token.RBRACKET)
        BracketApply(fun, args)(fun.span | endToken.span)

  def apply(fun: Word): Apply =
    val (args, span) = termArgs()
    Apply(fun, args)(fun.span | span)

  def newExpr(): New =
    val startItem = eat(Token.NEW)
    val ref = qualid()
    val targs =
      if peek() == Token.LBRACKET then typeArgs()._1
      else Nil

    val (args, span) =
      if peek() == Token.LPAREN then termArgs()
      else (Nil, ref.span)

    New(ref, targs, args)(startItem.span | span)

  def list(): ListLit =
    val lbrace = eat(Token.LBRACKET)
    val args =
      if peek() == Token.RBRACKET then Nil
      else oneOrMore(() => expr(), Token.COMMA)

    val rbrace = eat(Token.RBRACKET)
    ListLit(args)(lbrace.span | rbrace.span)

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
    eat(Token.COLON)
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
    val startToken = eat(Token.LBRACE)

    var count = 0
    val members: List[ValDef | FunDef] = repeated:
      if count > 0 then eatCommaOpt()

      val res =
        if peek() == Token.DEF then
          Some(defDef(needBody = true))

        else if peek() == Token.VAL then
          Some(valDef(Token.VAL, mods = Nil))

        else if peek() == Token.VAR then
          Some(valDef(Token.VAR, mods = Nil))

        else None

      if res.nonEmpty then count += 1

      res

    val endToken = eat(Token.RBRACE)
    Object(members)(startToken.span | endToken.span)

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

  def applyPattern(apply: Tag | RefTree): Word =
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

  def exprPattern(): Word =
    val indent = peekItem().indent

    val words = new mutable.ArrayBuffer[Word]
    words += simplePattern()
    var item = peekItem()
    while isSimplePatternStart(item.token) && !indent.isUnindent(item.indent) do
      words += simplePattern()
      item = peekItem()

    if words.size == 1 then words(0)
    else Expr(words.toList)(words.head.span | words.last.span)

  def pattern(): Word =
    val exprPat = exprPattern()

    val guard =
      if peek() == Token.IF then
        next()
        val cond = expr()
        If(cond, exprPat, Block(Nil)(cond.span))(exprPat.span | cond.span)
      else
        exprPat

    if peek() == Token.THEN then
      def binding(): WithArg =
        val id = ident()
        eat(Token.EQL)
        val rhs = expr()
        WithArg(id, rhs)(id.span | rhs.span)

      next()
      val args = oneOrMore(binding, Token.COMMA)
      With(guard, args)(guard.span | args.last.span)

    else
      guard

  def isSimplePatternStart(token: Token): Boolean =
    token == Token.TAG
    || token == Token.LPAREN
    || token == Token.LBRACKET
    || token.isInstanceOf[Token.Ident]
    || token.isInstanceOf[Token.BoolLit]
    || token.isInstanceOf[Token.StringLit]
    || token.isInstanceOf[Token.StringStart]
    || token.isInstanceOf[Token.CharLit]
    || token.isInstanceOf[Token.IntLit]

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
        val id = qualid()

        val itemNext = peekItem()
        itemNext.token match
          case Token.COLON if id.isInstanceOf[Ident] =>
            typePattern(id.asInstanceOf[Ident])

          case Token.LPAREN if itemNext.span.followsImmediate(id.span)  =>
            applyPattern(id)

          case Token.Ident("@") if id.isInstanceOf[Ident] =>
            next()
            val nested = simplePattern()
            Assign(id.asInstanceOf[Ident], nested)(nested.span | id.span)

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

      case _: Token.StringStart =>
        next()
        val value = multiLineString(item)
        StringLit(value)(item.span)

      case Token.LPAREN =>
        next()
        val pat = pattern()
        eat(Token.RPAREN)
        pat

      case Token.LBRACKET =>
        val lbracket = next()
        val pats =
          if peek() == Token.RBRACKET then Nil
          else oneOrMore(exprPattern, Token.COMMA)
        val rbracket = eat(Token.RBRACKET)
        ListLit(pats)(lbracket.span | rbracket.span)

      case _ =>
        val item = next()
        error("Expect a pattern, found = " + item.token, item.span.toPos)
        throw new SyntaxError
