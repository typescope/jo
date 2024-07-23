import Positions.Span

object Tokens:
  /** Tokens recognized by the scanner */
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

  /** The indent info is the same for all tokens of the same line */
  case class TokenInfo(token: Token, span: Span, indent: Indent)

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
