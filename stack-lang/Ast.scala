import Positions.{ Positioned, Span }


/***********************************************************************
 *
 * Abstract Syntax Tree
 *
 ***********************************************************************/
object Ast:
  sealed abstract class Tree extends Positioned with Product

  sealed abstract class Phrase extends Tree:
    def isDef: Boolean = this.isInstanceOf[Def]

  sealed abstract class Word extends Phrase

  sealed abstract trait RefTree extends Word, TypeTree:
    def name: String

  case class IntLit
    (value: Int)
    (val span: Span)
  extends Word

  case class BoolLit
    (value: Boolean)
    (val span: Span)
  extends Word

  case class StringLit
    (value: String)
    (val span: Span)
  extends Word

  case class Ident
    (name: String)
    (val span: Span)
  extends Word, RefTree

  case class Assign
    (ident: Ident, rhs: Word)
    (val span: Span)
  extends Phrase

  case class If
    (cond: Word, thenp: Word, elsep: Word)
    (val span: Span)
  extends Phrase

  case class While
    (cond: Word, body: Word)
    (val span: Span)
  extends Phrase

  case class Select
    (qual: Word, name: String)
    (val span: Span)
  extends Word, RefTree

  case class RecordLit
    (args: List[NamedArg])
    (val span: Span)
  extends Word

  case class NamedArg
    (ident: Ident, arg: Word)
    (val span: Span)
  extends Tree:
    def name = ident.name

  case class Variant
    (tag: Ident, values: List[Word], typ: TypeTree)
    (val span: Span)
  extends Word

  case class Match
    (scrutinee: Word, cases: List[Case])
    (val span: Span)
  extends Phrase

  case class Case
    (pat: Pattern, body: Word)
    (val span: Span)
  extends Tree

  case class Fence
    (phrase: Phrase)
    (val span: Span)
  extends Word

  case class TypeApply
    (fun: Word, targs: List[TypeTree])
    (val span: Span)
  extends Word

  case class Lambda
    (params: List[Param], body: Word)
    (val span: Span)
  extends Word

  case class Expr
    (words: List[Word])
    (val span: Span)
  extends Word:
    assert(words.nonEmpty)

  case class With
    (expr: Word, args: List[WithArg])
    (val span: Span)
  extends Word:
    assert(args.nonEmpty)

  case class WithArg
    (paramRef: RefTree, rhs: Word)
    (val span: Span)
  extends Tree

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
    (fields: List[Param])
    (val span: Span)
  extends TypeTree

  case class UnionType
    (branches: List[Branch])
    (val span: Span)
  extends TypeTree

  case class Branch
    (tag: Ident, params: List[Param])
    (val span: Span)
  extends Tree:
    def name = tag.name

  case class AppliedType
    (tpeCtor: RefTree, targs: List[TypeTree])
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
    (ident: Ident, typ: TypeTree, rhs: Word, mutable: Boolean)
    (val span: Span)
  extends Phrase, Def

  case class Param
    (ident: Ident, typ: TypeTree)
    (val span: Span)
  extends Def

  case class FunDef
    (ident: Ident, tparams: List[TypeParam], params: List[Param],
        resType: TypeTree, body: Word, preParamCount: Int)
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

  case class Import
    (qualid: RefTree)
    (val span: Span)
  extends Tree:
    qualidWellFormed(qualid)

  case class Namespace
    (qualid: RefTree, imports: List[Import], defs: List[Def], source: String)
    (val span: Span)
  extends Tree:
    qualidWellFormed(qualid)

  def qualidWellFormed(qualid: RefTree): Unit =
    qualid match
      case _: Ident =>

      case Select(qual, name) =>
        qual match
          case id: RefTree =>
            qualidWellFormed(id)

          case _ =>
            throw new Exception("malformed qualid: " + qualid)
