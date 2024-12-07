import Symbols.*
import Types.*
import Positions.{ Positioned, Span }

/***********************************************************************
 *
 * Semantic Abstract Syntax Trees
 *
 * All names are resolved to symbols according to scoping rules.
 *
 ***********************************************************************/
object Sast:
  sealed abstract class Tree extends Positioned with Product:
    def tpe: Type

  sealed abstract class Word extends Tree:
    def isEmpty: Boolean =
      this match
        case Phrase(Nil) => true
        case _ => false

    def isDef: Boolean = this.isInstanceOf[Def]

    def show: String = Printing.show(this)

  case class IntLit
    (value: Int)
    (val span: Span)
  extends Word:
    def tpe: Type = IntType

  case class BoolLit
    (value: Boolean)
    (val span: Span)
  extends Word:
    def tpe: Type = BoolType

  case class StringLit
    (value: String)
    (val span: Span)
  extends Word:
    def tpe: Type = StringType

  case class RecordLit
    (args: List[(String, Word)])
    (val tpe: Type, val span: Span)
  extends Word

  case class Ident
    (symbol: Symbol)
    (val span: Span)
  extends Word:
    val tpe: Type = TypeRef(symbol)

  case class Select
    (qual: Word, name: String)
    (val tpe: Type, val span: Span)
  extends Word

  case class Assign
    (symbol: Symbol, rhs: Word)
    (val span: Span)
  extends Word:
    def tpe: Type = VoidType

  case class If
    (cond: Word, thenp: Word, elsep: Word)
    (val tpe: Type, val span: Span)
  extends Word

  case class While
    (cond: Word, body: Word)
    (val span: Span)
  extends Word:
    def tpe: Type = VoidType

  case class Phrase
    (words: List[Word])
    (val tpe: Type, val span: Span)
  extends Word

  case class With
    (expr: Word, param: Symbol, rhs: Word)
    (val tpe: Type, val span: Span)
  extends Word:
    assert(words.nonEmpty)

  case class TypeApply
    (fun: Word, targs: List[TypeTree])
    (val tpe: Type, val span: Span)
  extends Word

  case class Apply
    (fun: Word, args: List[Word])
    (val tpe: Type, val span: Span)
  extends Word:
    fun.tpe.asInvokableType match
      case appType: InvokableType =>
        assert(appType.paramTypes.size == args.size)

    def isPrimitiveCall: Boolean =
      fun match
        case Ident(sym) => sym.isPrimitive
        case _ => false

    /** Get the primitive symbol associated with the call */
    def primitive: Symbol =
      val Ident(sym) = fun: @unchecked
      sym

  /** Encoding of a type with another type
    *
    * It is also used to explicitly represent dropped values.
    */
  case class Encoded
    (repr: Word)
    (val tpe: Type)
  extends Word:
    def span = repr.span

    def isValueDrop = repr.tpe.isValueType && tpe.isVoidType

  case class TypeTree
    (tpe: Type)
    (val span: Span)
  extends Tree

  //----------------------------------------------------------------------------
  // definitions

  sealed abstract class Def extends Word:
    val symbol: Symbol
    val name: String = symbol.name
    val tpe: Type = VoidType

  case class ValDef
    (symbol: Symbol, rhs: Word)
    (val span: Span)
  extends Def

  case class TypeDef
    (symbol: Symbol)
    (val span: Span)
  extends Def

  /** Represents a named function definition
    *
    * @param locals contains a list of local value symbols (excluding params)
    */
  case class FunDef
    (symbol: Symbol, tparams: List[Symbol], params: List[Symbol], body: Word)
    (val locals: List[Symbol], val captures: List[Symbol], val span: Span)
  extends Def

  case class Namespace
    (symbol: Symbol, imports: List[Symbol], contextParams: List[Symbol], defs: List[Def])
    (val span: Span)
  extends Positioned:
    def info: NamespaceInfo = symbol.info.as[NamespaceInfo]

    val fullName: String = symbol.fullName

    def mainSymbol: Option[Symbol] =
      val funs = defs.filter(defn => defn.symbol.isFunction && defn.symbol.name == "main")
      funs.map(_.symbol).headOption

  //----------------------------------------------------------------------------
  // helpers

  def dropValue(word: Word): Word =
    assert(word.tpe.isValueType)
    Encoded(word)(VoidType)
