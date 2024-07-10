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
    (val span: Span)
  extends Word

  case class BoolLit
    (value: Boolean)
    (val span: Span)
  extends Word

  case class Ident
    (name: String)
    (val span: Span)
  extends Word, TypeTree

  case class Assign
    (ident: Ident, words: Phrase)
    (val span: Span)
  extends Word

  case class Call
    (fun: Word)
    (val span: Span)
  extends Word

  case class If
    (cond: Phrase, thenp: Phrase, elsep: Phrase)
    (val span: Span)
  extends Word

  case class While
    (cond: Phrase, body: Phrase)
    (val span: Span)
  extends Word

  case class Select
    (qual: Word, name: String)
    (val span: Span)
  extends Word

  case class RecordLit
    (args: List[NamedArg])
    (val span: Span)
  extends Word

  case class NamedArg
    (ident: Ident, arg: Phrase)
    (val span: Span)
  extends Positioned:
    def name = ident.name

  case class Variant
    (tag: Ident, values: List[Word], typ: TypeTree)
    (val span: Span)
  extends Word

  case class Match
    (scrutinee: Phrase, cases: List[Case])
    (val span: Span)
  extends Word

  case class Case
    (pat: Pattern, body: Phrase)
    (val span: Span)
  extends Positioned

  case class TypeApply
    (fun: Word, targs: List[TypeTree])
    (val span: Span)
  extends Word

  case class Lambda
    (params: List[Param], body: Phrase)
    (val span: Span)
  extends Word

  case class Phrase
    (tdefs: List[TypeDef], words: List[Word])
    (val span: Span)
  extends Word

  //---------------------------- patterns --------------------------------------

  sealed abstract class Pattern extends Positioned with Product

  case class Wildcard
    ()
    (val span: Span)
  extends Pattern

  case class TagPat
    (tag: Ident, bindings: List[Ident])
    (val span: Span)
  extends Pattern

  //------------------------------ types ---------------------------------------

  sealed trait TypeTree extends Positioned with Product:
    def isEmpty: Boolean = this.isInstanceOf[EmptyTypeTree]

  case class EmptyTypeTree
    ()
    (val span: Span)
  extends TypeTree

  case class RecordType
    (fields: List[Field])
    (val span: Span)
  extends TypeTree

  case class Field
    (ident: Ident, typ: TypeTree)
    (val span: Span)
  extends Positioned:
    def name = ident.name

  case class UnionType
    (branches: List[Branch])
    (val span: Span)
  extends TypeTree

  case class Branch
    (tag: Ident, tpts: List[TypeTree])
    (val span: Span)
  extends Positioned:
    def name = tag.name

  case class AppliedType
    (tpeCtor: Ident, targs: List[TypeTree])
    (val span: Span)
  extends TypeTree:
    assert(targs.nonEmpty)

  case class FunctionType
    (paramTypes: List[TypeTree], resultType: TypeTree)
    (val span: Span)
  extends TypeTree

  //-------------------------- definitions -------------------------------------

  sealed trait Def extends Positioned with Product:
    val ident: Ident
    val name: String = ident.name

  case class ValDef
    (ident: Ident, typ: TypeTree, rhs: Phrase, mutable: Boolean)
    (val span: Span)
  extends Word, Def

  case class Param
    (ident: Ident, typ: TypeTree)
    (val span: Span)
  extends Positioned:
    def name = ident.name

  case class FunDef
    (ident: Ident, tparams: List[TypeParam], params: List[Param], resType: TypeTree, body: Phrase)
    (val span: Span)
  extends Def

  case class TypeParam
    (ident: Ident, bound: TypeTree)
    (val span: Span)
  extends Positioned:
    def name = ident.name

  case class TypeDef
    (ident: Ident, tparams: List[TypeParam], rhs: TypeTree)
    (val span: Span)
  extends Def

  case class Prog(defs: List[Def], main: Phrase)
