/***********************************************************************
 *
 * Abstract Syntax Tree
 *
 ***********************************************************************/

object Ast:
  enum Word:
    case IntLit(value: Int)
    case BoolLit(value: Boolean)
    case Ident(name: String)
    case Proc(words: List[Word])
    case IfStat(cond: List[Word], thenp: List[Word], elsep: List[Word])

  enum Def:
    val name: String
    case FunDef(name: String, words: List[Word])
    case ValDef(name: String, words: List[Word])

  case class Prog(defs: List[Def], main: List[Word])
