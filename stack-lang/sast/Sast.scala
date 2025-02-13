package sast

import Symbols.*
import Types.*

import ast.Positions.{ Positioned, Span }

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
        case Block(Nil) => true
        case _ => false

    def isDef: Boolean = this.isInstanceOf[Def]

    def show: String = Printing.show(this)

    /** Whether the word can be duplicated as neighbors without affecting program semantics */
    def isIdempotent: Boolean =
      this match
        case _: Literal | _: Ident => true

        case Select(qual, _) => qual.isIdempotent

        case Encoded(expr) => expr.isIdempotent

        case _ => false

    /** Strip possible encoding */
    def strip: Word =
      this match
        case Encoded(expr) => expr.strip
        case _ => this

  case class Literal
    (constant: Constant)
    (val tpe: Type, val span: Span)
  extends Word

  case class RecordLit
    (args: List[(String, Word)])
    (val tpe: Type, val span: Span)
  extends Word

  case class Ident
    (symbol: Symbol)
    (val span: Span)
  extends Word:
    assert(!symbol.isType)

    def name: String = symbol.name
    val tpe: Type = TypeRef(symbol)

  case class Select
    (qual: Word, name: String)
    (val tpe: Type, val span: Span)
  extends Word:
    assert(qual.tpe.isValueType)

  /** Assignment to local vars */
  case class Assign
    (ident: Ident, rhs: Word)
    (val span: Span)
  extends Word:
    val symbol = ident.symbol
    def tpe: Type = VoidType

  /** Assignment to object fields */
  case class FieldAssign
    (qual: Word, name: String, rhs: Word)
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

  case class Block
    (words: List[Word])
    (val tpe: Type, val span: Span)
  extends Word

  case class With
    (expr: Word, args: List[WithArg], allow: Option[List[Ident]])
    (val tpe: Type, val span: Span)
  extends Word:
    assert(args.nonEmpty || allow.nonEmpty)

  case class WithArg
    (paramRef: Ident, rhs: Word)
    (val span: Span)
  extends Tree:
    def tpe: Type = VoidType

  case class DefaultParam
    (paramRef: Ident, default: Word)
    (val tpe: Type, val span: Span)
  extends Word

  case class TypeApply
    (fun: Word, targs: List[TypeTree])
    (val tpe: Type, val span: Span)
  extends Word

  case class Apply
    (fun: Word, args: List[Word])
    (val tpe: Type, val span: Span)
  extends Word:
    fun.tpe.asProcType match
      case procType =>
        assert(procType.paramTypes.size == args.size, procType.show + ", " + args)

    def funSymbol: Option[Symbol] =
      fun match
        case Ident(sym)               => Some(sym)
        case TypeApply(Ident(sym), _) => Some(sym)
        case _                        => None

  case class Object
    (self: Symbol, vals: List[ValDef], defs: List[FunDef])
    (val tpe: Type, val span: Span)
  extends Word

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

  sealed trait Def extends Tree:
    val symbol: Symbol
    val name: String = symbol.name
    val tpe: Type = VoidType

  /** Represents definition of contextual parameters */
  case class ParamDef
    (symbol: Symbol, tpt: TypeTree)
    (val span: Span)
  extends Def

  case class ValDef
    (symbol: Symbol, rhs: Word)
    (val span: Span)
  extends Word, Def:
    val isMutable = symbol.isMutable

  case class TypeDef
    (symbol: Symbol)
    (val span: Span)
  extends Word, Def

  /** Represents a named function or method definition
    *
    * @param locals contains a list of local value symbols (excluding params)
    */
  case class FunDef
    (symbol: Symbol, tparams: List[Symbol], params: List[Symbol], body: Word)
    (val span: Span)
  extends Word, Def:
    private lazy val census: (List[Symbol], List[Symbol]) =
      SastOps.variableCensus(this)

    lazy val locals: List[Symbol] = census._1
    lazy val freeVariables: List[Symbol] = census._2

  case class Namespace
    (symbol: Symbol, imports: List[Symbol], defs: List[Def])
    (val span: Span)
  extends Positioned:
    def info: NameTableInfo = symbol.info.as[NameTableInfo]

    val fullName: String = symbol.fullName

    def mainSymbol: Option[Symbol] =
      val funs = defs.filter(defn => defn.symbol.isFunction && defn.symbol.name == "main")
      funs.map(_.symbol).headOption

    def show: String = Printing.show(this)

  //----------------------------------------------------------------------------
  // helpers

  def dropValue(word: Word): Word =
    assert(word.tpe.isValueType)
    Encoded(word)(VoidType)

  def StringLit(s: String)(tp: Type, span: Span) =
    Literal(Constant.String(s))(tp, span)
