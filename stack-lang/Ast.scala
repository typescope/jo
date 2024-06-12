import Reporter.{ Positioned, Span }


/***********************************************************************
 *
 * Abstract Syntax Tree
 *
 ***********************************************************************/
object Ast:
  sealed trait Word extends Positioned with Product

  case class IntLit
    (value: Int)
    (val pos: Span)
  extends Word

  case class BoolLit
    (value: Boolean)
    (val pos: Span)
  extends Word

  case class Ident
    (name: String)
    (val pos: Span)
  extends Word, TypeTree

  case class Fence
    (words: Phrase)
    (val pos: Span)
  extends Word

  case class Assign
    (ident: Ident, words: Phrase)
    (val pos: Span)
  extends Word

  case class If
    (cond: Phrase, thenp: Phrase, elsep: Phrase)
    (val pos: Span)
  extends Word

  case class While
    (cond: Phrase, body: Phrase)
    (val pos: Span)
  extends Word

  case class Select
    (qual: Ident | Select, name: String)
    (val pos: Span)
  extends Word

  case class RecordLit
    (args: List[NamedArg])
    (val pos: Span)
  extends Word

  case class NamedArg
    (ident: Ident, arg: Phrase)
    (val pos: Span)
  extends Positioned:
    def name = ident.name

  case class Phrase
    (tdefs: List[TypeDef], words: List[Word])
    (val pos: Span)
  extends Positioned

  //---------------------------- types ---- ------------------------------------

  sealed trait TypeTree extends Positioned with Product

  case class RecordType
    (fields: List[Field])
    (val pos: Span)
  extends TypeTree

  case class Field
    (ident: Ident, typ: TypeTree)
    (val pos: Span)
  extends Positioned:
    def name = ident.name

  //-------------------------- definitions -------------------------------------

  sealed trait Def extends Positioned with Product:
    val ident: Ident
    val name: String = ident.name

  case class ValDef
    (ident: Ident, typ: TypeTree, rhs: Phrase, mutable: Boolean)
    (val pos: Span)
  extends Word, Def

  case class Param
    (ident: Ident, typ: TypeTree)
    (val pos: Span)
  extends Positioned:
    def name = ident.name

  case class FunDef
    (ident: Ident, params: List[Param], resType: TypeTree, body: Phrase)
    (val pos: Span)
  extends Def

  case class TypeDef
    (ident: Ident, rhs: TypeTree)
    (val pos: Span)
  extends Def

  case class Prog(defs: List[Def], main: Phrase)
