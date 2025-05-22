package ast

import Positions.{ Positioned, Span }
import common.KeyProps

/***********************************************************************
 *
 * Abstract Syntax Tree
 *
 ***********************************************************************/
object Ast:
  sealed abstract class Tree extends Product, Positioned, KeyProps.Container

  enum Modifier extends Tree:
    case Auto()(val span: Span)
    case Private()(val span: Span)

    def show: String = this match
      case Auto()    => "auto"
      case Private() => "private"

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

  case class SeqLit
    (words: List[Word])
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

  /** A dotless infix method call formed by expression typer
    *
    * We could use Apply, but a special class produces better error messages.
    */
  case class DotlessCall
    (obj: Word, method: Ident, arg: Word)
    (val span: Span)
  extends Word

  /** An infix call formed by expression typer
    *
    * We could use Apply, but a special class produces better error messages.
    */
  case class InfixCall
    (preArgs: List[Word], fun: Word, postArgs: List[Word])
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

  case class ExprType
    (types: List[TypeTree])
    (val span: Span)
  extends TypeTree

  case class TagType
    (tag: Ident, params: List[Param])
    (val span: Span)
  extends TypeTree:
    def name = tag.name

  case class AppliedType
    (tpeCtor: TypeTree, targs: List[TypeTree])
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
    private var _modifiers: List[Modifier] | Null = null

    def name: String

    def modifiers: List[Modifier] =
      if _modifiers == null then Nil else _modifiers

    def withMods(mods: List[Modifier]): this.type =
      assert(_modifiers == null, "already set, modifiers = " + _modifiers + ", mods = " + mods)
      _modifiers = mods
      this

  case class ValDef
    (ident: Ident, tpt: TypeTree, rhs: Word, mutable: Boolean)
    (val span: Span)
  extends Word, Def:
    def name: String = ident.name

  /** Context parameter definition */
  case class ParamDef
    (ident: Ident, tpt: TypeTree, default: Option[Word])
    (val span: Span)
  extends Def:
    def name: String = ident.name


  case class Section
    (ident: Ident, defs: List[Def])
    (val span: Span)
  extends Def:
    def name: String = ident.name

  /** Representation of functions and methods
    *
    * TODO: a keyword `capture` can be introduced for methods to make capture
    * explicit.
    */
  case class FunDef
    (ident: Ident, tparams: List[TypeParam], params: List[Param], autos: List[Param],
        resultType: TypeTree, receives: Option[List[RefTree]], body: Word,
        preParamCount: Int)
    (val span: Span)
  extends Word, Def:
    for
      params <- receives
      param <- params
    do
      assert(isQualid(param), param)

    def name: String = ident.name

  case class ClassDef
    (ident: Ident, tparams: List[TypeParam], params: List[Param], members: List[ValDef | FunDef])
    (val span: Span)
  extends Def:
    def name: String = ident.name

  /** Representation of a pattern definition */
  case class PatDef
    (ident: Ident, tparams: List[TypeParam], params: List[Param],
      resultType: TypeTree, cases: List[Case], preParamCount: Int)
    (val span: Span)
  extends Word, Def:
    assert(cases.forall(c => isPattern(c.pat)), "Ill-formed pattern tree: " + cases)

    def name: String = ident.name

  case class DataDef
    (ident: Ident, tparams: List[TypeParam], params: List[Param])
    (val span: Span)
  extends Def:
    def name: String = ident.name

  case class EnumDef
    (ident: Ident, tparams: List[TypeParam], branches: List[TagType])
    (val span: Span)
  extends Def:
    def name: String = ident.name

  case class Param
    (ident: Ident, tpt: TypeTree)
    (val span: Span)
  extends Tree:
    def name = ident.name

  case class TypeParam
    (ident: Ident, bound: TypeTree)
    (val span: Span)
  extends Tree:
    def name = ident.name

  /** A type definition
    *
    * @param isBound whether the rhs is a type bound
    */
  case class TypeDef
    (ident: Ident, tparams: List[TypeParam], rhs: TypeTree, isBound: Boolean, preParamCount: Int)
    (val span: Span)
  extends Word, Def:
    def name: String = ident.name

  case class Import
    (qualid: RefTree)
    (val span: Span)
  extends Tree:
    assert(isQualid(qualid), "malformed qualid: " + qualid)

  case class AliasDef
    (qualid: RefTree)
    (val span: Span)
  extends Def:
    assert(isQualid(qualid), "malformed qualid: " + qualid)

    def name = qualid.name

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
      case _: Tag | _: Ident | _: StringLit | _: IntLit | _: CharLit | _: BoolLit => true

      case TypeAscribe(_: Ident, _) => true

      case Apply(_: Tag | _: Ident, args) if args.nonEmpty =>
        args.forall(isPattern)

      case Expr(words) if words.nonEmpty =>
        words.forall(isPattern)

      case With(expr, bindings) =>
        isPattern(expr) && bindings.forall(_.paramRef.isInstanceOf[Ident])

      case If(cond, thenp, Block(Nil)) =>
        isPattern(thenp)

      case Assign(_: Ident, rhs) => isPattern(rhs)

      case SeqLit(words) => words.forall(isPattern)

      case _ =>
        false
