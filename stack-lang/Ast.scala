import Positions.{ Positioned, Span }


/***********************************************************************
 *
 * Abstract Syntax Tree
 *
 ***********************************************************************/
object Ast:
  sealed abstract class Tree extends Positioned with Product

  sealed abstract class Phrase extends Tree
  sealed abstract class Word extends Phrase

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
  extends Phrase

  case class If
    (cond: Phrase, thenp: Phrase, elsep: Phrase)
    (val span: Span)
  extends Phrase

  case class While
    (cond: Phrase, body: Phrase)
    (val span: Span)
  extends Phrase

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
  extends Tree:
    def name = ident.name

  case class Variant
    (tag: Ident, values: List[Word], typ: TypeTree)
    (val span: Span)
  extends Word

  case class Match
    (scrutinee: Phrase, cases: List[Case])
    (val span: Span)
  extends Phrase

  case class Case
    (pat: Pattern, body: Phrase)
    (val span: Span)
  extends Tree

  case class TypeApply
    (fun: Word, targs: List[TypeTree])
    (val span: Span)
  extends Word

  case class Lambda
    (params: List[Param], body: Phrase)
    (val span: Span)
  extends Word

  case class Expr
    (words: List[Word])
    (val span: Span)
  extends Phrase:
    assert(words.nonEmpty)

  case class Block
    (phrases: List[Phrase])
    (val span: Span)
  extends Word

  //---------------------------- patterns --------------------------------------

  sealed abstract class Pattern extends Tree

  case class Wildcard
    ()
    (val span: Span)
  extends Pattern

  case class TagPat
    (tag: Ident, bindings: List[Ident])
    (val span: Span)
  extends Pattern

  //------------------------------ types ---------------------------------------

  sealed trait TypeTree extends Tree:
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
  extends Tree:
    def name = ident.name

  case class UnionType
    (branches: List[Branch])
    (val span: Span)
  extends TypeTree

  case class Branch
    (tag: Ident, tpts: List[TypeTree])
    (val span: Span)
  extends Tree:
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

  sealed trait Def extends Tree:
    val ident: Ident
    val name: String = ident.name

  case class ValDef
    (ident: Ident, typ: TypeTree, rhs: Phrase, mutable: Boolean)
    (val span: Span)
  extends Phrase, Def

  case class Param
    (ident: Ident, typ: TypeTree)
    (val span: Span)
  extends Tree:
    def name = ident.name

  case class FunDef
    (ident: Ident, tparams: List[TypeParam], params: List[Param],
        resType: TypeTree, body: Phrase, preParamCount: Int)
    (val span: Span)
  extends Phrase, Def

  case class TypeParam
    (ident: Ident, bound: TypeTree)
    (val span: Span)
  extends Tree:
    def name = ident.name

  case class TypeDef
    (ident: Ident, tparams: List[TypeParam], rhs: TypeTree)
    (val span: Span)
  extends Phrase, Def

  case class Prog(phrases: List[Phrase])(val span: Span) extends Tree:
    // lazy val vals: List[ValDef] =
    //   phrases.collect: phrase =>
    //     phrase match
    //       case vdef: ValDef => vdef
    //
    // lazy val funs: List[FunDef] =
    //   phrases.collect: phrase =>
    //     phrase match
    //       case fdef: FunDef => fdef
    //
    lazy val defs: List[Def] =
      phrases.collect: phrase =>
        phrase match
          case defn: Def => defn
