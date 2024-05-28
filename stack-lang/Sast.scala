import scala.collection.mutable

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

  enum Word extends Tree:
    case IntLit(value: Int)
    case BoolLit(value: Boolean)
    case Ident(symbol: Symbol)
    case Assign(symbol: Symbol, rhs: Phrase)
    case If(cond: Phrase, thenp: Phrase, elsep: Phrase)
    case While(cond: List[Word], body: List[Word])

    val tpe: Type =
      this match
        case _: IntLit   => Type.Int
        case _: BoolLit  => Type.Bool

        case _: Assign | _: While => Type.Void

        case ident: Ident => ident.symbol.info

        case ifte: If => ifte.elsep.tpe

  case class Phrase(words: List[Word], tpe: Type) extends Tree

  case class Fun(
    symbol: Symbol,
    params: List[Symbol],
    locals: List[Symbol],
    body  : List[Word])
  extends Tree:
    def name: String = symbol.name
    def tpe = symbol.info

  case class Prog(funs: List[Fun], vals: List[Symbol], main: Symbol):
    Positioned.checkComponentPos(this)
