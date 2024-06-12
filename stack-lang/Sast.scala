import scala.collection.mutable
import scala.collection.immutable.ListMap

import Symbols.*
import Types.*
import Reporter.Positioned

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

  sealed abstract class Word extends Tree

  case class IntLit(value: Int)(val tpe: Type) extends Word

  case class BoolLit(value: Boolean)(val tpe: Type) extends Word

  case class RecordLit(args: ListMap[String, Phrase])(val tpe: Type) extends Word

  case class Ident(symbol: Symbol)(val tpe: Type) extends Word

  case class Select(qual: Word, name: String)(val tpe: Type) extends Word

  case class Assign(symbol: Symbol, rhs: Phrase)(val tpe: Type) extends Word

  case class If(cond: Phrase, thenp: Phrase, elsep: Phrase)(val tpe: Type) extends Word

  case class While(cond: Phrase, body: Phrase)(val tpe: Type) extends Word

  case class Phrase(words: List[Word])(val tpe: Type) extends Word:
    def isEmpty: Boolean = words.isEmpty

  case class Fun(
    symbol: Symbol, params: List[Symbol], locals: List[Symbol], body: Phrase)
  extends Tree:
    def name: String = symbol.name
    def tpe = symbol.info

  case class Prog(funs: List[Fun], vals: List[Symbol], main: Symbol):
    Positioned.checkComponentPos(this)
