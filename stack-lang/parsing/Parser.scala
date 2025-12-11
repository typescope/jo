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

    /** For string parsing - bypasses lookahead buffer */
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

  /** Parse a string starting with StringStart(n)
    *
    * For single-line (n == 1): collects content until StringEnd, handles escaping
    * For multi-line (n >= 3): collects StringLine tokens, handles indentation stripping
    */
  def parseString(openMarker: TokenInfo): Word =
    val Token.StringStart(quoteCount) = openMarker.token: @unchecked

    // ArrayBuffer to collect parts with indentation info: (part, indent, span)
    val partsBuffer = mutable.ArrayBuffer[(Word, Indent)]()
    var done = false
    var resultSpan = openMarker.span

    // Collect all parts until closing marker
    while !done do
      val nextItem = scanner.nextString(quoteCount)
      nextItem.token match
        case Token.StringLine(content) =>
          // Check if single-line string is unclosed (empty content means hit newline immediately)
          if quoteCount == 1 && content.isEmpty then
            error("Unclosed string literal", openMarker.span.toPos)
            // Stop parsing this string - treat as unclosed
            return StringLit("")(openMarker.span | nextItem.span)

          // Check if single-line string spans multiple lines
          if quoteCount == 1 && !nextItem.indent.isSameLine(openMarker.indent) then
            error("Single-line string cannot span multiple lines", nextItem.span.toPos)

          partsBuffer += ((StringLit(content)(nextItem.span), nextItem.indent))
          resultSpan = resultSpan | nextItem.span

        case Token.InterpolationStart =>
          // Parse interpolation expression
          val exprStartIndent = nextItem.indent
          val exprStartSpan = nextItem.span
          val interpolatedExpr = expr()
          val rbrace = eat(Token.RBRACE)
          val exprSpan = exprStartSpan | rbrace.span

          if !interpolatedExpr.pos.isOneLine then
            // Validate interpolation is single-line (doesn't span multiple lines)
            error(s"Interpolation should only span one line", exprSpan.toPos)

          partsBuffer += Block(interpolatedExpr :: Nil)(exprSpan) -> exprStartIndent
          resultSpan = resultSpan | exprSpan

        case Token.StringEnd =>
          done = true
          resultSpan = resultSpan | nextItem.span

          // Process parts based on string type
          if quoteCount == 1 then
            // Single-line string: unescape literals, validate interpolations
            return buildString(partsBuffer.toSeq, quoteCount, resultSpan, baseIndent = 0)
          else
            // Multi-line string: determine base indentation and process
            val baseIndent = nextItem.indent.tokenOffset
            return buildString(partsBuffer.toSeq, quoteCount, resultSpan, baseIndent)

        case Token.EOF =>
          error("Unclosed string literal", openMarker.span.toPos)
          return StringLit("")(openMarker.span)

        case other =>
          error(s"Unexpected token in string: $other", openMarker.span.toPos)
          return StringLit("")(openMarker.span)
    end while

    // Should not reach here
    StringLit("")(openMarker.span)
  end parseString

  private def buildString(partsWithIndent: Seq[(Word, Indent)], quoteCount: Int, resultSpan: Span, baseIndent: Int): Word =
    val escapePolicy =
      if quoteCount == 1 then
        StringUtil.EscapePolicy.Disable("") // All escapes
      else
        StringUtil.EscapePolicy.Enable("u") // Only unicode escapes

    val processedParts = mutable.ArrayBuffer[Word]()

    for ((part, indent), idx) <- partsWithIndent.zipWithIndex do
      // insert newline for multiline string
      if quoteCount > 2 && idx > 0 && !indent.isSameLine(partsWithIndent(idx - 1)._2) then
        processedParts += StringLit("\n")(Span(part.span.start - 1, 0))

      part match
        case StringLit(content) =>
          val isLineStart = idx < 1 || !indent.isSameLine(partsWithIndent(idx - 1)._2)

          // For multiline, strip base indentation from content that starts with it
          val strippedContent =
            if quoteCount > 1 && isLineStart then
              val lineIndent = content.prefixLength(c => c == ' ' || c == '\t')

              if lineIndent < baseIndent && content.trim.nonEmpty then
                error(s"Line has insufficient indentation (expected at least $baseIndent spaces)", part.pos)

              if content.length >= baseIndent then content.substring(baseIndent) else content
            else
              content

          // Unescape the content
          try
            val unescaped = StringUtil.unescape(strippedContent, escapePolicy)
            processedParts += StringLit(unescaped)(part.span)
          catch
            case e: StringUtil.EscapeError =>
              val errorStart =
                if quoteCount > 1 && indent.isFirstOfLine then
                  part.span.start + e.offset + baseIndent
                else
                  part.span.start + e.offset

              val errorSpan = Span(errorStart, e.length)
              error(e.message, errorSpan.toPos)
              processedParts += StringLit("")(part.span)

        case expr =>
          // For multiline, check indentation if it's first of line
          if quoteCount > 1 && indent.isFirstOfLine then
            if indent.tokenOffset < baseIndent then
              error(s"Interpolation has insufficient indentation (expected at least $baseIndent spaces)", expr.pos)

          processedParts += expr
      end match
    end for

    processedParts.toList match
      case StringLit(content) :: Nil =>
        // update the span
        StringLit(content)(resultSpan)

      case parts =>
        InterpolatedString(parts)(resultSpan)
  end buildString

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
    else if item.token == Token.INTERFACE then interfaceDef(mods)
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
      case Token.DEFER =>
        val item = next()
        Modifier.Defer()(item.span) :: modifiers()

      case Token.PRIVATE =>
        val item = next()
        if peek() == Token.LBRACKET then
          next()
          val id = ident()
          val endItem = eat(Token.RBRACKET)
          Modifier.Private(Some(id))(item.span | endItem.span) :: modifiers()

        else
          Modifier.Private(None)(item.span) :: modifiers()

      case _ =>
        Nil

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

  def defDef(needBody: Boolean, bodyAllowed: Boolean): FunDef =
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
      val token = peek()
      if needBody then
        if token == Token.EQL then
          next()
          block(defToken.indent)
        else
          error("Expect EQL, found = " + token, peekItem().span.toPos)
          Block(Nil)(resType.span)
      else
        // For interface methods and object type declarations, body is optional
        if token == Token.EQL then
          if bodyAllowed then
            next()
            block(defToken.indent)
          else
            error("No body expected for declaration", peekItem().span.toPos)
            next()
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

  def autoSection(): List[Auto] =
    if peek() == Token.LPAREN then
      next()
      eat(Token.AUTO)
      val list = autosRest(mutable.ArrayBuffer(auto()), typeOptional = false)
      eat(Token.RPAREN)
      list
    else
      Nil

  def auto(): Auto =
    val id = ident()
    eat(Token.COLON)
    val tpt = typ()

    val candidates =
      if peek() == Token.WITH then
        next()
        candidateList()
      else
        Nil

    val finalSpan = if candidates.isEmpty then id.span | tpt.span else id.span | candidates.last.span
    Auto(id, tpt, candidates)(finalSpan)

  def autosRest(acc: mutable.ArrayBuffer[Auto], typeOptional: Boolean): List[Auto] =
    val token = peek()
    if token == Token.RPAREN || token == Token.EOF then acc.toList
    else
      eat(Token.COMMA)
      autosRest(acc += auto(), typeOptional)

  def classDef(mods: List[Modifier]): ClassDef =
    val klass = eat(Token.CLASS)
    val id = ident()
    val tparams = typeParams()

    // Parse constructor parameters if present (simplified syntax)
    val classParams =
      if peek() == Token.LPAREN then params()
      else Nil

    // Parse view declarations and members
    val views = mutable.ArrayBuffer[ViewDecl]()
    val vals = mutable.ArrayBuffer[ValDef]()
    val funs = mutable.ArrayBuffer[FunDef]()

    repeated:
      val item = peekItem()
      if klass.indent.isUnindent(item.indent) then
        None
      else if item.token == Token.VIEW then
        Some(views += viewDecl())
      else
        val mods = modifiers()
        val item = peekItem()

        if item.token == Token.DEF then
          Some(funs += defDef(needBody = true, bodyAllowed = true).withMods(mods))

        else if peek() == Token.VAL || peek() == Token.VAR then
          val mod = next()
          val mutable = mod.token == Token.VAR
          val id = ident()
          eat(Token.COLON)
          val tpt = typ()
          val (body, endSpan) =
            if peek() == Token.EQL then
              eat(Token.EQL)
              val rhs = block(mod.indent)
              (rhs, rhs.span)
            else
              val emptyBlock = Block(phrases = Nil)(id.span)
              (emptyBlock, tpt.span)
          Some(vals += ValDef(id, tpt, body, mutable)(mod.span | endSpan).withMods(mods))

        else None

    eatEndOpt(klass.indent)

    val lastSpan =
      if funs.nonEmpty then funs.last.span
      else if vals.nonEmpty then vals.last.span
      else if views.nonEmpty then views.last.span
      else if classParams.nonEmpty then classParams.last.span
      else if tparams.nonEmpty then tparams.last.span
      else id.span

    ClassDef(id, tparams, classParams, views.toList, vals.toList, funs.toList)(klass.span | lastSpan).withMods(mods)

  def interfaceDef(mods: List[Modifier]): InterfaceDef =
    val interface = eat(Token.INTERFACE)
    val id = ident()
    val tparams = typeParams()

    val members: List[FunDef] = repeated:
      val item = peekItem()
      if interface.indent.isUnindent(item.indent) then
        None
      else if item.token == Token.DEF then
        val mods = modifiers()
        // Interface methods can have bodies (default implementations) or no bodies
        Some(defDef(needBody = false, bodyAllowed = true).withMods(mods))
      else
        error("Expect method definition in interface, found = " + item.token, item.span.toPos)
        next()
        None

    eatEndOpt(interface.indent)

    val lastSpan =
      if members.nonEmpty then members.last.span
      else if tparams.nonEmpty then tparams.last.span
      else id.span

    InterfaceDef(id, tparams, members)(interface.span | lastSpan).withMods(mods)

  def viewDecl(): ViewDecl =
    val viewToken = eat(Token.VIEW)
    val tpt = typ()
    val rhs =
      if peek() == Token.EQL then
        eat(Token.EQL)
        Some(expr())
      else
        None

    val finalSpan = rhs.map(r => viewToken.span | r.span).getOrElse(viewToken.span | tpt.span)
    ViewDecl(tpt, rhs)(finalSpan)

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

      def branch(): DataDef =
        val id = ident()
        val paramList = paramSection()
        val endSpan = if paramList.isEmpty then id.span else paramList.last.span
        DataDef(id, Nil, paramList)(id.span | endSpan)

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

    val adapters =
      if peek() == Token.WITH then
        eat(Token.WITH)
        adapterList()
      else
        Nil

    val finalSpan = if adapters.isEmpty then id.span | tpt.span else id.span | adapters.last.span
    Param(id, tpt, adapters)(finalSpan)

  def paramsRest(acc: mutable.ArrayBuffer[Param], typeOptional: Boolean): List[Param] =
    val token = peek()
    if token == Token.RPAREN || token == Token.EOF then acc.toList
    else
      eat(Token.COMMA)
      paramsRest(acc += param(typeOptional), typeOptional)

  /** Parse adapter list: [adapter1, adapter2, ...]
    * Adapters can be qualified identifiers (function adapters) or .member (member adapters)
    */
  def adapterList(): List[ParamAdapter] =
    eat(Token.LBRACKET)
    val adapters = mutable.ArrayBuffer[ParamAdapter]()

    // Parse first adapter
    if peek() == Token.DOT then
      val dotItem = eat(Token.DOT)
      val memberName = ident()
      val span1 = dotItem.span | memberName.span
      adapters += ParamAdapter.Member(memberName.name)(span1)
    else
      val ref = qualid()
      adapters += ParamAdapter.Function(ref)(ref.span)

    // Parse remaining adapters
    while peek() == Token.COMMA do
      eat(Token.COMMA)
      if peek() == Token.DOT then
        val dotItem = eat(Token.DOT)
        val memberName = ident()
        val span = dotItem.span | memberName.span
        adapters += ParamAdapter.Member(memberName.name)(span)
      else
        val ref = qualid()
        adapters += ParamAdapter.Function(ref)(ref.span)

    eat(Token.RBRACKET)
    adapters.toList

  /** Parse candidate list for auto parameters: [candidate1, candidate2, ...]
    * Candidates can be qualified identifiers (value candidates) or [Type].member (member candidates)
    */
  def candidateList(): List[AutoCandidate] =
    eat(Token.LBRACKET)
    val candidates = mutable.ArrayBuffer[AutoCandidate]()

    // Parse first candidate
    if peek() == Token.LBRACKET then
      // Member candidate: [Type].member
      val lbracket = eat(Token.LBRACKET)
      val tpe = typ()
      eat(Token.RBRACKET)
      eat(Token.DOT)
      val memberName = ident()
      val span1 = lbracket.span | memberName.span
      candidates += AutoCandidate.Member(tpe, memberName.name)(span1)
    else
      // Value candidate: qualid
      val ref = qualid()
      candidates += AutoCandidate.Value(ref)(ref.span)

    // Parse remaining candidates
    while peek() == Token.COMMA do
      eat(Token.COMMA)
      if peek() == Token.LBRACKET then
        // Member candidate: [Type].member
        val lbracket = eat(Token.LBRACKET)
        val tpe = typ()
        eat(Token.RBRACKET)
        eat(Token.DOT)
        val memberName = ident()
        val span = lbracket.span | memberName.span
        candidates += AutoCandidate.Member(tpe, memberName.name)(span)
      else
        // Value candidate: qualid
        val ref = qualid()
        candidates += AutoCandidate.Value(ref)(ref.span)

    eat(Token.RBRACKET)
    candidates.toList

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

  def havingBinding(): HavingBinding =
    val tpe = typ()
    eat(Token.EQL)
    val value = expr()
    HavingBinding(tpe, value)(tpe.span | value.span)

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
    token0 == Token.LPAREN && {
      val token1 = peek(1)
      lazy val token2 = peek(2)
      lazy val token3 = peek(3)

      token1.isInstanceOf[Token.Ident] && (token2 == Token.COLON || token2 == Token.COMMA)
      || token1 == Token.RPAREN && token2 == Token.RARROW
      || token1.isInstanceOf[Token.Ident] && token2 == Token.RPAREN && token3 == Token.RARROW
    }

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

      case Token.LPAREN =>
        if isLambda() then Some(lambda()) else optSelectAndApply(fence())

      case _: Token.Ident =>
        val id = ident()
        if peek() == Token.RARROW then
          val arrow = eat(Token.RARROW)
          val body = block(arrow.indent)
          val params = Param(id, EmptyTypeTree()(id.span), Nil)(id.span) :: Nil
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

      case Token.StringStart(_) =>
        next()
        val lit = parseString(item)
        optSelectAndApply(lit)

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
    val token = item.token
    token match
      case Token.IF        => Some(ifElse())
      case Token.MATCH     => Some(patmat())
      case Token.WHILE     => Some(whileDo())

      case Token.VAL | Token.VAR  =>
        Some(valDef(item.token))

      case Token.DEF =>
        Some(funDef(mods = Nil))

      case Token.PATTERN =>
        Some(patDef(mods = Nil))

      case Token.TYPE =>
        Some(typeDef(mods = Nil))

      case Token.DEFER | Token.PRIVATE =>
        error("Cannot use " + token + " for local definitions", item.span.toPos)
        next()
        phrase()

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

      case Token.LIKE =>
        val likeToken = next()
        val targetType = simpleType()
        eat(Token.WITH)
        val adapters = adapterList()
        val endSpan = if adapters.isEmpty then targetType.span else adapters.last.span
        Some(DuckType(targetType, adapters)(likeToken.span | endSpan))

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

  def fields(acc: mutable.ArrayBuffer[Param]): List[Param] =
    peek() match
      case Token.RBRACE | Token.EOF => acc.toList
      case _ =>
        if acc.nonEmpty then eatCommaOpt()
        val id = ident()
        eat(Token.COLON)
        val tp = typ()
        val field = Param(id, tp, Nil)(id.span | tp.span)
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
        val methodDecl = defDef(needBody = false, bodyAllowed = false)
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
    val havingBindings =
      if peek() == Token.HAVING then
        eat(Token.HAVING)
        oneOrMore(havingBinding, Token.COMMA)
      else
        Nil
    val finalSpan = if havingBindings.isEmpty then fun.span | span else fun.span | havingBindings.last.span
    Apply(fun, args, havingBindings)(finalSpan)

  def newExpr(): New =
    val startItem = eat(Token.NEW)
    val ref = qualid()
    val (targs, targsSpan) =
      if peek() == Token.LBRACKET then typeArgs()
      else (Nil, ref.span.endPoint)

    val (args, span) =
      if peek() == Token.LPAREN then termArgs()
      else (Nil, ref.span)

    if targs.isEmpty then
      New(ref, args)(startItem.span | span)

    else
      val tpt = AppliedType(ref, targs)(ref.span | targsSpan)
      New(tpt, args)(startItem.span | span)

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
          Some(defDef(needBody = true, bodyAllowed = true))

        else if peek() == Token.VAL then
          Some(valDef(Token.VAL))

        else if peek() == Token.VAR then
          Some(valDef(Token.VAR))

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

  def applyPattern(apply: RefTree): Word =
    val bindings = patternArgs()
    val spanEnd = bindings.last.span
    Apply(apply, bindings, Nil)(apply.span | spanEnd)

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
    token == Token.LPAREN
    || token == Token.LBRACKET
    || token.isInstanceOf[Token.Ident]
    || token.isInstanceOf[Token.BoolLit]
    || token.isInstanceOf[Token.StringStart]
    || token.isInstanceOf[Token.CharLit]
    || token.isInstanceOf[Token.IntLit]

  def simplePattern(): Word =
    val item = peekItem()

    item.token match
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

      case _: Token.StringStart =>
        next()
        parseString(item)

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
