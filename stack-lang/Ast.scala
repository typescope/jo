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
    case If(cond: List[Word], thenp: List[Word], elsep: List[Word])

  enum Def:
    val ident: Word.Ident
    val name: String = ident.name

    case FunDef(ident: Word.Ident, params: List[Word.Ident], words: List[Word])
    case ValDef(ident: Word.Ident, words: List[Word])

  case class Prog(defs: List[Def], main: List[Word])
