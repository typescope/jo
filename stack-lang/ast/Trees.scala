package ast

import Positions.{ Positioned, Span }
import common.KeyProps

/***********************************************************************
 *
 * Abstract Syntax Tree
 *
 ***********************************************************************/
object Trees:
  sealed abstract class Tree extends Product, Positioned, KeyProps.Container

  /** Base trait for all patterns.
    *
    * RefTree extends Pattern so identifiers and qualified names can be used as patterns.
    * Other patterns are represented by dedicated pattern case classes.
    */
  sealed trait Pattern extends Tree

  enum Modifier extends Tree:
    case Defer()(val span: Span)
    case Private(qualifier: Option[Ident])(val span: Span)

    def show: String = this match
      case Defer() => "defer"

      case Private(qualifier)  =>
        qualifier match
          case Some(id) =>"private[" + id.name + "]"
          case None => "private"

    def isPrivate: Boolean = this.isInstanceOf[Private]

    def isDefer: Boolean = this.isInstanceOf[Defer]

  sealed abstract class Word extends Tree:
    def show: String = Printing.show(this)

    def isEmptyBlock: Boolean =
      this match
        case Block(Nil) => true
        case _ => false

  sealed abstract trait RefTree extends Word, TypeTree, Pattern:
    def name: String

  case class IntLit
    (value: String, isHex: Boolean)
    (val span: Span)
  extends Word

  case class FloatLit
    (value: String)
    (val span: Span)
  extends Word

  case class BoolLit
    (value: Boolean)
    (val span: Span)
  extends Word

  case class CharLit
    (value: Int)
    (val span: Span)
  extends Word

  case class StringLit
    (value: String)
    (val span: Span)
  extends Word

  case class This
    (val span: Span)
  extends Word

  case class InterpolatedString
    (parts: List[Word])
    (val span: Span)
  extends Word

  case class ListLit
    (words: List[Word])
    (val span: Span)
  extends Word

  case class MapPair
    (key: Word, value: Word)
    (val span: Span)
  extends Word

  case class MapLit
    (words: List[Word])
    (val span: Span)
  extends Word

  case class Ident
    (name: String)
    (val span: Span)
  extends Word, RefTree:
    assert(name.nonEmpty, "name is empty")

    def isCapitalized: Boolean = Naming.isCapitalized(name)

  case class Apply
    (fun: Word, args: List[Word])
    (val span: Span)
  extends Word

  case class New
    (classType: TypeTree, args: List[Word])
    (val span: Span)
  extends Word

  /** Represents the expression e[i, j, ..] */
  case class BracketApply
    (subject: Word, args: List[Word])
    (val span: Span)
  extends Word

  /** An infix operator call formed from expressions
    *
    * We could use Apply, but a special class produces better error messages.
    */
  case class InfixOperatorCall
    (lhs: Word, op: Ident, rhs: Word)
    (val span: Span)
  extends Word

  /** A prefix operator call formed from expressions
    *
    * We could use Apply, but a special class produces better error messages.
    */
  case class PrefixOperatorCall
    (op: Ident, arg: Word)
    (val span: Span)
  extends Word

  /** An infix call formed from expressions
    *
    * InfixOperatorCall and PrefixOperatorCall might desugar to InfixCall by
    * flow typer.
    *
    * We could use Apply, but a special class produces better error messages.
    */
  case class InfixCall
    (preArgs: List[Word], fun: Word, postArgs: List[Word])
    (val span: Span)
  extends Word

  case class Assign
    (lhs: Word, rhs: Word)
    (val span: Span)
  extends Word:
    assert(lhs.isInstanceOf[RefTree] || lhs.isInstanceOf[BracketApply], "Invalid lhs for assign, lhs = " + lhs)

  case class If
    (cond: Word, thenp: Word, elsep: Word)
    (val span: Span)
  extends Word

  case class While
    (cond: Word, body: Word)
    (val span: Span)
  extends Word

  case class For
    (pattern: Pattern, iter: Word, cond: Option[Word], body: Word)
    (val span: Span)
  extends Word

  case class Select
    (qual: Word, name: String)
    (val span: Span)
  extends Word, RefTree

  case class IsExpr
    (scrutinee: Word, pattern: Pattern)
    (val span: Span)
  extends Word

  case class NamedArg
    (ident: Ident, arg: Word)
    (val span: Span)
  extends Tree:
    def name = ident.name

  case class TypeAscribe
    (expr: Word, tpt: TypeTree)
    (val span: Span)
  extends Word

  case class Match
    (scrutinee: Word, cases: List[Case])
    (val span: Span)
  extends Word

  case class Case
    (pat: Pattern, body: Word)
    (val span: Span)
  extends Tree

  case class CaseDef
    (pat: Pattern, rhs: Word)
    (val span: Span)
  extends Word

  /** Literal pattern: 42, true, 'a', "hello" */
  case class LiteralPattern
    (value: Word)
  extends Pattern:
    assert(value.isInstanceOf[IntLit] || value.isInstanceOf[FloatLit] ||
           value.isInstanceOf[BoolLit] || value.isInstanceOf[CharLit] ||
           value.isInstanceOf[StringLit],
           "LiteralPattern must contain a literal value")

    def span: Span = value.span

  /** Type pattern: x: Type */
  case class TypePattern
    (id: Ident, tpt: TypeTree)
    (val span: Span)
  extends Pattern

  /** Bind pattern: x @ pattern */
  case class BindPattern
    (id: Ident, pattern: Pattern)
    (val span: Span)
  extends Pattern

  /** Apply pattern: Constructor(args) */
  case class ApplyPattern
    (fun: RefTree, args: List[Pattern])
    (val span: Span)
  extends Pattern:
    assert(isQualid(fun), "ApplyPattern constructor must be a valid qualid: " + fun)

  /** Sequence pattern: [pattern1, pattern2, ...] */
  case class SequencePattern
    (patterns: List[Pattern])
    (val span: Span)
  extends Pattern

  /** Guard pattern: pattern if condition */
  case class GuardPattern
    (pattern: Pattern, guard: Word)
    (val span: Span)
  extends Pattern

  /** Assignment pattern: pattern with x = expr, y = expr2, ... */
  case class AssignPattern
    (pattern: Pattern, assignments: List[(Ident, Word)])
    (val span: Span)
  extends Pattern:
    assert(assignments.nonEmpty, "empty assignments")

  /** Expression pattern: p1 p2 p3 (for infix operators like | and &) */
  case class ExprPattern
    (patterns: List[Pattern])
    (val span: Span)
  extends Pattern:
    assert(patterns.nonEmpty, "ExprPattern must have at least one pattern")

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

  case class HavingBinding
    (tpe: TypeTree, value: Word)
    (val span: Span)
  extends Tree

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

  case class DuckType
    (tpe: TypeTree, adapters: List[ParamAdapter])
    (val span: Span)
  extends TypeTree:
    assert(adapters.nonEmpty, "duck type must have at least one adapter")


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

    def copy(
        ident: Ident = this.ident,
        tpt: TypeTree = this.tpt,
        rhs: Word = this.rhs,
        mutable: Boolean = this.mutable)
        (span: Span)
    : ValDef =
      ValDef(ident, tpt, rhs, mutable)(span).withMods(this.modifiers).copyProps(this)

  case class AutoDef
    (ident: Ident, tpt: TypeTree, rhs: Word)
    (val span: Span)
  extends Word, Def:
    def name: String = ident.name

  /** Context parameter definition */
  case class ParamDef
    (ident: Ident, tpt: TypeTree, default: Option[Word])
    (val span: Span)
  extends Def:
    def name: String = ident.name

    def copy(
        ident: Ident = this.ident,
        tpt: TypeTree = this.tpt,
        default: Option[Word] = this.default)
        (span: Span)
    : ParamDef =
      ParamDef(ident, tpt, default)(span).withMods(this.modifiers).copyProps(this)

  case class Section
    (ident: Ident, defs: List[Def])
    (val span: Span)
  extends Def:
    def name: String = ident.name

    def copy(
        ident: Ident = this.ident,
        defs: List[Def] = this.defs)
        (span: Span)
    : Section =
      Section(ident, defs)(span).withMods(this.modifiers).copyProps(this)

  /** Representation of functions and methods
    *
    * TODO: a keyword `capture` can be introduced for methods to make capture
    * explicit.
    */
  case class FunDef
    (ident: Ident, tparams: List[TypeParam], params: List[Param], autos: List[Auto],
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

    def copy(
        ident: Ident = this.ident,
        tparams: List[TypeParam] = this.tparams,
        params: List[Param] = this.params,
        autos: List[Auto] = this.autos,
        resultType: TypeTree = this.resultType,
        receives: Option[List[RefTree]] = this.receives,
        body: Word = this.body,
        preParamCount: Int = this.preParamCount)
        (span: Span)
    : FunDef =

      FunDef(ident, tparams, params, autos, resultType, receives, body, preParamCount)(span).withMods(this.modifiers).copyProps(this)

  case class ClassDef
    (ident: Ident, tparams: List[TypeParam], params: List[Param], views: List[ViewDecl], vals: List[ValDef], funs: List[FunDef])
    (val span: Span)
  extends Def:
    def name: String = ident.name

    def copy(
        ident: Ident = this.ident,
        tparams: List[TypeParam] = this.tparams,
        params: List[Param] = this.params,
        views: List[ViewDecl] = this.views,
        vals: List[ValDef] = this.vals,
        funs: List[FunDef] = this.funs)
        (span: Span)
    : ClassDef =
      ClassDef(ident, tparams, params, views, vals, funs)(span).withMods(this.modifiers).copyProps(this)

  /** Representation of an interface definition
    *
    * An interface defines a behavioral contract with method signatures.
    */
  case class InterfaceDef
    (ident: Ident, tparams: List[TypeParam], members: List[FunDef])
    (val span: Span)
  extends Def:
    def name: String = ident.name

    def copy(
        ident: Ident = this.ident,
        tparams: List[TypeParam] = this.tparams,
        members: List[FunDef] = this.members)
        (span: Span)
    : InterfaceDef =
      InterfaceDef(ident, tparams, members)(span).withMods(this.modifiers).copyProps(this)

  /** Representation of an object definition
    *
    * An object defines a singleton value with associated behavior.
    * Objects cannot have type parameters, constructor parameters, or fields.
    * They can only have methods (funs) and intrinsic views.
    */
  case class ObjectDef
    (ident: Ident, views: List[ViewDecl], funs: List[FunDef])
    (val span: Span)
  extends Def:
    def name: String = ident.name

    def copy(
        ident: Ident = this.ident,
        views: List[ViewDecl] = this.views,
        funs: List[FunDef] = this.funs)
        (span: Span)
    : ObjectDef =
      ObjectDef(ident, views, funs)(span).withMods(this.modifiers).copyProps(this)

  /** Representation of a view declaration in a class
    *
    * `view I` declares that the class implements interface I
    * `view I = expr` declares a delegated view where expr provides the implementation
    */
  case class ViewDecl
    (tpe: TypeTree, rhs: Option[Word])
    (val span: Span)
  extends Tree

  /** Representation of a pattern definition */
  case class PatDef
    (ident: Ident, tparams: List[TypeParam], params: List[Param],
      resultType: TypeTree, cases: List[Case], preParamCount: Int)
    (val span: Span)
  extends Word, Def:
    def name: String = ident.name

    def copy(
        ident: Ident = this.ident,
        tparams: List[TypeParam] = this.tparams,
        params: List[Param] = this.params,
        resultType: TypeTree = this.resultType,
        cases: List[Case] = this.cases,
        preParamCount: Int = this.preParamCount)
        (span: Span)
    : PatDef =
      PatDef(ident, tparams, params, resultType, cases, preParamCount)(span).withMods(this.modifiers).copyProps(this)

  case class UnionDef
    (ident: Ident, tparams: List[TypeParam], branches: List[ClassDef])
    (val span: Span)
  extends Def:
    def name: String = ident.name

    def copy(
        ident: Ident = this.ident,
        tparams: List[TypeParam] = this.tparams,
        branches: List[ClassDef] = this.branches)
        (span: Span)
    : UnionDef =
      UnionDef(ident, tparams, branches)(span).withMods(this.modifiers).copyProps(this)

  enum ParamAdapter extends Tree:
    case Function(ref: RefTree)(val span: Span)
    case Member(name: String)(val span: Span)

  enum AutoCandidate extends Tree:
    case Value(ref: RefTree)(val span: Span)
    case Member(tpe: TypeTree, name: String)(val span: Span)

  case class Param
    (ident: Ident, tpt: TypeTree)
    (val span: Span)
  extends Tree:
    def name = ident.name

  case class Auto
    (ident: Ident, tpt: TypeTree, candidates: List[AutoCandidate])
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

    def copy(
        ident: Ident = this.ident,
        tparams: List[TypeParam] = this.tparams,
        rhs: TypeTree = this.rhs,
        isBound: Boolean = this.isBound,
        preParamCount: Int = this.preParamCount)
        (span: Span)
    : TypeDef =
      TypeDef(ident, tparams, rhs, isBound, preParamCount)(span).withMods(this.modifiers).copyProps(this)

  case class Import
    (qualid: RefTree)
    (val span: Span)
  extends Tree:
    assert(isQualid(qualid), "malformed qualid: " + qualid)

  enum AliasKind:
    case Def, Param, Pattern

    override def toString = this match
      case AliasKind.Def     => "def"
      case AliasKind.Param   => "param"
      case AliasKind.Pattern => "pattern"

  case class AliasDef
    (ident: Ident, kind: AliasKind, qualid: RefTree)
    (val span: Span)
  extends Def:
    assert(isQualid(qualid), "malformed qualid: " + qualid)

    def name: String = ident.name

    def copy(
        ident: Ident = this.ident,
        kind: AliasKind = this.kind,
        qualid: RefTree = this.qualid)
        (span: Span)
    : AliasDef =
      AliasDef(ident, kind, qualid)(span).withMods(this.modifiers).copyProps(this)

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
