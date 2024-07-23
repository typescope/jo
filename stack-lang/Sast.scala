import scala.collection.mutable

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

  case class TypeApply
    (fun: Word, targs: List[TypeTree])
    (val tpe: Type, val span: Span)
  extends Word

  case class Apply
    (fun: Word, args: List[Word])
    (val tpe: Type, val span: Span)
  extends Word:
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

    def isValueDrop = repr.tpe.isValueType && tpe.isVoid

  case class TypeTree
    (tpe: Type)
    (val span: Span)
  extends Tree

  sealed trait Def extends Tree:
    val symbol: Symbol
    val name: String = symbol.name

  case class ValDef
    (symbol: Symbol, rhs: Word)
    (val span: Span)
  extends Word, Def:
    def tpe: Type = VoidType

  case class TypeDef
    (symbol: Symbol)
    (val span: Span)
  extends Def:
    def tpe: Type = VoidType

  case class FunDef
    (symbol: Symbol, tparams: List[Symbol], params: List[Symbol], body: Word)
    (val locals: List[Symbol], val captures: List[Symbol], val span: Span)
  extends Word, Def:
    def tpe = symbol.info

  case class Prog(defs: List[Def], main: Word):
    def vals: List[ValDef] =
      defs.filter(_.isInstanceOf[ValDef]).asInstanceOf

    def funs: List[FunDef] =
      defs.filter(_.isInstanceOf[FunDef]).asInstanceOf

    def init: Symbol =
      main match
        case Apply(Ident(sym), Nil) => sym
        case _ =>
          throw new Exception("Ident expected, found = " + main)
