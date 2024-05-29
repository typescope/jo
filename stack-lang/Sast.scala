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
    case While(cond: Phrase, body: Phrase)

    val tpe: Type =
      this match
        case _: IntLit   => Type.Int
        case _: BoolLit  => Type.Bool

        case _: Assign | _: While => Type.Void

        case ident: Ident => ident.symbol.info

        case ifte: If => ifte.elsep.tpe // else can be empty thus void

  case class Phrase(words: List[Word], tpe: Type) extends Tree:
    def isEmpty: Boolean = words.isEmpty
    def resultCount: Byte = if tpe.isVoid then 0 else 1

  case class Fun(
    symbol: Symbol,
    params: List[Symbol],
    locals: List[Symbol],
    body  : Phrase)
  extends Tree:
    def name: String = symbol.name
    def tpe = symbol.info

  case class Prog(funs: List[Fun], vals: List[Symbol], main: Symbol):
    Positioned.checkComponentPos(this)
