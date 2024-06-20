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
    (args: List[(String, Phrase)])
    (val tpe: Type, val pos: Span)
  extends Word

  case class Ident
    (symbol: Symbol)
    (val pos: Span)
  extends Word:
    def tpe: Type = symbol.info

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

  object Phrase:
    def apply(word: Word): Phrase =
      Phrase(word :: Nil)(word.tpe, word.pos)

  /** Encode of a type with another type */
  case class Encoded
    (repr: Word)
    (val tpe: Type)
  extends Word:
    def pos = repr.pos

    def isValueDrop = repr.tpe.isValueType && tpe.isVoid

  case class Fun
    (symbol: Symbol, params: List[Symbol], locals: List[Symbol], body: Word)
    (val pos: Span)
  extends Tree:
    def name: String = symbol.name
    def tpe = symbol.info

  case class Prog
    (funs: List[Fun], vals: List[Symbol], main: Symbol)
