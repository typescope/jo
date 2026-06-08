package fuzzing

import scala.util.Random

import ast.Positions.Source
import parsing.Scanner
import parsing.Tokens.*
import reporting.Reporter

/** Token-aware mutator.
  *
  * Tokenizes the input once per call, picks 1–4 mutation operators from a
  * weighted pool, collects their patches against the original byte array,
  * drops overlapping patches, and applies the survivors in a single walk.
  *
  * Every operator is a pure `(bytes, tokens, rng, others) => Seq[Patch]`.
  * They do not observe each other's edits, which keeps offset arithmetic
  * straightforward (all patches reference offsets in the original `bytes`).
  *
  * Whitespace between tokens is never touched — mutations happen inside
  * token spans and at token boundaries, which preserves Jo's indentation.
  *
  * Falls back to [[ByteMutator]] when:
  *   - the Scanner throws on tokenization,
  *   - the input is not valid UTF-8 (byte offsets wouldn't align),
  *   - fewer than two tokens are present,
  *   - all picked operators declined, producing no net change.
  */
object TokenMutator extends Mutator:

  import Mutator.*

  //--------------------------------------------------------------------------
  // Types

  enum TokKind:
    case Identifier
    case Keyword
    case Operator
    case Punctuation
    case IntLiteral
    case FloatLiteral
    case BoolLiteral
    case CharLiteral
    case StringPiece            // whole "..." / """ ... """ (incl. interpolations) treated as opaque
    case Tagged                 // TaggedLiteral — opaque
    case Other

  /** A contiguous byte region covering one token (or an opaque string / tagged
    * run merged from several raw tokens). Offsets are UTF-8 byte offsets into
    * the original input.
    */
  case class TokSlice(kind: TokKind, start: Int, length: Int):
    def end: Int = start + length

  private type Op =
    (Array[Byte], Vector[TokSlice], Random, IndexedSeq[Array[Byte]]) => Seq[Patch]

  //--------------------------------------------------------------------------
  // Entry point

  def mutate(input: Array[Byte], rng: Random, others: IndexedSeq[Array[Byte]]): Array[Byte] =
    if !utf8RoundTrips(input) then ByteMutator.mutate(input, rng, others)
    else
      val tokens = tokenizeOrNull(input)

      if tokens == null || tokens.size < 2 then
        ByteMutator.mutate(input, rng, others)
      else
        val k = 1 + rng.nextInt(4)
        val patches = (0 until k).flatMap(_ => pickOp(rng)(input, tokens, rng, others))
        val out = Patch.applyAll(input, patches)

        if java.util.Arrays.equals(out, input) then ByteMutator.mutate(input, rng, others)
        else out
  end mutate

  //--------------------------------------------------------------------------
  // Op weights

  private val weightedOps: IndexedSeq[Op] = IndexedSeq(
    deleteToken, deleteToken,                  // 2
    duplicateToken,                            // 1
    swapSameKind,                              // 1
    replaceKeyword, replaceKeyword,            // 2
    replaceOperator, replaceOperator,          // 2
    replaceIdent, replaceIdent, replaceIdent,  // 3
    spliceBalancedRegion,                      // 1
  )

  private def pickOp(rng: Random): Op = weightedOps(rng.nextInt(weightedOps.size))

  //--------------------------------------------------------------------------
  // Tokenization

  /** Returns null on any tokenization failure. */
  private def tokenizeOrNull(bytes: Array[Byte]): Vector[TokSlice] =
    try tokenize(bytes)
    catch case _: Throwable => null

  private def tokenize(bytes: Array[Byte]): Vector[TokSlice] =
    given Reporter = Reporter.createReporter(buffer = true)
    given Source   = new Source("<fuzz-token-mutator>")

    val scanner = new Scanner(new String(bytes, "UTF-8"))
    val buf     = Vector.newBuilder[TokSlice]
    var done    = false

    while !done do
      val info = scanner.next()
      info.token match
        case Token.EOF =>
          done = true

        case Token.StringStart(q) =>
          val startOffset = info.span.start
          val endOffset   = consumeString(scanner, q, startOffset)
          buf += TokSlice(TokKind.StringPiece, startOffset, endOffset - startOffset)

        case t =>
          classify(t) match
            case Some(kind) => buf += TokSlice(kind, info.span.start, info.span.length)
            case None       => ()
    end while

    buf.result()
  end tokenize

  /** Drive the scanner through a multi-part string until its matching `StringEnd`.
    * Returns the end offset of the closing marker.
    */
  private def consumeString(scanner: Scanner, quoteCount: Int, startOffset: Int): Int =
    var end  = startOffset
    var done = false

    while !done do
      val info = scanner.nextString(quoteCount)
      info.token match
        case _: Token.StringLine =>
          end = info.span.start + info.span.length

        case Token.InterpolationStart =>
          end = info.span.start + info.span.length
          end = consumeInterpolation(scanner, end)

        case Token.StringEnd =>
          end  = info.span.start + info.span.length
          done = true

        case Token.EOF =>
          done = true

        case _ =>
          end = info.span.start + info.span.length
    end while

    end
  end consumeString

  /** Inside an interpolation expression `\{ ... }`, drive the scanner through
    * nested braces (and nested strings) until we balance the opening brace.
    */
  private def consumeInterpolation(scanner: Scanner, startEnd: Int): Int =
    var depth = 1
    var end   = startEnd

    while depth > 0 do
      val info = scanner.next()
      info.token match
        case Token.EOF =>
          return end

        case Token.LBRACE =>
          depth += 1
          end = info.span.start + info.span.length

        case Token.RBRACE =>
          depth -= 1
          end = info.span.start + info.span.length

        case Token.StringStart(q) =>
          end = consumeString(scanner, q, info.span.start)

        case _ =>
          end = info.span.start + info.span.length
    end while

    end
  end consumeInterpolation

  private def classify(token: Token): Option[TokKind] =
    token match
      case _: Token.Name          => Some(TokKind.Identifier)
      case _: Token.IntLit        => Some(TokKind.IntLiteral)
      case _: Token.FloatLit      => Some(TokKind.FloatLiteral)
      case _: Token.BoolLit       => Some(TokKind.BoolLiteral)
      case _: Token.CharLit       => Some(TokKind.CharLiteral)
      case _: Token.Operator      => Some(TokKind.Operator)

      case Token.EQL | Token.COLON | Token.RARROW | Token.DOT =>
        Some(TokKind.Operator)

      case Token.LPAREN | Token.RPAREN | Token.LBRACKET | Token.RBRACKET
         | Token.LBRACE | Token.RBRACE | Token.COMMA =>
        Some(TokKind.Punctuation)

      case _: Token.StringStart | _: Token.StringLine | Token.StringEnd | Token.InterpolationStart =>
        None  // handled in consumeString path; should not reach here

      case Token.EOF =>
        None

      case _ =>
        // All remaining cases are keyword tokens: DEF, VAL, IF, MATCH, CLASS, ...
        Some(TokKind.Keyword)
  end classify

  //--------------------------------------------------------------------------
  // Cache for `others` tokenizations

  private val othersCache = new java.util.IdentityHashMap[Array[Byte], Vector[TokSlice]]()

  private def tokenizeCached(bytes: Array[Byte]): Vector[TokSlice] =
    val hit = othersCache.get(bytes)

    if hit != null then hit
    else
      val result = tokenizeOrNull(bytes)

      if result == null then Vector.empty
      else
        if othersCache.size > 32 then othersCache.clear()
        othersCache.put(bytes, result)
        result
  end tokenizeCached

  //--------------------------------------------------------------------------
  // Operators

  private def deleteToken: Op = (_, tokens, rng, _) =>
    val tok = tokens(rng.nextInt(tokens.size))
    Seq(Patch(tok.start, tok.length, Array.emptyByteArray))

  private def duplicateToken: Op = (bytes, tokens, rng, _) =>
    val tok  = tokens(rng.nextInt(tokens.size))
    val copy = bytes.slice(tok.start, tok.end)
    Seq(Patch(tok.end, 0, copy))

  private def swapSameKind: Op = (bytes, tokens, rng, _) =>
    val groups = tokens.groupBy(_.kind).values.filter(_.size >= 2).toIndexedSeq

    if groups.isEmpty then Seq.empty
    else
      val g = groups(rng.nextInt(groups.size))
      val i = rng.nextInt(g.size)
      var j = rng.nextInt(g.size)
      if j == i then j = (j + 1) % g.size

      val a      = g(i)
      val b      = g(j)
      val aBytes = bytes.slice(a.start, a.end)
      val bBytes = bytes.slice(b.start, b.end)

      Seq(
        Patch(a.start, a.length, bBytes),
        Patch(b.start, b.length, aBytes),
      )
  end swapSameKind

  private def replaceKeyword: Op = (bytes, tokens, rng, _) =>
    val kws = tokens.filter(_.kind == TokKind.Keyword)

    if kws.isEmpty then Seq.empty
    else
      val target      = kws(rng.nextInt(kws.size))
      val current     = new String(bytes, target.start, target.length, "UTF-8")
      val replacement = pickDifferent(Keywords, current, rng)
      Seq(Patch(target.start, target.length, replacement.getBytes("UTF-8")))
  end replaceKeyword

  private def replaceOperator: Op = (bytes, tokens, rng, _) =>
    val ops = tokens.filter(_.kind == TokKind.Operator)

    if ops.isEmpty then Seq.empty
    else
      val target      = ops(rng.nextInt(ops.size))
      val current     = new String(bytes, target.start, target.length, "UTF-8")
      val replacement = pickDifferent(Operators, current, rng)
      Seq(Patch(target.start, target.length, replacement.getBytes("UTF-8")))
  end replaceOperator

  private def replaceIdent: Op = (bytes, tokens, rng, others) =>
    val idents = tokens.filter(_.kind == TokKind.Identifier)

    if idents.isEmpty then Seq.empty
    else
      val target = idents(rng.nextInt(idents.size))

      val replacement: Option[Array[Byte]] = rng.nextInt(3) match
        case 0 =>
          if idents.size < 2 then None
          else
            val other = idents(rng.nextInt(idents.size))
            if other == target then None
            else Some(bytes.slice(other.start, other.end))

        case 1 =>
          pickIdentFromOthers(others, rng)

        case _ =>
          Some(randomIdent(rng).getBytes("UTF-8"))

      replacement match
        case Some(r) => Seq(Patch(target.start, target.length, r))
        case None    => Seq.empty
  end replaceIdent

  private def spliceBalancedRegion: Op = (bytes, tokens, rng, others) =>
    val pairs = matchedPairs(tokens, bytes)

    if pairs.isEmpty || others.isEmpty then Seq.empty
    else
      val (open, close) = pairs(rng.nextInt(pairs.size))
      val wantChar      = bytes(open.start).toChar

      findDonor(others, rng, wantChar, tries = 4) match
        case Some((donorBytes, dOpen, dClose)) =>
          val replacement = donorBytes.slice(dOpen.start, dClose.end)
          Seq(Patch(open.start, close.end - open.start, replacement))

        case None => Seq.empty
  end spliceBalancedRegion

  //--------------------------------------------------------------------------
  // Helpers

  private def utf8RoundTrips(bytes: Array[Byte]): Boolean =
    java.util.Arrays.equals(new String(bytes, "UTF-8").getBytes("UTF-8"), bytes)

  private def pickDifferent(pool: Array[String], current: String, rng: Random): String =
    if pool.length <= 1 then pool(0)
    else
      var i = rng.nextInt(pool.length)
      if pool(i) == current then i = (i + 1) % pool.length
      pool(i)
  end pickDifferent

  private def randomIdent(rng: Random): String =
    val len       = 1 + rng.nextInt(8)
    val alphabet  = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"
    val sb        = new StringBuilder
    sb.append(('a' + rng.nextInt(26)).toChar)
    var i = 1
    while i < len do
      sb.append(alphabet(rng.nextInt(alphabet.length)))
      i += 1
    sb.toString
  end randomIdent

  private def pickIdentFromOthers(others: IndexedSeq[Array[Byte]], rng: Random): Option[Array[Byte]] =
    if others.isEmpty then None
    else
      var tries = 4
      while tries > 0 do
        val candidate = others(rng.nextInt(others.size))
        val toks      = tokenizeCached(candidate)
        val idents    = toks.filter(_.kind == TokKind.Identifier)

        if idents.nonEmpty then
          val pick = idents(rng.nextInt(idents.size))
          return Some(candidate.slice(pick.start, pick.end))

        tries -= 1
      end while
      None
  end pickIdentFromOthers

  private def matchedPairs(tokens: Vector[TokSlice], bytes: Array[Byte]): IndexedSeq[(TokSlice, TokSlice)] =
    val paren = scala.collection.mutable.Stack.empty[TokSlice]
    val brack = scala.collection.mutable.Stack.empty[TokSlice]
    val brace = scala.collection.mutable.Stack.empty[TokSlice]
    val out   = Vector.newBuilder[(TokSlice, TokSlice)]

    for tok <- tokens do
      if tok.kind == TokKind.Punctuation && tok.length == 1 then
        bytes(tok.start).toChar match
          case '(' => paren.push(tok)
          case ')' => if paren.nonEmpty then out += (paren.pop() -> tok)
          case '[' => brack.push(tok)
          case ']' => if brack.nonEmpty then out += (brack.pop() -> tok)
          case '{' => brace.push(tok)
          case '}' => if brace.nonEmpty then out += (brace.pop() -> tok)
          case _   => ()

    out.result()
  end matchedPairs

  private def findDonor(
      others: IndexedSeq[Array[Byte]],
      rng: Random,
      want: Char,
      tries: Int
  ): Option[(Array[Byte], TokSlice, TokSlice)] =
    var remaining = tries
    while remaining > 0 do
      val candidate = others(rng.nextInt(others.size))
      val toks      = tokenizeCached(candidate)

      if toks.nonEmpty then
        val pairs = matchedPairs(toks, candidate)
        val matching = pairs.filter: (o, _) =>
          o.length == 1 && candidate(o.start).toChar == want

        if matching.nonEmpty then
          val (o, c) = matching(rng.nextInt(matching.size))
          return Some((candidate, o, c))

      remaining -= 1
    end while
    None
  end findDonor

end TokenMutator
