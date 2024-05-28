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
  enum Word extends Positioned:
    case Unit()
    case IntLit(value: Int)
    case BoolLit(value: Boolean)
    case Ident(symbol: Symbol)
    case Assign(symbol: Symbol, rhs: Phrase)
    case If(cond: Phrase, thenp: Phrase, elsep: Phrase)
    case While(cond: List[Word], body: List[Word])

    val tpe: Type =
      this match
        case _: Unit     => Type.Unit
        case _: IntLit   => Type.Int
        case _: BoolLit  => Type.Bool

        case _: Assign | _: While => Type.Unit

        case ident: Ident => ident.symbol.info

        case ifte: If => ifte.elsep.tpe

  case class Phrase(words: List[Word], tpe: Type) extends Positioned

  case class Fun(
    symbol: Symbol,
    params: List[Symbol],
    locals: List[Symbol],
    body  : List[Word])
  extends Positioned:
    def name: String = symbol.name

  case class Prog(funs: List[Fun], vals: List[Symbol], main: Symbol):
    Positioned.checkComponentPos(this)
