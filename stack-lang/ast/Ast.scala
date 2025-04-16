package ast

import Positions.{ Positioned, Span }


/***********************************************************************
 *
 * Abstract Syntax Tree
 *
 ***********************************************************************/
object Ast:
  sealed abstract class Tree extends Positioned with Product

  sealed abstract class Word extends Tree:
    def show: String = Printing.show(this)

    def isEmptyBlock: Boolean =
      this match
        case Block(Nil) => true
        case _ => false

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

  case class CharLit
    (value: Char)
    (val span: Span)
  extends Word

  case class StringLit
    (value: String)
    (val span: Span)
  extends Word

  case class Ident
    (name: String)
    (val span: Span)
  extends Word, RefTree:
    assert(name.nonEmpty, "name is empty")

    def isCapitalized: Boolean = Character.isUpperCase(name.charAt(0))

  case class Apply
    (fun: Word, args: List[Word])
    (val span: Span)
  extends Word

  case class Assign
    (lhs: RefTree, rhs: Word)
    (val span: Span)
  extends Word

  case class If
    (cond: Word, thenp: Word, elsep: Word)
    (val span: Span)
  extends Word

  case class While
    (cond: Word, body: Word)
    (val span: Span)
  extends Word

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

  case class Tag
    (name: Ident)
    (val span: Span)
  extends Word

  case class TypeAscribe
    (expr: Word, tpt: TypeTree)
    (val span: Span)
  extends Word

  case class Match
    (scrutinee: Word, cases: List[Case])
    (val span: Span)
  extends Word

  case class Case
    (pat: Word, body: Word)
    (val span: Span)
  extends Tree:
    assert(isPattern(pat), "Ill-formed pattern tree: " + pat)

  case class Fence
    (phrase: Word)
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

  case class Allow
    (expr: Word, params: List[RefTree])
    (val span: Span)
  extends Word:
    for param <- params do assert(isQualid(param), param)

  case class WithArg
    (paramRef: RefTree, rhs: Word)
    (val span: Span)
  extends Tree:
    assert(isQualid(paramRef))

  case class Block
    (phrases: List[Word])
    (val span: Span)
  extends Word

  case class Object
    (members: List[ValDef | FunDef])
    (val span: Span)
  extends Word

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
    (branches: List[TypeTree])
    (val span: Span)
  extends TypeTree

  case class TagType
    (tag: Ident, params: List[Param])
    (val span: Span)
  extends TypeTree:
    def name = tag.name

  case class AppliedType
    (tpeCtor: RefTree, targs: List[TypeTree])
    (val span: Span)
  extends TypeTree:
    assert(targs.nonEmpty)

  case class FunctionType
    (paramTypes: List[TypeTree], resultType: TypeTree, receives: List[RefTree])
    (val span: Span)
  extends TypeTree:
    for param <- receives do assert(isQualid(param), param)

  case class ObjectType
    (members: List[ValDef | FunDef])
    (val span: Span)
  extends TypeTree

  //-------------------------- definitions -------------------------------------

  sealed trait Def extends Tree:
    val ident: Ident
    val name: String = ident.name

  case class ValDef
    (ident: Ident, typ: TypeTree, rhs: Word, mutable: Boolean)
    (val span: Span)
  extends Word, Def

  /** Context parameter definition */
  case class ParamDef
    (ident: Ident, typ: TypeTree, default: Option[Word])
    (val span: Span)
  extends Def

  /** Representation of functions and methods
    *
    * TODO: a keyword `capture` can be introduced for methods to make capture
    * explicit.
    */
  case class FunDef
    (ident: Ident, tparams: List[TypeParam], params: List[Param],
        resultType: TypeTree, receives: Option[List[RefTree]], body: Word,
        preParamCount: Int)
    (val span: Span)
  extends Word, Def:
    for
      params <- receives
      param <- params
    do
      assert(isQualid(param), param)

  /** Representation of a pattern definition */
  case class PatDef
    (ident: Ident, tparams: List[TypeParam], params: List[Param], resultType: TypeTree, body: Word)
    (val span: Span)
  extends Word, Def:
    assert(isPattern(body), "Ill-formed pattern tree: " + body)

  case class Param
    (ident: Ident, typ: TypeTree)
    (val span: Span)
  extends Tree:
    def name = ident.name

  case class TypeParam
    (ident: Ident, bound: TypeTree)
    (val span: Span)
  extends Tree:
    def name = ident.name

  case class TypeDef
    (ident: Ident, tparams: List[TypeParam], rhs: TypeTree, isBound: Boolean)
    (val span: Span)
  extends Word, Def

  case class Import
    (qualid: RefTree)
    (val span: Span)
  extends Tree:
    assert(isQualid(qualid), "malformed qualid: " + qualid)

  case class Namespace
    (qualid: RefTree, imports: List[Import], defs: List[Def], source: String)
    (val span: Span)
  extends Tree:
    assert(isQualid(qualid), "malformed qualid: " + qualid)

    def show: String = Printing.show(this)

  def isQualid(word: Word): Boolean =
    word match
      case _: Ident => true

      case Select(qual, name) => isQualid(qual)

      case _ => false

  def isPattern(pat: Word): Boolean =
    pat match
      case _: Tag | _: Ident => true

      case TypeAscribe(_: Ident, _) => true

      case Apply(_: Tag | _: Ident, args) if args.nonEmpty =>
        args.forall(isPattern)

      case Expr(words) if words.nonEmpty =>
        words.forall(isPattern)

      case _ =>
        false
