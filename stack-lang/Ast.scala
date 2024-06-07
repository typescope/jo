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

  case class Ident(name: String) extends Word, TypeTree

  case class Fence(words: Phrase) extends Word

  case class Assign(ident: Ident, words: Phrase) extends Word

  case class If(cond: Phrase, thenp: Phrase, elsep: Phrase) extends Word

  case class While(cond: Phrase, body: Phrase) extends Word

  case class Phrase(words: List[Word]) extends Positioned

  //---------------------------- types ---- ------------------------------------

  sealed trait TypeTree extends Positioned with Product

  //-------------------------- definitions -------------------------------------

  sealed trait Def extends Positioned with Product:
    val ident: Ident
    val name: String = ident.name

  case class ValDef(
    ident: Ident, typ: TypeTree, rhs: Phrase, mutable: Boolean)
  extends Word, Def

  case class Param(ident: Ident, typ: TypeTree) extends Positioned:
    def name = ident.name

  case class FunDef(
    ident: Ident, params: List[Param], resType: TypeTree, body: Phrase)
  extends Def

  case class TypeDef(ident: Ident, rhs: TypeTree) extends Word, Def

  case class Prog(defs: List[Def], main: Phrase):
    Positioned.checkComponentPos(this)
