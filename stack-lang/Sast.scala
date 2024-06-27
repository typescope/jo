import scala.collection.mutable

import Symbols.*
import Types.*
import Reporter.{ Positioned, Span }

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
    (val pos: Span)
  extends Word:
    def tpe: Type = Type.Int

  case class BoolLit
    (value: Boolean)
    (val pos: Span)
  extends Word:
    def tpe: Type = Type.Bool

  case class RecordLit
    (args: List[(String, Word)])
    (val tpe: Type, val pos: Span)
  extends Word

  case class Ident
    (symbol: Symbol)
    (val pos: Span, val tpe: Type = symbol.info)
  extends Word

  case class Select
    (qual: Word, name: String)
    (val tpe: Type, val pos: Span)
  extends Word

  case class Assign
    (symbol: Symbol, rhs: Word)
    (val pos: Span)
  extends Word:
    def tpe: Type = Type.Void

  case class If
    (cond: Word, thenp: Word, elsep: Word)
    (val tpe: Type, val pos: Span)
  extends Word

  case class While
    (cond: Word, body: Word)
    (val pos: Span)
  extends Word:
    def tpe: Type = Type.Void

  case class Phrase
    (words: List[Word])
    (val tpe: Type, val pos: Span)
  extends Word

  /** Encode of a type with another type */
  case class Encoded
    (repr: Word)
    (val tpe: Type)
  extends Word:
    def pos = repr.pos

    def isValueDrop = repr.tpe.isValueType && tpe.isVoid

  case class TypeTree
    (tpe: Type)
    (val pos: Span)
  extends Tree

  sealed trait Def extends Tree:
    val symbol: Symbol
    val name: String = symbol.name

  case class ValDef
    (symbol: Symbol, rhs: Word)
    (val pos: Span)
  extends Word, Def:
    def tpe: Type = Type.Void

  case class TypeDef
    (symbol: Symbol)
    (val pos: Span)
  extends Def:
    def tpe: Type = Type.Void

  case class FunDef
    (symbol: Symbol, tparams: List[Symbol], params: List[Symbol], locals: List[Symbol], body: Word)
    (val pos: Span)
  extends Def:
    def tpe = symbol.info

  case class Prog(defs: List[Def], main: Word):
    def vals: List[Symbol] =
      defs.filter(_.isInstanceOf[ValDef]).map(_.symbol)

    def funs: List[FunDef] =
      defs.filter(_.isInstanceOf[FunDef]).asInstanceOf

    def init: Symbol =
      main match
        case Ident(sym) => sym
        case _ =>
          throw new Exception("Ident expected, found = " + main)
