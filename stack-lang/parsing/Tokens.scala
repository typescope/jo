package parsing

import ast.Positions.Span

object Tokens:
  case class WithSpan[T](value: T, span: Span)

  /** Raw comment data before processing.
    * Span is kept for accurate warning positions during processing.
    */
  enum RawComment:
    case SingleLine(content: String, span: Span)
    case Block(content: String, columnOffset: Int, span: Span)

  /** Tokens recognized by the scanner */
  enum Token:
    case LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE
    case AS, CASE, DO, END, ELSE, EXTENSION, FOR, IF, IMPORT, IN, MATCH,
         NSPACE, PARAM, THEN, TYPE, VAL, VAR, WHILE, WITH, ALLOW, OBJECT, DEF, RECEIVES,
         PATTERN, SECTION, UNION, BEGIN, AUTO, DEFER, NEW, CLASS, PRIVATE, RETURN, BREAK, CONTINUE,
         INTERFACE, VIEW, IS, RESCUE, THIS, ANNOTATION, AT
    case COMMA, DOT, EOF
    case COLON, RARROW, EQL
    case IntLit(value: String)(val isHex: Boolean)
    case FloatLit(value: String)
    case BoolLit(value: Boolean)
    case CharLit(value: Int)
    case Name(name: String)
    case Operator(name: String)
    case RegexLit(content: String)
    // Multi-line string tokens (for parser to handle indentation/continuation)
    case StringStart(quoteCount: Int) // """ or """""
    case StringEnd                    // """ or """""
    case StringLine(content: String) // One line of raw string content (with escapes)
    case InterpolationStart           // Start of interpolation \{

  /** The indent info is the same for all tokens of the same line */
  case class TokenInfo(
    token: Token,
    span: Span,
    indent: Indent,
    precedingComments: List[RawComment]
  )

  /** Support for indentation syntax
    *
    * The indentation syntax is motivated for avoiding explicit semicolon
    * or `end` to end a construct.
    *
    * With indentation-syntax, a block ends if a token is beyond the limit
    * indentation.
    *
    *
    * - For a val or fun definition, the limit for rhs is the line indentation
    *   of `val` and `def` respectively.
    *
    * - For while/do, limit for the body is the line indentation of `do`.
    *
    * - For if/then/else, limit for the branches are the line indentations of
    *   `then` and `else` respectively.
    *
    * - For pattern value definitions, the limit is the line indentaion of `val`.
    *
    * The line indentation info is the same for all tokens of the same line.
    */
  class Indent(val line: Int, val lineIndent: Int, val tokenOffset: Int):

    assert(line >= 0, "line = " + line)
    assert(lineIndent >= 0, "lineIndent = " + lineIndent)
    assert(tokenOffset >= 0, "tokenOffset = " + tokenOffset)

    def isFirstOfLine: Boolean = lineIndent == tokenOffset

    /** Whether this indentation is a dedent relative to the reference.
      *
      * A dedent begins a line whose indentation is less than or equal (`<=`)
      * to the reference line's indentation.
      */
    def isDedent(ref: Indent): Boolean =
      this.isFirstOfLine && this.tokenOffset <= ref.lineIndent

    /** Whether this indentation is an outdent relative to the reference.
      *
      * An outdent is strictly smaller (`<`), while a dedent is `<=`.
      *
      * Outdent is used when checking if/else where an `else` aligned under its
      * `if` is a dedent but not an outdent.
      */
    def isOutdent(ref: Indent): Boolean =
      this.isFirstOfLine && this.tokenOffset < ref.lineIndent

    /** Whether this indentation is an indent relative to the reference. */
    def isIndent(ref: Indent): Boolean =
      this.isFirstOfLine && this.tokenOffset > ref.lineIndent

    /** Whether this indentation is an indent relative to the reference, or both
      * are on the same line.
      */
    def isIndentOrSameLine(ref: Indent): Boolean =
      isSameLine(ref) || isIndent(ref)

    /** Either of the following is true:
      * - `this` is first of line and has the same offset as line indentation of `this`
      * - `other` and `this` are on the same line
      */
    def isSameIndent(other: Indent): Boolean =
      other.isFirstOfLine && other.tokenOffset == this.lineIndent
      || other.line == this.line

    /** Is the other vertically aigned with `this` and both are first of line */
    def isAligned(other: Indent): Boolean =
      this.isFirstOfLine && other.isFirstOfLine
      && other.tokenOffset == this.tokenOffset

    /** Are the two tokens on the same line? */
    def isSameLine(other: Indent): Boolean = this.line == other.line

    def lineStart: Indent = Indent(line, lineIndent, lineIndent)

    override def toString: String =
      "line = " + line + ", lineIndent = " + lineIndent + ", tokenOffset = " + tokenOffset
