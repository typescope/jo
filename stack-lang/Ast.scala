import Reporter.Positioned


/***********************************************************************
 *
 * Abstract Syntax Tree
 *
 ***********************************************************************/
object Ast:
  enum Word extends Positioned:
    case IntLit(value: Int)
    case BoolLit(value: Boolean)
    case Ident(name: String)
    case Fence(words: List[Word])
    case If(cond: List[Word], thenp: List[Word], elsep: List[Word])
    case ValDef(ident: Word.Ident, words: List[Word]) extends Word, Def

  sealed trait Def extends Positioned with Product:
    val ident: Word.Ident
    val name: String = ident.name

  type ValDef = Word.ValDef

  case class FunDef(
    ident: Word.Ident, params: List[Word.Ident], words: List[Word])
  extends Def

  case class Prog(defs: List[Def], main: List[Word]):
    Positioned.checkComponentPos(this)
