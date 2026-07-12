/************************************************************************
 *                                                                      *
 * The parser for the stack-oriented language.                          *
 *                                                                      *
 ************************************************************************/
package parsing

import ast.Trees.*
import ast.Naming
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

      val units = Parser.parse(sources)
      for unit <- units do
        println(unit.source.file + ":")
        println(unit.show)
        println()

  def parse(sourceFiles: List[String])(using Reporter): List[FileUnit] = {
    for file <- sourceFiles.sorted yield
      Parser.parse(file)  <| file
  } <| "parsing"

  /** Parse the supplied code */
  def parse(path: String)(using rp: Reporter): FileUnit = try
    val source = Reporter.source(path)
    val defaultModuleName = StringUtil.toPascalCase(IO.fileNameNoExt(path))
    val parser = new Parser(source.content)(using rp, source)
    parser.parse(defaultModuleName)

  catch case _: java.nio.file.NoSuchFileException =>
    Reporter.abortInternal("Source not found: " + path)

   /** A scanner that supports peeking tokens ahead. */
  class LookAheadScanner(scanner: Scanner):
    private val peekedTokens: mutable.ListBuffer[TokenInfo] = new mutable.ListBuffer

    /** Return the token, its span and the line indentation where the token ends */
    def next(): TokenInfo =
      val info =
        if peekedTokens.isEmpty then
          scanner.next()
        else
          peekedTokens.remove(0)

      info

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
  def peekItem(i: Int = 0): TokenInfo = scanner.peekItem(i)
  def eat(expect: Token): TokenInfo =
    val item = peekItem()
    if item.token != expect then
      error("Unexpected token, found = " + item.token + ", expect = " + expect, item.span.toPos)
      throw new SyntaxError
    next()

  /** Process raw comments into a list of doc strings.
    *
    * - Single-line comments: take the content
    * - Block comments: strip based the vertical column of first letter, warn about misaligned content
    *
    * Span information is used for accurate warning positions.
    * After processing, only the string content is returned.
    */
  def processComments(raw: List[RawComment]): List[String] =
    if raw.isEmpty then return Nil

    val processed = raw.flatMap:
      case RawComment.SingleLine(content, _) =>
        content.dropWhile(_ == '/').trim :: Nil

      case RawComment.Block(content, columnOffset, span) =>
        processBlockComment(content, columnOffset, span)

    // Remove leading/trailing empty lines
    processed.dropWhile(_.isEmpty).reverse.dropWhile(_.isEmpty).reverse

  private def processBlockComment(content: String, columnOffset: Int, span: Span): List[String] =
    val lines = content.linesIterator.toList
    if lines.isEmpty then return Nil


    val headLine = lines.head
    val slashCount = headLine.trim.takeWhile(_ == '/').size
    val paddingCount = slashCount + 2
    val stripColumn = columnOffset + paddingCount

    // span.start points to "//[", content starts after it
    var lineOffset = span.start + headLine.size + 1

    val restLines = lines.tail.map: line =>
      val result =
        if line.length < stripColumn then
          // Line is shorter than strip column - check for letter or digit
          if line.exists(Naming.isLetterOrDigit) then
            val pos = Span(lineOffset, line.length).toPos
            warn("Comment content to the left of opening delimiter", pos)
          ""
        else
          // Check for letter or digit to the LEFT of the strip column (columns 0 to stripColumn-1)
          val prefix = line.take(stripColumn)
          if prefix.exists(Naming.isLetterOrDigit) then
            val pos = Span(lineOffset, stripColumn).toPos
            warn("Comment content to the left of opening delimiter", pos)
          // Drop everything up to stripColumn
          line.drop(stripColumn)

      lineOffset += line.length + 1  // +1 for newline
      result
    // end map

    headLine.drop(paddingCount) :: restLines


  /** Parse a string starting with StringStart(n)
    *
    * For single-line (n == 1): collects content until StringEnd, handles escaping
    * For multi-line (n >= 3): collects StringLine tokens, handles indentation stripping
    */
  def parseString(openMarker: TokenInfo): InterpolatedString | StringLit =
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

  private def buildString(partsWithIndent: Seq[(Word, Indent)], quoteCount: Int, resultSpan: Span, baseIndent: Int): InterpolatedString | StringLit =
    val escapePolicy =
      if quoteCount == 1 then
        StringUtil.EscapePolicy.Disable("") // All escapes
      else
        StringUtil.EscapePolicy.Enable("u") // Only unicode escapes

    val processedParts = mutable.ArrayBuffer[Word]()

    def appendLit(text: String, span: Span): Unit =
      if processedParts.nonEmpty then
        val last = processedParts.last
        last match
          case StringLit(content) =>
            processedParts(processedParts.size - 1) = StringLit(content + text)(last.span | span)

          case _ =>
            processedParts += StringLit(text)(span)
      else
        processedParts += StringLit(text)(span)

    for ((part, indent), idx) <- partsWithIndent.zipWithIndex do
      // insert newline for multiline string
      if quoteCount > 2 && idx > 0 && !indent.isSameLine(partsWithIndent(idx - 1)._2) then
        appendLit("\n", Span(part.span.start - 1, 0))

      part match
        case StringLit(content) =>
          val isLineStart = idx < 1 || !indent.isSameLine(partsWithIndent(idx - 1)._2)

          // For multiline, strip base indentation from content that starts with it
          val strippedContent =
            if quoteCount > 1 && isLineStart then
              val lineIndent = content.segmentLength(c => c == ' ' || c == '\t')

              if lineIndent < baseIndent && content.trim.nonEmpty then
                error(s"Line has insufficient indentation (expected at least $baseIndent spaces)", part.pos)

              if content.length >= baseIndent then content.substring(baseIndent) else content
            else
              content

          // Unescape the content
          try
            val unescaped = StringUtil.unescape(strippedContent, escapePolicy)
            appendLit(unescaped, part.span)
          catch
            case e: StringUtil.EscapeError =>
              val errorStart =
                if quoteCount > 1 && indent.isFirstOfLine then
                  part.span.start + e.offset + baseIndent
                else
                  part.span.start + e.offset

              val errorSpan = Span(errorStart, e.length)
              error(e.message, errorSpan.toPos)
              appendLit("", part.span)

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

      case Nil =>
        StringLit("")(resultSpan)

      case parts =>
        InterpolatedString(parts)(resultSpan)
  end buildString

  def skipIndented(limitIndent: Indent) =
    var item = peekItem()
    while
      (!item.indent.isDedent(limitIndent) ||
       item.token == Token.END && !item.indent.isOutdent(limitIndent))
      && item.token != Token.EOF
    do
      next()
      if item.token.isInstanceOf[Token.StringStart] then
        parseString(item)
      item = peekItem()

  def skipUntilEnd() =
    var token = peek()
    while
      token != Token.EOF
    do
      next()
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
      refIndent.tokenOffset != itemIndent.tokenOffset
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

      catch case _: SyntaxError =>
        skipIndented(firstToken.indent)
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

  def parse(defaultModuleName: String): FileUnit =
    val unit = fileUnit(defaultModuleName)
    // With parsing errors, ensure finish scanning
    skipUntilEnd()
    unit

  def fileUnit(defaultModuleName: String): FileUnit =
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

    FileUnit(id, imports, defs, source)

  def qualid(): RefTree =
    var qual: RefTree = ident()
    while peek() == Token.DOT do
      val dot = next()

      if !dot.span.followsImmediate(qual.span) then
        error("Unexpect space before dot selection", dot.span.toPos)

      val id = ident()

      if !id.span.followsImmediate(dot.span) then
        error("Unexpect space after dot selection", dot.span.toPos)

      qual = Select(qual, id.name)(qual.span | id.span)

    qual

  def importStat(): Import =
    val info = eat(Token.IMPORT)
    val id = qualid()
    if peek() == Token.AS then
      next()
      val alias = ident()
      Import(id, Some(alias))(info.span | alias.span)
    else
      Import(id, None)(info.span | id.span)

  def parseTopLevelDef(): Def =
    // Get doc comment from the first token (before any consumption)
    val doc = processComments(peekItem().precedingComments)

    val annots = annotations()
    val mods = modifiers()
    val item = peekItem()

    val defn =
      if item.token == Token.ANNOTATION then annotationDef(mods)
      else if item.token == Token.TYPE then typeDef(mods)
      else if item.token == Token.DEF then funDef(mods)
      else if item.token == Token.PARAM then paramDef(mods)
      else if item.token == Token.PATTERN then patDef(mods)
      else if item.token == Token.UNION then unionDef(mods)
      else if item.token == Token.SECTION then section(mods)
      else if item.token == Token.CLASS then classDef(mods)
      else if item.token == Token.OBJECT then objectDef(mods)
      else if item.token == Token.INTERFACE then interfaceDef(mods)
      else if item.token == Token.EXTENSION then extensionDef(mods)
      else if item.token == Token.AUTO then
        error("Auto definitions are not allowed at top-level", item.span.toPos)
        autoDef()  // Consume and return for better error recovery

      else if item.token == Token.VAL || item.token == Token.VAR then
        error("Value definitions are not allowed at top-level", item.span.toPos)
        val vdef = valDef(item.token)  // Consume and return for better error recovery
        FunDef(
          vdef.ident, tparams = Nil, params = Nil, autos = Nil,
          resultType = vdef.tpt, receives = None, preParamCount = 0,
          preTypeParamCount = 0,
          body = vdef.rhs
        )(vdef.span)

      else
        error("Expect a definition, found = " + item.token, item.span.toPos)
        next()
        throw new SyntaxError

    defn.withAnnotations(annots).withDocComment(doc)

  /** Parse one annotation: @name or @name(args); assumes Token.AT is next */
  def parseOneAnnotation(): Annotation =
    val at = next()  // consume @
    val nm = qualid()
    val args =
      if peek() == Token.LPAREN then
        eat(Token.LPAREN)
        val buf = mutable.ArrayBuffer[CallArg]()
        if peek() != Token.RPAREN then
          buf += annotArg()
          while peek() == Token.COMMA do
            eat(Token.COMMA)
            buf += annotArg()
        eat(Token.RPAREN)
        buf.toList
      else Nil
    Annotation(nm, args)(at.span | nm.span)

  /** Parse zero or more annotation uses: @name or @name(args) */
  def annotations(): List[Annotation] =
    if peek() != Token.AT then Nil
    else parseOneAnnotation() :: annotations()

  /** Parse a single annotation argument: a literal or a named literal (name = literal) */
  def annotArg(): CallArg =
    val item = peekItem()
    item.token match
      case Token.Name(nm) if peek(1) == Token.EQL =>
        val nameSpan = item.span
        next()  // consume name
        next()  // consume =
        val value = annotLiteral()
        NamedArg(Ident(nm)(nameSpan), value)(nameSpan | value.span)

      case _ =>
        annotLiteral()

  /** Parse a literal annotation argument (Int, Bool, or String) */
  def annotLiteral(): Word =
    val item = peekItem()
    item.token match
      case lit: Token.IntLit =>
        next()
        IntLit(lit.value, lit.isHex)(item.span)

      case Token.FloatLit(value) =>
        next()
        FloatLit(value)(item.span)

      case Token.BoolLit(value) =>
        next()
        BoolLit(value)(item.span)

      case _: Token.StringStart =>
        parseString(next()) match
          case s: StringLit => s
          case _ =>
            error("Annotation argument must be a plain string literal, not an interpolated string", item.span.toPos)
            throw new SyntaxError

      case token =>
        error("Annotation argument must be a literal (Int, Bool, or String), found = " + token, item.span.toPos)
        throw new SyntaxError

  /** Parse an annotation definition: annotation name[(params)] */
  def annotationDef(mods: List[Modifier] = Nil): AnnotationDef =
    val tok = eat(Token.ANNOTATION)
    val id = name()
    val params =
      if peek() == Token.LPAREN then
        eat(Token.LPAREN)
        val ps =
          if peek() == Token.RPAREN then
            Nil
          else
            val buf = mutable.ArrayBuffer(param(typeOptional = false, acceptDefault = true))
            paramsRest(buf, typeOptional = false, acceptDefault = true)
        eat(Token.RPAREN)
        ps
      else Nil
    AnnotationDef(id, params)(tok.span | id.span).withMods(mods)

  def section(mods: List[Modifier]): Section =
    val secToken = eat(Token.SECTION)
    val id = name()
    val defs = repeated:
      val item = peekItem()
      if item.indent.isDedent(secToken.indent) then None
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
          val id = name()
          val endItem = eat(Token.RBRACKET)
          Modifier.Private(Some(id))(item.span | endItem.span) :: modifiers()

        else
          Modifier.Private(None)(item.span) :: modifiers()

      case _ =>
        Nil

  def valDef(modifier: Token): ValDef =
    val mutable = modifier == Token.VAR
    val mod = eat(modifier)
    val id = name()

    val tpt =
      if peek() == Token.COLON then
        eat(Token.COLON)
        typ()
      else
        EmptyTypeTree()(id.span)

    val eqItem = eat(Token.EQL)
    val rhs = block(mod.indent, eqItem)
    ValDef(id, tpt, rhs, mutable)(mod.span | rhs.span)

  def autoDef(): AutoDef =
    val auto = eat(Token.AUTO)
    val id = name()
    eat(Token.COLON)
    val tpt = typ()
    val eqItem = eat(Token.EQL)
    val rhs = block(auto.indent, eqItem)
    AutoDef(id, tpt, rhs)(auto.span | rhs.span)

  def funDef(mods: List[Modifier]): FunDef =
    val fun = eat(Token.DEF)
    val preTypeParams = typeParams()
    val preParamList = preParamSection()
    val id = ident()
    val postTypeParams = typeParams()
    val postParamList = postParamSection()
    val autos = autoSection()

    if preTypeParams.nonEmpty && preParamList.isEmpty then
      error("Pre type parameters require a non-empty pre parameter section", id.pos)

    if Naming.isOperator(id.name) then
      if preParamList.size > 1 then
        error("An operator should have no more than 1 pre parameter, found = " + preParamList.size, id.pos)

      else if postParamList.size > 1 then
        error("An operator should have no more than 1 post parameter, found = " + postParamList.size, id.pos)

      else if preParamList.size > 0 && postParamList.size == 0 then
        error("Postfix operator not supported", id.pos)

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
        val eqItem = eat(Token.EQL)
        block(fun.indent, eqItem)

    eatEndOpt(fun.indent)

    val tparams = preTypeParams ++ postTypeParams
    val paramList = preParamList ++ postParamList
    FunDef(id, tparams, paramList, autos, resType, receiveParams, body, preParamList.size, preTypeParams.size)(fun.span | body.span).withMods(mods)

  def defDef(needBody: Boolean, bodyAllowed: Boolean): FunDef =
    val defToken = eat(Token.DEF)
    val id = ident()
    val preTypeParams = typeParams()
    val postTypeParams = typeParams()
    val paramList = postParamSection()
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
          val eqItem = next()
          block(defToken.indent, eqItem)
        else
          error("Expect EQL, found = " + token, peekItem().span.toPos)
          Block(Nil)(resType.span)
      else
        // For interface methods and object type declarations, body is optional
        if token == Token.EQL then
          if bodyAllowed then
            val eqItem = next()
            block(defToken.indent, eqItem)
          else
            error("No body expected for declaration", peekItem().span.toPos)
            val eqItem = next()
            block(defToken.indent, eqItem)
        else
          Block(Nil)(resType.span)

    eatEndOpt(defToken.indent)

    if Naming.isOperator(id.name) then
      if Naming.startsWithPrefixMarker(id.name) then
        if paramList.size != 0 then
          error("A prefix operator should have 0 parameters, found = " + paramList.size, id.pos)

      else if paramList.size == 0 then
        error("Only infix and prefix operators are supported", id.pos)

      else if paramList.size > 1 then
        error("An operator should have no more than 1 post parameter, found = " + paramList.size, id.pos)

    val tparams = preTypeParams ++ postTypeParams
    val preParamCount = 0
    FunDef(id, tparams, paramList, autos, resType, receiveParams, body, preParamCount, preTypeParams.size)(defToken.span | body.span)

  def paramDef(mods: List[Modifier]): ParamDef =
    val token = eat(Token.PARAM)
    val id = name()
    eat(Token.COLON)
    val tpt = typ()
    val default =
      if peek() == Token.EQL then
        val eqItem = eat(Token.EQL)
        Some(block(token.indent, eqItem))
      else
        None

    ParamDef(id, tpt, default)(token.span | tpt.span).withMods(mods)

  def patDef(mods: List[Modifier]): PatDef =
    val pat = eat(Token.PATTERN)
    val preParamList = preParamSection()
    val id = ident()
    val tparams = typeParams()
    val postParamList = simpleParamSection()

    eat(Token.COLON)
    val resType = typ()

    if Naming.isOperator(id.name) then
      if preParamList.size > 1 then
        error("An operator should have no more than 1 pre parameter, found = " + preParamList.size, id.pos)

      else if postParamList.size > 1 then
        error("An operator should have no more than 1 post parameter, found = " + postParamList.size, id.pos)

      else if preParamList.size > 0 && postParamList.size == 0 then
        error("Postfix operator not supported", id.pos)

    eat(Token.EQL)
    val item = peekItem()
    if item.indent.isDedent(pat.indent) then
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
            val pat = pattern(indent => indent.isIndentOrSameLine(caseToken.indent))
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

  def simpleParamSection(): List[Param] =
    if peek() == Token.LPAREN && peek(1) != Token.AUTO then params() else Nil


  def postParamSection(): List[Param] =
    if peek() == Token.LPAREN && peek(1) != Token.AUTO then params(acceptDefault = true) else Nil

  def preParamSection(): List[Param] =
    if peek() == Token.LPAREN && peek(1) != Token.AUTO then
      if peek(1) == Token.RPAREN then
        val lparen = eat(Token.LPAREN)
        val rparen = eat(Token.RPAREN)
        error("Pre parameter section must contain at least one parameter", (lparen.span | rparen.span).toPos)
        Nil
      else
        params()
    else
      Nil

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
    val id = name()
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
    val id = name()
    val tparams = typeParams()

    // Parse constructor parameters if present (simplified syntax)
    val classParams =
      if peek() == Token.LPAREN then params()
      else Nil

    // Parse view declarations and members
    val views = mutable.ArrayBuffer[ViewDecl]()
    val vals = mutable.ArrayBuffer[ValDef]()
    val funs = mutable.ArrayBuffer[FunDef]()

    var continue = true
    while continue do
      val item = peekItem()
      if item.token == Token.EOF || item.indent.isDedent(klass.indent) then
        continue = false

      else if item.token == Token.VIEW then
        views += viewDecl()

      else
        // Get doc comment from the first token before modifiers
        val doc = processComments(peekItem().precedingComments)
        val annots = annotations()
        val mods = modifiers()
        val item = peekItem()

        if item.token == Token.DEF then
          funs += defDef(needBody = true, bodyAllowed = true).withAnnotations(annots).withMods(mods).withDocComment(doc)

        else if peek() == Token.VAL || peek() == Token.VAR then
          val mod = next()
          val mutable = mod.token == Token.VAR
          val id = name()

          val tpt =
            if peek() != Token.COLON then
              EmptyTypeTree()(id.span)
            else
              eat(Token.COLON)
              typ()

          val (body, endSpan) =
            if peek() == Token.EQL then
              val eqItem = eat(Token.EQL)
              val rhs = block(mod.indent, eqItem)
              (rhs, rhs.span)
            else
              if tpt.isEmpty then
                error("Class fields require a type or initializer", id.pos)
              val emptyBlock = Block(phrases = Nil)(id.span)
              (emptyBlock, tpt.span)
          vals += ValDef(id, tpt, body, mutable)(mod.span | endSpan).withAnnotations(annots).withMods(mods).withDocComment(doc)

        else if item.token == Token.AUTO then
          error("Auto definitions are not allowed as class fields", item.span.toPos)
          // Parse the autoDef for better error handling
          autoDef()
          // Continue parsing subsequent members

        else
          continue = false

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
    val id = name()
    val tparams = typeParams()

    val members: List[FunDef] = repeated:
      val item = peekItem()
      if item.indent.isDedent(interface.indent) then
        None
      else if item.token == Token.AT || item.token == Token.DEF || item.token == Token.PRIVATE || item.token == Token.DEFER then
        // Get doc comment from the first token before modifiers
        val doc = processComments(item.precedingComments)
        val annots = annotations()
        val mods = modifiers()
        // Interface methods can have bodies (default implementations) or no bodies
        Some(defDef(needBody = false, bodyAllowed = true).withAnnotations(annots).withMods(mods).withDocComment(doc))
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

  def extensionDef(mods: List[Modifier]): ExtensionDef =
    val extToken = eat(Token.EXTENSION)
    val id = name()
    val tparams = typeParams()

    // Parse base type: "for" type
    eat(Token.FOR)
    val baseTpt = typ()

    // Parse methods (same as interface, but bodies are required)
    val members: List[FunDef] = repeated:
      val item = peekItem()
      if item.indent.isDedent(extToken.indent) then
        None
      else if item.token == Token.AT || item.token == Token.DEF || item.token == Token.PRIVATE || item.token == Token.DEFER then
        val doc = processComments(peekItem().precedingComments)
        val annots = annotations()
        val mods = modifiers()
        Some(defDef(needBody = false, bodyAllowed = true).withAnnotations(annots).withMods(mods).withDocComment(doc))
      else
        error("Expect method definition in extension, found = " + item.token, item.span.toPos)
        next()
        None

    eatEndOpt(extToken.indent)

    val lastSpan =
      if members.nonEmpty then members.last.span
      else baseTpt.span

    ExtensionDef(id, tparams, baseTpt, members)(extToken.span | lastSpan).withMods(mods)

  def objectDef(mods: List[Modifier]): ObjectDef =
    val obj = eat(Token.OBJECT)
    val id = name()

    // Objects cannot have type parameters
    if peek() == Token.LBRACKET then
      error("Objects cannot have type parameters", peekItem().span.toPos)
      typeParams() // consume them anyway

    // Objects cannot have constructor parameters
    if peek() == Token.LPAREN then
      error("Objects cannot have constructor parameters", peekItem().span.toPos)
      params() // consume them anyway

    // Parse view declarations and methods (no vals allowed)
    val views = mutable.ArrayBuffer[ViewDecl]()
    val funs = mutable.ArrayBuffer[FunDef]()

    var continue = true
    while continue do
      val item = peekItem()
      if item.token == Token.EOF || item.indent.isDedent(obj.indent) then
        continue = false

      else if item.token == Token.VIEW then
        val view = viewDecl()
        // Check that it's an intrinsic view (no delegate)
        if view.rhs.isDefined then
          error("Objects cannot have delegate views (view I = expr)", view.span.toPos)
        views += view

      else
        // Get doc comment from the first token before modifiers
        val doc = processComments(peekItem().precedingComments)
        val annots = annotations()
        val mods = modifiers()
        val item = peekItem()

        if item.token == Token.DEF then
          funs += defDef(needBody = true, bodyAllowed = true).withAnnotations(annots).withMods(mods).withDocComment(doc)

        else if peek() == Token.VAL || peek() == Token.VAR then
          error("Objects cannot have fields (val or var declarations)", item.span.toPos)
          // Consume the field definition to continue parsing
          next()
          name()

          if peek() == Token.COLON then
            eat(Token.COLON)
            typ()

          if peek() == Token.EQL then
            val eqItem = eat(Token.EQL)
            block(item.indent, eqItem)
          // Continue parsing subsequent members

        else if item.token == Token.AUTO then
          error("Auto definitions are not allowed as object members", item.span.toPos)
          autoDef()  // Consume the definition for better error recovery
          // Continue parsing subsequent members

        else
          continue = false

    // Check that no constructor is defined (method with same name as object)
    funs.find(_.name == id.name).foreach { ctor =>
      error("Objects cannot have explicit constructor", ctor.span.toPos)
    }

    eatEndOpt(obj.indent)

    val lastSpan =
      if funs.nonEmpty then funs.last.span
      else if views.nonEmpty then views.last.span
      else id.span

    ObjectDef(id, views.toList, funs.toList)(obj.span | lastSpan).withMods(mods)

  def viewDecl(): ViewDecl =
    val viewToken = eat(Token.VIEW)
    val tpt = typ()
    val rhs =
      if peek() == Token.EQL then
        val eqItem = next()
        Some(block(viewToken.indent, eqItem))
      else
        None

    val finalSpan = rhs.map(r => viewToken.span | r.span).getOrElse(viewToken.span | tpt.span)
    ViewDecl(tpt, rhs)(finalSpan)

  def typeDef(mods: List[Modifier]): TypeDef =
    val typeItem = eat(Token.TYPE)
    val preTypeParams = typeParams()
    val id = ident()
    val postTypeParams = typeParams()

    if Naming.isOperator(id.name) then
      if preTypeParams.size > 1 then
        error("An operator should have no more than 1 pre type parameter, found = " + preTypeParams.size, id.pos)

      else if postTypeParams.size > 1 then
        error("An operator should have no more than 1 post type parameter, found = " + postTypeParams.size, id.pos)

      else if preTypeParams.size > 0 && postTypeParams.size == 0 then
        error("Postfix operator not supported", id.pos)

    val rhs =
      peek() match
        case Token.EQL =>
          eat(Token.EQL)
          typ()

        case Token.Operator("<:") =>
          val sub = next()
          val tp = typ()
          val span = sub.span | tp.span
          error("Type bounds are not supported", span.toPos)
          EmptyTypeTree()(id.span)

        case _ =>
          EmptyTypeTree()(id.span)

    val tparams = preTypeParams ++ postTypeParams
    TypeDef(id, tparams, rhs, preTypeParams.size)(typeItem.span | rhs.span).withMods(mods)

  def unionDef(mods: List[Modifier]): Def =
    val union = eat(Token.UNION)
    val id = name()
    val tparams = typeParams()
    eat(Token.EQL)

    def branch(): ClassDef =
      val id = ident()
      val paramList = simpleParamSection()
      val endSpan = if paramList.isEmpty then id.span else paramList.last.span
      ClassDef(id, Nil, paramList, Nil, Nil, Nil)(id.span | endSpan)

    val branches = oneOrMore(branch, Token.Operator("|"))

    // Parse optional methods (same pattern as extensionDef)
    val funs: List[FunDef] = repeated:
      val item = peekItem()
      if item.indent.isDedent(union.indent) then
        None
      else if item.token == Token.AT || item.token == Token.DEF || item.token == Token.PRIVATE || item.token == Token.DEFER then
        val doc = processComments(peekItem().precedingComments)
        val annots = annotations()
        val mods = modifiers()
        Some(defDef(needBody = true, bodyAllowed = true).withAnnotations(annots).withMods(mods).withDocComment(doc))
      else
        None

    eatEndOpt(union.indent)

    val lastSpan =
      if funs.nonEmpty then funs.last.span
      else branches.last.span

    UnionDef(id, tparams, branches, funs)(union.span | lastSpan).withMods(mods)

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
    val id = name()

    peek() match
      case Token.Operator("<:") =>
        val sub = next()
        val tp = typ()
        val span = sub.span | tp.span
        error("Type bounds are not supported", span.toPos)

      case _ =>

    TypeParam(id)(id.span)

  def params(typeOptional: Boolean = false, acceptDefault: Boolean = false): List[Param] =
    eat(Token.LPAREN)
    val list =
      if peek() == Token.RPAREN then Nil
      else paramsRest(mutable.ArrayBuffer(param(typeOptional, acceptDefault)), typeOptional, acceptDefault)
    eat(Token.RPAREN)
    list

  def param(typeOptional: Boolean, acceptDefault: Boolean = false): Param =
    val id = name()
    val tpt =
      if peek() == Token.COLON then
        eat(Token.COLON)
        typ()
      else
        if !typeOptional then
          error(s"Expect type annotation, e.g. ${id.name}: Int", id.pos)

        EmptyTypeTree()(id.span)

    val default =
      if acceptDefault && peek() == Token.EQL then
        eat(Token.EQL)
        defaultValue()
      else
        None

    Param(id, tpt, default)(id.span | tpt.span)

  /** Parse a default parameter value: either a literal or a qualified identifier.
    * Returns None and reports an error if neither is found.
    */
  def defaultValue(): Option[Word] =
    val item = peekItem()
    item.token match
      case lit: Token.IntLit =>
        next()
        Some(IntLit(lit.value, lit.isHex)(item.span))

      case lit: Token.FloatLit =>
        next()
        Some(FloatLit(lit.value)(item.span))

      case lit: Token.BoolLit =>
        next()
        Some(BoolLit(lit.value)(item.span))

      case lit: Token.CharLit =>
        next()
        Some(CharLit(lit.value)(item.span))

      case Token.StringStart(_) =>
        next()
        parseString(item) match
          case str: StringLit => Some(str)
          case _ =>
            error("Default value must be a plain string literal, not an interpolated string", item.span.toPos)
            None

      case _: Token.Name =>
        Some(qualid())

      case _ =>
        error("Default value must be a literal or a qualified identifier", item.span.toPos)
        val tok = peek()
        if tok != Token.COMMA && tok != Token.RPAREN then word(prevWord = null)
        None

  def paramsRest(acc: mutable.ArrayBuffer[Param], typeOptional: Boolean, acceptDefault: Boolean = false): List[Param] =
    val token = peek()
    if token == Token.RPAREN || token == Token.EOF then acc.toList
    else
      eat(Token.COMMA)
      paramsRest(acc += param(typeOptional, acceptDefault), typeOptional, acceptDefault)

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


  /** Parse method reference list for extension types: [Ext.m1, Ext.m2!, ...]
    *
    * Each entry is a (qualid, isOverride) pair. The `!` suffix marks intentional
    * shadowing of a base type member.
    *
    * Returns the list and the span of the closing bracket.
    */
  def methodRefList(): (List[(RefTree, Boolean)], Span) =
    eat(Token.LBRACKET)
    val methods = mutable.ArrayBuffer[(RefTree, Boolean)]()

    def parseOne(): Unit =
      val ref = qualid()
      val isOverride = peek() == Token.Operator("!")
      if isOverride then next()
      methods += ((ref, isOverride))

    if peek() != Token.RBRACKET then
      parseOne()
      while peek() == Token.COMMA do
        eat(Token.COMMA)
        parseOne()

    val endSpan = eat(Token.RBRACKET).span
    (methods.toList, endSpan)


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
  def block(limitIndent: Indent, lastItem: TokenInfo): Word =
    val item = peekItem()

    if item.indent.isSameLine(lastItem.indent) then
      try
        return phrase()
      catch case _: SyntaxError =>
        error("Expected a phrase after " + lastItem.token, lastItem.span.toPos)
        throw new SyntaxError

    else if item.indent.isDedent(limitIndent) then
      error("Code expected after " + lastItem.token + ", but nothing found", lastItem.span.toPos)
      Block(phrases = Nil)(lastItem.span.endPoint)

    else
      stanza(mutable.ArrayBuffer(), limitIndent, peekItem())

  def stanza(phrases: mutable.ArrayBuffer[Word], limitIndent: Indent, refToken: TokenInfo): Block =
    def finalResult(): Block =
      if phrases.isEmpty then
        Block(phrases = Nil)(peekItem().span)

      else
        val span = phrases.head.span | phrases.last.span
        Block(phrases.toList)(span)

    val item = peekItem()
    if item.indent.isDedent(limitIndent) || item.token == Token.EOF then
      finalResult()

    else

      try
        val p = phrase()
        if phrases.nonEmpty then checkAlign(refToken, item)
        stanza(phrases += p, limitIndent, refToken)

      catch case _: SyntaxError =>
        skipIndented(limitIndent)
        finalResult()

  def withClause(): Word =
    // TODO: inheriting limit or use current?
    val withItem = eat(Token.WITH)
    val args = oneOrMore(withArg, Token.COMMA)
    val inItem = eat(Token.IN)
    checkAlign(withItem, inItem, allowSameLine = true)

    val body = block(inItem.indent, inItem)
    With(body, args)(body.span | withItem.span)

  def withArg(): WithArg =
    val id = qualid()
    eat(Token.EQL)
    val rhs = simpleExpr()
    WithArg(id, rhs)(id.span | rhs.span)

  def allowClause(): Word =
    // TODO: inheriting limit or use current?
    val allowItem = eat(Token.ALLOW)
    val params =
      peek() match
        case Token.Name("none") =>
          next()
          Nil
        case _ =>
          oneOrMore(qualid, Token.COMMA)

    val inItem = eat(Token.IN)
    checkAlign(allowItem, inItem, allowSameLine = true)

    val body = block(inItem.indent, inItem)
    Allow(body, params)(allowItem.span | body.span)

  /** An expression admissible in any position, including a comma-separated list:
   *  every form except the inline colon call. It is `expr` with the inline colon
   *  call turned off.
   */
  def simpleExpr(): Word = expr(allowInlineColon = false)

  /** The expression parser.
   *
   *  `allowInlineColon` is what `simpleExpr` turns off: an inline colon call
   *  (`foo: a, b`) is admissible everywhere except directly inside a comma-separated
   *  list, because its commas would collide with the list's own. Comma positions call
   *  `simpleExpr` (flag off); every other position calls `expr` (flag on, the default).
   */
  def expr(allowAssign: Boolean = false, allowInlineColon: Boolean = true): Word =
    val headItem = peekItem()

    headItem.token match
      case Token.IF        => ifElse()
      case Token.MATCH     => patmat()
      case Token.ALLOW     => allowClause()
      case Token.WITH      => withClause()

      case _ if isLambdaStart() =>
        lambdaExpr()

      case token =>
        val w = word(prevWord = null) match
          case Some(w) => w
          case None =>
            error("Expect an expression, found = " + token, headItem.span.toPos)
            throw new SyntaxError

        val item = peekItem()
        val isDedent = item.indent.isDedent(headItem.indent)

        item.token match
          case Token.EQL if allowAssign =>
            if !w.isInstanceOf[RefTree | BracketApply] then
              error("Unexpected left-side of assignment", w.pos)

            assign(w)

          case Token.COLON =>
            colonCall(w, allowInlineColon)

          case Token.DOT if !isDedent =>
            dotChain(w, headItem.indent)

          case Token.RESCUE =>
            val rescueItem = item
            next()
            val pat = simplePattern(prevPattern = null)
            if pat == null then
              error("Expect a pattern after `rescue`", item.span.toPos)
              w
            else
              val arrowItem = eat(Token.RARROW)
              val handler = block(rescueItem.indent, arrowItem)
              RescueExpr(w, pat, handler)(w.span | handler.span)

          case _ =>
            words(mutable.ArrayBuffer(w), limit = Some(headItem.indent))

  private def inlineColonArgs(colonIndent: Indent): List[CallArg] =
    def inlineColonArg(): CallArg =
      if peek().isInstanceOf[Token.Name] && peek(1) == Token.EQL then
        val id = name()
        eat(Token.EQL)
        val rhs = simpleExpr()
        NamedArg(id, rhs)(id.span | rhs.span)

      else
        simpleExpr()

    val acc = mutable.ArrayBuffer.empty[CallArg]

    acc += inlineColonArg()

    while peek() == Token.COMMA do
      val comma = eat(Token.COMMA)

      if !comma.indent.isSameLine(colonIndent) then
        error("Inline colon calls should have commas on the same line as `:`", comma.span.toPos)

      acc += inlineColonArg()

    acc.toList

  private def indentedColonArgs(colonIndent: Indent): List[CallArg] =
    def indentedColonArg(): CallArg =
      if peek().isInstanceOf[Token.Name] && peek(1) == Token.EQL then
        val id = name()
        eat(Token.EQL)
        val rhs = expr()
        NamedArg(id, rhs)(id.span | rhs.span)

      else
        expr()

    val firstItem = peekItem()
    val acc = mutable.ArrayBuffer.empty[CallArg]
    var continue = true

    while continue do
      val item = peekItem()

      if item.indent.isDedent(colonIndent) || item.token == Token.EOF || !item.indent.isFirstOfLine then
        continue = false

      else
        if acc.nonEmpty then checkAlign(firstItem, item)

        acc += indentedColonArg()

        if peek() == Token.COMMA then
          val comma = next()
          error("Comma not allowed for indented colon call", comma.span.toPos)
    end while

    if acc.isEmpty then
      error("Expect colon-call arguments", firstItem.span.toPos)
      throw new SyntaxError

    acc.toList


  private def colonArgs(colon: TokenInfo, allowInlineColon: Boolean): List[CallArg] =
    val item = peekItem()

    if colon.indent.isSameLine(item.indent) then
      if !allowInlineColon then
        error(
          "An inline colon call cannot appear in a comma-context.  " +
            "Parenthesize it or use the indented colon syntax",
          colon.span.toPos
        )

      inlineColonArgs(colon.indent)

    else if item.indent.isIndent(colon.indent) then
      indentedColonArgs(colon.indent)

    else
      error("Expect a colon-call argument", item.span.toPos)
      throw new SyntaxError

  private def colonCall(base: Word, allowInlineColon: Boolean): Word =
    val colon = eat(Token.COLON)

    if base.isInstanceOf[PrefixOperatorCall] then
      error("Colon call head may not start with prefix operator", base.pos)

    else if base.isInstanceOf[IsExpr] then
      error("Colon call head may not start with is-expression", base.pos)

    if !colon.span.followsImmediate(base.span) then
      error("Colon call head should be followed immediately by `:` with no space in between", base.pos)

    val args = colonArgs(colon, allowInlineColon)
    base match
      case New(tpt, Nil) if tpt.span.endOffset == base.span.endOffset =>
        New(tpt, args)(base.span | args.last.span)

      case _ =>
        Apply(base, args)(base.span | args.last.span)

  def word(prevWord: Word | Null): Option[Word] =
    val w = atom() match
      case None => return None
      case Some(w) => w

    val item = peekItem()

    item.token match
      case Token.AS =>
        next()
        val tpt = simpleType(prevType = null)
        if tpt == null then
          error("Expect a type after `as`", item.span.toPos)
          Some(w)
        else
          Some(TypeAscribe(w, tpt)(w.span | tpt.span))

      case Token.IS =>
        next()
        val pat = simplePattern(prevPattern = null)
        if pat == null then
          error("Expect a pattern after `is`", item.span.toPos)
          Some(w)
        else
          Some(IsExpr(w, pat)(w.span | pat.span))

      case _ =>
        w match
          case op @ Ident(name) if Naming.isOperator(name) =>
            val followsPrevWord = prevWord != null && w.span.followsImmediate(prevWord.span)

            val possiblePrefixApply =
              item.span.followsImmediate(w.span)
              && !followsPrevWord

            if possiblePrefixApply then
              atom() match
                case Some(arg) => Some(PrefixOperatorCall(op, arg)(op.span | arg.span))
                case None => Some(w)

            else
              Some(w)

          case _ => Some(w)

  def isParenLambdaStart(): Boolean =
    val token0 = peek(0)
    token0 == Token.LPAREN && {
      val token1 = peek(1)
      lazy val token2 = peek(2)
      lazy val token3 = peek(3)

      token1.isInstanceOf[Token.Name] && (token2 == Token.COLON || token2 == Token.COMMA)
      || token1 == Token.RPAREN && token2 == Token.RARROW
      || token1.isInstanceOf[Token.Name] && token2 == Token.RPAREN && token3 == Token.RARROW
    }

  def isNameLambdaStart(): Boolean =
    peek(0).isInstanceOf[Token.Name] && peek(1) == Token.RARROW

  def isLambdaStart(): Boolean =
    isParenLambdaStart() || isNameLambdaStart()

  def atom(): Option[Word] =
    val item = peekItem()

    def continue(base: Word): Option[Word] = Some(optSelectAndApply(base))

    item.token match
      case Token.LBRACKET => continue(list())

      case Token.LPAREN =>
        continue(fence())

      case _: Token.Operator =>
        // An operator should not be selected or applied
        Some(ident())

      case _: Token.Name =>
        continue(name())

      case lit: Token.IntLit  =>
        next()
        continue(IntLit(lit.value, lit.isHex)(item.span))

      case lit: Token.FloatLit =>
        next()
        continue(FloatLit(lit.value)(item.span))

      case lit: Token.BoolLit =>
        next()
        continue(BoolLit(lit.value)(item.span))

      case lit: Token.CharLit  =>
        next()
        continue(CharLit(lit.value)(item.span))

      case Token.THIS  =>
        next()
        continue(This(item.span))

      case Token.StringStart(_) =>
        next()
        val lit = parseString(item)
        continue(lit)

      case _: Token.RegexLit =>
        next()
        continue(Regex.parseLiteral(item))

      case Token.NEW =>
        continue(newExpr())

      case _ =>
        None

  def optSelectAndApply(word: Word): Word =
    val item = peekItem()

    // Only consume if ". [ (" immediately follows the base word
    if !item.span.followsImmediate(word.span) then return word

    item.token match
      case Token.DOT =>
        eat(Token.DOT)
        val id = ident()
        if !id.span.followsImmediate(item.span) then
          error("Unexpect space after dot selection", item.span.toPos)

        val sel = Select(word, id.name)(word.span | id.span)
        optSelectAndApply(sel)

      case Token.LBRACKET =>
        optSelectAndApply(bracketApply(word))

      case Token.LPAREN =>
        optSelectAndApply(apply(word))

      case _ => word

  def dotChain(base: Word, limitIndent: Indent): Word =
    if base.isInstanceOf[PrefixOperatorCall] then
      error("dot chain head may not start with prefix operator", base.pos)

    else if base.isInstanceOf[IsExpr] then
      error("dot chain head may not start with is-expression", base.pos)

    val refDot = peekItem()

    var chain = base
    var continue = true

    while continue do
      val dot = eat(Token.DOT)

      if !dot.indent.isFirstOfLine then
        error("dot chain continuation dot should start a new line", dot.span.toPos)

      checkAlign(refDot, dot)

      val id = ident()
      if !id.span.followsImmediate(dot.span) then
        error("Unexpect space after dot selection", dot.span.toPos)

      // The selection is only supported in dot chain as it does not immdiately follow the qualifier
      val select = Select(chain, id.name)(chain.span | id.span)
      chain = optSelectAndApply(select)

      if peek() == Token.COLON then
        val colon = eat(Token.COLON)
        if !colon.span.followsImmediate(chain.span) then
          error("Colon call head should be followed immediately by `:` with no space in between", colon.span.toPos)

        if !colon.indent.isSameLine(dot.indent) then
          error("Colon is not on the same line as the dot", colon.span.toPos)

        // a multi-line chain's colon sits on its own indented dot line, so its
        // inline args never collide with an enclosing comma list — always allowed
        val args = colonArgs(colon, allowInlineColon = true)
        chain = Apply(chain, args)(chain.span | args.last.span)

      val item = peekItem()
      continue = item.token == Token.DOT && !item.indent.isDedent(limitIndent)
    end while

    chain


  def words(keyword: TokenInfo): Word =
    word(prevWord = null) match
      case Some(w) => words(mutable.ArrayBuffer(w), limit = None)
      case None =>
        error("Expect words after " + keyword.token + ", found none", keyword.span.toPos)
        throw new SyntaxError

  def words(buf: mutable.ArrayBuffer[Word], limit: Option[Indent]): Word =
    assert(buf.nonEmpty, "empty buf")

    def finish(): Word =
      buf(0) match
        case id @ Ident(name) if buf.size == 2 && Naming.isOperator(name) =>
          val arg = buf(1)
          PrefixOperatorCall(id, buf(1))(id.span | arg.span)

        case _ =>
          if buf.size == 1 then
            buf(0)
          else
            val span = buf.head.span | buf.last.span
            Expr(buf.toList)(span)

    val item = peekItem()

    def shouldContinueWithLimit: Boolean =
      if !item.indent.isFirstOfLine then return true

      if item.indent.isOutdent(limit.get) then return false

      val isLastOperator = buf.last match
        case Ident(name) if Naming.isOperator(name) => true
        case _ => false

      val isCurrentInfix = item.token match
        case _: Token.Operator =>
          // check whether current is infix instead of prefix
          //
          // prefix operator must be followed immediately by the operand
          val item2 = peekItem(1)
          !item2.span.followsImmediate(item.span)

        case _ => false

      if isLastOperator then !isCurrentInfix else isCurrentInfix

    val shouldStop =
        limit.nonEmpty && !shouldContinueWithLimit
        || item.token == Token.EOF

    if shouldStop then
      finish()

    else
      word(prevWord = buf.last) match
        case Some(w) => words(buf += w, limit)
        case None => finish()

  def phrase(): Word =
    val item = peekItem()
    val token = item.token

    // Helper to process comments only for definitions
    def doc: List[String] = processComments(item.precedingComments)

    token match
      case Token.VAL =>
        if isPlainValDefStart() then
          valDef(Token.VAL).withDocComment(doc)
        else
          patValDef()

      case Token.VAR  =>
        valDef(Token.VAR).withDocComment(doc)

      case Token.WHILE     => whileDo()

      case Token.FOR       => forLoop()

      case Token.AUTO =>
        autoDef().withDocComment(doc)

      case Token.DEF =>
        funDef(mods = Nil).withDocComment(doc)

      case Token.PATTERN =>
        patDef(mods = Nil).withDocComment(doc)

      case Token.TYPE =>
        error("Type definitions are only permitted at top-level", item.span.toPos)
        val tdef = typeDef(mods = Nil)
        Block(Nil)(tdef.span)

      case Token.AT =>
        error("Annotations are not permitted on local definitions", item.span.toPos)
        annotations()
        phrase()

      case Token.DEFER | Token.PRIVATE =>
        error("Cannot use " + token + " for local definitions", item.span.toPos)
        next()
        phrase()

      case Token.RETURN    => returnExpr()
      case Token.BREAK     => breakExpr()
      case Token.CONTINUE  => continueExpr()

      case _ => expr(allowAssign = true)

  def returnExpr(): Word =
    val retItem = eat(Token.RETURN)
    val nextItem = peekItem()

    val value =
      if nextItem.indent.isDedent(retItem.indent) then None
      else Some(expr())

    Return(value)(retItem.span | value.map(_.span).getOrElse(retItem.span))

  def breakExpr(): Word =
    val breakItem = eat(Token.BREAK)
    Break(breakItem.span)

  def continueExpr(): Word =
    val continueItem = eat(Token.CONTINUE)
    Continue(continueItem.span)

  def typ(): TypeTree =
    def continue(tp: TypeTree): TypeTree =
      val nextItem = peekItem()
      nextItem.token match
        case Token.RARROW =>
          next()
          val resType = typ()
          val params = optReceiveParams().getOrElse(Nil)
          val endSpan = if params.isEmpty then resType.span else params.last.span
          FunctionType(tp :: Nil, resType, params)(tp.span | endSpan)

        case Token.Operator("|") =>
          val branches = mutable.ArrayBuffer[TypeTree](tp)

          var item = nextItem
          while item.token == Token.Operator("|") do
            next()
            val branch = simpleType(prevType = null)
            if branch == null then
              error("A type expected after `|`", item.span.toPos)
              item = null
            else
              branches += branch
              item = peekItem()
          end while

          val span = branches.head.span | branches.last.span
          UnionType(branches.toList)(span)


        case Token.Operator(":-") =>
          next()
          val adapters = adapterList()
          val endSpan =
            if adapters.isEmpty then
              tp.span
            else
              adapters.last.span

          DuckType(tp, adapters)(tp.span | endSpan)

        case Token.Operator(":+") =>
          next()
          val (methods, endSpan) = methodRefList()
          ExtensionType(tp, methods)(tp.span | endSpan)

        case _ =>
          val tps = mutable.ArrayBuffer[TypeTree](tp)

          var lastType = simpleType(prevType = tp)
          while lastType != null do
            tps += lastType
            val item = peekItem()
            if item.indent.isFirstOfLine then
              lastType = null
            else
              lastType = simpleType(prevType = lastType)
          end while
          val span = tps.head.span | tps.last.span
          if tps.size == 1 then tps.head else ExprType(tps.toList)(span)
    end continue

    val item = peekItem()
    item.token match
      case Token.LPAREN =>
        val lparen = next()
        val tps =
          if peek() == Token.RPAREN then
            Nil
          else
            oneOrMore(typ, Token.COMMA)

        eat(Token.RPAREN)

        val token = peek()
        if token == Token.RARROW then
          next()

          val resType = typ()
          val params = optReceiveParams().getOrElse(Nil)
          val endSpan = if params.isEmpty then resType.span else params.last.span
          FunctionType(tps, resType, params)(lparen.span | endSpan)
        else
          if tps.size == 0 || tps.size > 1 then
            error("`=>` expected, found = " + token, item.span.toPos)
            throw new SyntaxError
          else
            continue(tps.head)

      case _ =>
        val tp = simpleType(prevType = null)
        if tp == null then
          error("A type expected, but found = " + item.token, item.span.toPos)
          throw new SyntaxError

        continue(tp)

  def simpleType(prevType: TypeTree | Null): TypeTree | Null =
    val atomStart = peekItem()
    val atom = atomType()
    if atom == null then return null

    val tp =
      atom match
        case op @ Ident(name) if Naming.isOperator(name) =>
          val followsPrevType = prevType != null && op.span.followsImmediate(prevType.span)

          val item = peekItem()
          val possiblePrefixApply =
            item.span.followsImmediate(op.span)
            && !followsPrevType

          if possiblePrefixApply then
            val argType = atomType()
            if argType == null then atom
            else AppliedType(op, argType :: Nil)(op.span | argType.span)
          else
            atom

        case _ => atom

    var tpt: TypeTree = tp
    while peek() == Token.AT && atomStart.indent.isSameLine(peekItem().indent) do
      val annot = parseOneAnnotation()
      tpt = AnnotType(tpt, annot)(tpt.span | annot.span)
    tpt

  def atomType(): TypeTree | Null =
    peek() match
      case Token.LPAREN   =>
        next()
        val tp = typ()
        eat(Token.RPAREN)
        tp

      case _: Token.Operator =>
        ident()

      case _: Token.Name =>
        val id = qualid()
        if peek() == Token.LBRACKET then
          appliedType(id)

        else
          id

      case _ =>
        null

  def optReceiveParams(): Option[List[RefTree]] =
    if peek() == Token.RECEIVES then
      eat(Token.RECEIVES)

      peek() match
        case Token.Name("none") =>
          next()
          Some(Nil)

        case _ =>
          Some(oneOrMore(() => qualid(), Token.COMMA))
    else
      None

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

  /** A name or an operator */
  def ident(): Ident =
    val item = next()
    item.token match
      case id: Token.Name =>
        Ident(id.name)(item.span)

      case op: Token.Operator =>
        Ident(op.name)(item.span)

      case token =>
        error("Expect identifier, found token " + token, item.span.toPos)
        throw new SyntaxError

  /** A name but not operator */
  def name(): Ident =
    val item = next()
    item.token match
      case id: Token.Name =>
        Ident(id.name)(item.span)

      case token =>
        error("Expect a name, found token " + token, item.span.toPos)
        throw new SyntaxError

  def lambdaExpr(): Word =
    val item = peekItem()
    item.token match
      case Token.LPAREN =>
        lambda()
      case _: Token.Name =>
        val id = name()
        val arrow = eat(Token.RARROW)
        val body = block(arrow.indent, arrow)
        val params = Param(id, EmptyTypeTree()(id.span))(id.span) :: Nil
        Lambda(params, body)(id.span | body.span)
      case token =>
        error("Expect lambda, found = " + token, item.span.toPos)
        throw new SyntaxError

  def lambda(): Word =
    val lparen = peekItem()
    val paramList = params(typeOptional = true)
    val arrow = eat(Token.RARROW)
    val body = block(lparen.indent, arrow)
    Lambda(paramList, body)(lparen.span | body.span)

  def fence(): Word =
    val lparen = eat(Token.LPAREN)
    val nested = expr()
    val rparen = eat(Token.RPAREN)
    val span = lparen.span | rparen.span
    Fence(nested)(span)

  def ifElse(elseAlignRefOpt: Option[TokenInfo] = None): Word =
    val ifItem = eat(Token.IF)
    val cond = expr()
    val thenItem = eat(Token.THEN)
    val thenp = block(thenItem.indent, thenItem)
    checkAlign(ifItem, thenItem, allowSameLine = true)
    val elseAlignRef = elseAlignRefOpt.getOrElse(ifItem)

    // else is optional
    val nextItem = peekItem()
    val elsep =
      // outdent else belongs to outer if/else
      if nextItem.token == Token.ELSE && !nextItem.indent.isOutdent(ifItem.indent) then
        val elseItem = eat(Token.ELSE)
        // if cond then
        // else if cond then
        // else
        checkAlign(elseAlignRef, nextItem, allowSameLine = true)
        val blk =
          if peek() == Token.IF && elseItem.indent.isSameLine(peekItem().indent) then
            val nested = ifElse(Some(elseItem))
            Block(nested :: Nil)(nested.span)
          else
            block(nextItem.indent, nextItem)
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
    val body = block(whileItem.indent, doItem)

    checkAlign(whileItem, doItem, allowSameLine = true)

    eatEndOpt(whileItem.indent)

    While(cond, body)(whileItem.span | body.span)

  def forLoop(): Word =
    val forItem = eat(Token.FOR)
    val pattern = exprPattern()
    eat(Token.IN)
    val iter = expr()
    val condOpt = if peek() == Token.IF then
      next()
      Some(expr())
    else
      None
    val doItem = eat(Token.DO)
    val body = block(forItem.indent, doItem)

    checkAlign(forItem, doItem, allowSameLine = true)

    eatEndOpt(forItem.indent)

    For(pattern, iter, condOpt, body)(forItem.span | body.span)

  def assign(lhs: Word): Assign =
    eat(Token.EQL)
    val rhs = expr()
    Assign(lhs, rhs)(lhs.span | rhs.span)

  def bracketApply(fun: Word): Word =
    eat(Token.LBRACKET)
    val args = oneOrMore(() => simpleExpr(), Token.COMMA)
    val endToken = eat(Token.RBRACKET)
    BracketApply(fun, args)(fun.span | endToken.span)

  def apply(fun: Word): Apply =
    val (args, span) = termArgs()
    Apply(fun, args)(fun.span | span)

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
      else oneOrMore(() => simpleExpr(), Token.COMMA)

    val rbrace = eat(Token.RBRACKET)
    ListLit(args)(lbrace.span | rbrace.span)

  def termArgs(): (List[Word | NamedArg], Span) =
    def callArg(): Word | NamedArg =
      if peek().isInstanceOf[Token.Name] && peek(1) == Token.EQL then
        val id = ident()
        eat(Token.EQL)
        val arg = simpleExpr()
        NamedArg(id, arg)(id.span | arg.span)
      else
        simpleExpr()

    val startItem = eat(Token.LPAREN)
    if peek() == Token.RPAREN then
      val endItem = eat(Token.RPAREN)
      return (Nil, startItem.span | endItem.span)

    val acc: mutable.ArrayBuffer[Word | NamedArg] = mutable.ArrayBuffer.empty
    acc += callArg()
    var token = peek()
    while
      token == Token.COMMA
    do
      eat(Token.COMMA)
      acc += callArg()
      token = peek()

    val endItem = eat(Token.RPAREN)
    val span = startItem.span | endItem.span
    (acc.toList, span)

  def patmat(): Match =
    val matchItem = eat(Token.MATCH)
    val scrutinee = expr()
    val caseDecls = cases(mutable.ArrayBuffer.empty, matchItem.indent)

    eatEndOpt(matchItem.indent)

    val span2 = if caseDecls.isEmpty then scrutinee.span else caseDecls.last.span
    Match(scrutinee, caseDecls)(matchItem.span | span2)

  /** Whether `val` starts a plain value definition:
    *   val <name> [: type] = rhs
    *
    * Otherwise it starts a pattern value definition.
    */
  private def isPlainValDefStart(): Boolean =
    peek(1) match
      case _: Token.Name | _: Token.Operator =>
        peek(2) == Token.COLON || peek(2) == Token.EQL
      case _ =>
        false

  def patValDef(): PatValDef =
    val valItem = eat(Token.VAL)
    val pat = exprPattern()
    val eq = eat(Token.EQL)
    val rhs = block(valItem.indent, eq)
    PatValDef(pat, rhs)(valItem.span | rhs.span)

  def cases(acc: mutable.ArrayBuffer[(Case, TokenInfo)], limitIndent: Indent): List[Case] =
    val item = peekItem()
    if item.token == Token.CASE && !item.indent.isOutdent(limitIndent) then
      val caseItem = eat(Token.CASE)

      if acc.nonEmpty then
        checkAlign(acc.head._2, caseItem)

      val pat = pattern()
      val arrow = eat(Token.RARROW)
      val body = block(caseItem.indent, arrow)
      val caseDecl = Case(pat, body)(caseItem.span | body.span)
      cases(acc += caseDecl -> caseItem, limitIndent)
    else
      acc.map(_._1).toList

  def applyPattern(apply: RefTree): Pattern =
    val args = patternArgs()
    val spanEnd = args.last.span
    ApplyPattern(apply, args)(apply.span | spanEnd)

  def patternArgs(): List[Pattern] =
    val nested = new mutable.ArrayBuffer[Pattern]
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

  def typePattern(id: Ident): Pattern =
    eat(Token.COLON)
    val tpt = simpleType(prevType = null)
    TypePattern(id, tpt)(id.span | tpt.span)

  def sequenceItem(): SequenceItem =
    val item = peekItem()
    item.token match
      case Token.Operator("..") =>
        val dotdot = next()
        val nextItem = peekItem()
        nextItem.token match
          case Token.WHILE =>
            next()
            val guard = exprPattern()
            RepeatPattern(None, Some(guard))(dotdot.span | guard.span)

          case _: Token.Name =>
            val id = name()
            val afterId = peek()
            if afterId == Token.WHILE then
              next()
              val guard = exprPattern()
              RepeatPattern(Some(id), Some(guard))(dotdot.span | guard.span)
            else
              RepeatPattern(Some(id), None)(dotdot.span | id.span)

          case _ =>
            RepeatPattern(None, None)(dotdot.span)

      case _ =>
        AtomItem(pattern())

  def exprPattern(): Pattern =
    val patterns = new mutable.ArrayBuffer[Pattern]
    var pat = simplePattern(prevPattern = null)

    while pat != null do
      patterns += pat
      val item = peekItem()
      if item.indent.isFirstOfLine then
        pat = null
      else
        pat = simplePattern(prevPattern = pat)

    patterns.toList match
      case (op: Ident) :: rhs :: Nil if Naming.isOperator(op.name) =>
        ApplyPattern(op, rhs :: Nil)(op.span | rhs.span)

      case pats =>
        if pats.size == 1 then pats.head
        else ExprPattern(pats)(pats.head.span | pats.last.span)

  def pattern(): Pattern = pattern(_ => true)

  def pattern(validIndent: Indent => Boolean): Pattern =
    val exprPat = exprPattern()

    val item1 = peekItem()
    val pat1 = if item1.token == Token.IF && validIndent(item1.indent) then
      val keyword = next()
      val cond = words(keyword)
      GuardPattern(exprPat, cond)(exprPat.span | cond.span)
    else
      exprPat

    val item2 = peekItem()
    if item2.token == Token.THEN && validIndent(item2.indent) then
      next()
      val assignments = oneOrMore(() => {
        val id = name()
        eat(Token.EQL)
        val value = simpleExpr()
        (id, value)
      }, Token.COMMA)
      val lastSpan = assignments.last._2.span
      AssignPattern(pat1, assignments)(pat1.span | lastSpan)
    else
      pat1

  def atomPattern(): Pattern | Null =
    val item = peekItem()

    def continue(word: RefTree): Pattern =
      val item = peekItem()

      // Only consume if ". (" immediately follows the base word
      if !item.span.followsImmediate(word.span) then return word

      item.token match
        case Token.DOT =>
          eat(Token.DOT)
          val id = ident()
          if !id.span.followsImmediate(item.span) then
            error("Unexpect space after dot selection", item.span.toPos)

          val sel = Select(word, id.name)(word.span | id.span)
          continue(sel)

        case Token.LPAREN =>
          applyPattern(word)

        case _ => word

    item.token match
      case _: Token.Operator => ident()

      case _: Token.Name => continue(name())

      case ilit: Token.IntLit =>
        next()
        LiteralPattern(IntLit(ilit.value, ilit.isHex)(item.span))

      case Token.FloatLit(value) =>
        next()
        LiteralPattern(FloatLit(value)(item.span))

      case Token.BoolLit(value) =>
        next()
        LiteralPattern(BoolLit(value)(item.span))

      case Token.CharLit(value) =>
        next()
        LiteralPattern(CharLit(value)(item.span))

      case _: Token.StringStart =>
        next()
        parseString(item) match
          case strLit: StringLit =>
            LiteralPattern(strLit)

          case interpo: InterpolatedString =>
            error("String interpolation not allowed in string literal", interpo.pos)
            LiteralPattern(StringLit("")(interpo.span))

      case _: Token.RegexLit =>
        next()
        val regex = Regex.parseLiteral(item)
        RegexPattern(None, regex)(regex.span)

      case Token.LPAREN =>
        next()
        val pat = pattern()
        eat(Token.RPAREN)
        pat

      case Token.LBRACKET =>
        val lbracket = next()
        val items =
          if peek() == Token.RBRACKET then Nil
          else oneOrMore(sequenceItem, Token.COMMA)
        val rbracket = eat(Token.RBRACKET)
        SequencePattern(items)(lbracket.span | rbracket.span)

      case _ =>
        null

  def simplePattern(prevPattern: Pattern | Null): Pattern | Null =
    val pat = atomPattern()

    if pat == null then return null

    val item = peekItem()

    val id = pat match
      case id: Ident => id
      case _ => return pat

    item.token match
      case Token.COLON =>
        typePattern(id.asInstanceOf[Ident])

      case Token.AT =>
        // Bind pattern: x @ pattern
        // Special case: x @ `regex` binds x as Match, not as the scrutinee
        next()
        val nested = atomPattern()
        if nested == null then
          error("Expect a pattern after `@`", item.span.toPos)
          throw new SyntaxError

        else nested match
          case RegexPattern(None, regex) =>
            RegexPattern(Some(id), regex)(id.span | nested.span)
          case _ =>
            BindPattern(id, nested)(id.span | nested.span)

      case _ if Naming.isOperator(id.name) =>
        val followsPrevPattern = prevPattern != null && id.span.followsImmediate(prevPattern.span)

        val possiblePrefixApply =
          item.span.followsImmediate(id.span)
          && !followsPrevPattern

        if possiblePrefixApply then
          val arg = atomPattern()
          if arg == null then
            id
          else
            ApplyPattern(id, arg :: Nil)(id.span | arg.span)
        else
          id

      case _ =>
        id
