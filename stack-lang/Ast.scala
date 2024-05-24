import Reporter.Positioned


/***********************************************************************
 *
 * Abstract Syntax Tree
 *
 ***********************************************************************/
object Ast:
  sealed trait Word extends Positioned with Product

  case class IntLit(value: Int) extends Word

  case class BoolLit(value: Boolean) extends Word

  case class Ident(name: String) extends Word

  case class Fence(words: List[Word]) extends Word

  case class Assign(ident: Ident, words: List[Word]) extends Word

  case class If(cond: List[Word], thenp: List[Word], elsep: List[Word])
  extends Word

  case class While(cond: List[Word], body: List[Word]) extends Word

  sealed trait Def extends Positioned with Product:
    val ident: Ident
    val name: String = ident.name

  case class ValDef(ident: Ident, words: List[Word], mutable: Boolean)
  extends Word, Def

  case class FunDef(
    ident: Ident, params: List[Ident], words: List[Word])
  extends Def

  case class Prog(defs: List[Def], main: List[Word]):
    Positioned.checkComponentPos(this)
