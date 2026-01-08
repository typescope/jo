package parsing

import ast.Positions.Span

object Tokens:
  /** Tokens recognized by the scanner */
  enum Token:
    case LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE
    case AS, CASE, DO, END, ELSE, FOR, FUN, IF, IMPORT, IN, MATCH, NSPACE, PARAM, THEN,
         TYPE, VAL, VAR, WHILE, WITH, ALLOW, OBJECT, DEF, RECEIVES, PATTERN,
         SECTION, UNION, ALIAS, BEGIN, AUTO, DEFER, NEW, CLASS, PRIVATE,
         INTERFACE, VIEW, LIKE, IS
    case COMMA, DOT, EOF
    case COLON, RARROW, EQL, SUBTYPE
    case IntLit(value: String)(val isHex: Boolean)
    case FloatLit(value: String)
    case BoolLit(value: Boolean)
    case CharLit(value: Int)
    case Name(name: String)
    case Operator(name: String)
    // Multi-line string tokens (for parser to handle indentation/continuation)
    case StringStart(quoteCount: Int) // """ or """""
    case StringEnd                    // """ or """""
    case StringLine(content: String) // One line of raw string content (with escapes)
    case InterpolationStart           // Start of interpolation \{

    def withInfo(span: Span, indent: Indent): TokenInfo =
      TokenInfo(this, span, indent)

  /** The indent info is the same for all tokens of the same line */
  case class TokenInfo(token: Token, span: Span, indent: Indent):
    /** Whether the other indentation is a unindentation to the current one */
    def isUnindent(that: TokenInfo): Boolean =
      that.token == Token.EOF || this.indent.isUnindent(that.indent)

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
    *   of `val` and `fun` respectively.
    *
    * - For while/do, limit for the body is the line indentation of `do`.
    *
    * - For if/then/else, limit for the branches are the line indentations of
    *   `then` and `else` respectively.
    *
    * - For case definitions, the limit is the line indentaion of `case`.
    *
    * The line indentation info is the same for all tokens of the same line.
    */
  class Indent(val line: Int, val lineIndent: Int, val tokenOffset: Int):

    assert(line >= 0, "line = " + line)
    assert(lineIndent >= 0, "lineIndent = " + lineIndent)
    assert(tokenOffset >= 0, "tokenOffset = " + tokenOffset)

    def isFirstOfLine: Boolean = lineIndent == tokenOffset

    /** Whether the other indentation is a unindentation to the current one */
    def isUnindent(other: Indent): Boolean =
      other.isFirstOfLine && other.tokenOffset <= this.lineIndent

    /** Whether the other indentation is a unindentation to the current one */
    def isOutdent(other: Indent): Boolean =
      other.isFirstOfLine && other.tokenOffset < this.lineIndent

    /** Whether the other is an indentation to the current one */
    def isIndent(other: Indent): Boolean =
      other.isFirstOfLine && other.tokenOffset > this.lineIndent

    /** Whether the other is an indentation to the current one or both on the same line? */
    def isIndentOrSameLine(other: Indent): Boolean =
      isSameLine(other) || isIndent(other)

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
