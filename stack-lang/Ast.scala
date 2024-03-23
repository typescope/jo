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
    case Fence(words: List[Word])
    case IfStat(cond: List[Word], thenp: List[Word], elsep: List[Word])

  enum Def:
    val ident: Ident
    val name: String = ident.name

    case FunDef(ident: Ident, params: List[Ident], words: List[Word])
    case ValDef(ident: Ident, words: List[Word])

  case class Prog(defs: List[Def], main: List[Word])
