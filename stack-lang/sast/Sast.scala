package sast

import Symbols.*
import Types.*

import ast.Positions.{ Positioned, Span }

/***********************************************************************
 *
 * Semantic Abstract Syntax Trees
 *
 * All names are resolved to symbols according to scoping rules.
 *
 ***********************************************************************/
object Sast:
  sealed abstract class Tree extends Positioned with Product:
    def tpe: Type

  sealed abstract class Word extends Tree:
    def isEmpty: Boolean =
      this match
        case Block(Nil) => true
        case _ => false

    def dropValue: Word =
      assert(this.tpe.isValueType)
      Encoded(this)(VoidType)

    def ensureDropValue: Word =
      if this.tpe.isValueType then dropValue else this

    def show: String = Printing.show(this)

    /** Whether the word can be duplicated as neighbors without affecting program semantics */
    def isIdempotent: Boolean =
      this match
        case _: Literal => true

        case Ident(sym) =>
          // Be more cautious with mutable variables and context parameters
          !sym.is(Flags.Mutable)
          && !sym.isAllOf(Flags.Context | Flags.Param)

        case Select(qual, _) => qual.isIdempotent

        case Encoded(expr) => expr.isIdempotent

        case _ => false

    def refersTo(symbol: Symbol): Boolean =
      // selection is never symblic after normalization, thus no need to handle
      this match
        case Ident(sym) => sym == symbol
        case TypeApply(fun, _) => fun.refersTo(symbol)
        case _ => false

    /** Strip possible encoding */
    def strip: Word =
      this match
        case Encoded(expr) => expr.strip
        case _ => this

  case class Literal
    (constant: Constant)
    (val tpe: Type, val span: Span)
  extends Word

  case class RecordLit
    (args: List[(String, Word)])
    (val tpe: Type, val span: Span)
  extends Word

  case class TaggedLit
    (tagTree: Literal, args: List[Word])
    (val tpe: Type, val span: Span)
  extends Word:
    val tag = tagTree.constant match
      case Constant.String(name) => name
      case c => throw new Exception("Expect string, found = " + c)

  case class Ident
    (symbol: Symbol)
    (val span: Span)
  extends Word:
    assert(!symbol.isType)

    def name: String = symbol.name
    val tpe: Type = TypeRef(symbol)

  case class Select
    (qual: Word, name: String)
    (val tpe: Type, val span: Span)
  extends Word:
    assert(qual.tpe.isValueType)

  /** Assignment to local vars */
  case class Assign
    (ident: Ident, rhs: Word)
    (val span: Span)
  extends Word:
    val symbol = ident.symbol
    def tpe: Type = VoidType

  /** Assignment to object fields */
  case class FieldAssign
    (qual: Word, name: String, rhs: Word)
    (val span: Span)
  extends Word:
    def tpe: Type = VoidType

  case class If
    (cond: Word, thenp: Word, elsep: Word)
    (val tpe: Type, val span: Span)
  extends Word

  case class While
    (cond: Word, body: Word)
    (val span: Span)
  extends Word:
    def tpe: Type = VoidType

  case class Block
    (words: List[Word])
    (val tpe: Type, val span: Span)
  extends Word

  case class With
    (expr: Word, args: List[WithArg])
    (val tpe: Type, val span: Span)
  extends Word:
    assert(args.nonEmpty)

  case class Allow
    (expr: Word, params: List[Ident])
    (val tpe: Type, val span: Span)
  extends Word

  case class WithArg
    (paramRef: Ident, rhs: Word)
    (val span: Span)
  extends Tree:
    def tpe: Type = VoidType

  case class TypeApply
    (fun: Word, targs: List[TypeTree])
    (val tpe: Type, val span: Span)
  extends Word

  case class Apply
    (fun: Word, args: List[Word])
    (val tpe: Type, val span: Span)
  extends Word:
    fun.tpe.asProcType match
      case procType =>
        assert(procType.paramTypes.size == args.size, procType.show + ", " + args)

    def funSymbol: Option[Symbol] =
      fun match
        case Ident(sym)               => Some(sym)
        case TypeApply(Ident(sym), _) => Some(sym)
        case _                        => None

  case class Object
    (self: Symbol, vals: List[ValDef], defs: List[FunDef])
    (val tpe: Type, val span: Span)
  extends Word

  /** Encoding of a type with another type
    *
    * It is also used to explicitly represent dropped values.
    */
  case class Encoded
    (repr: Word)
    (val tpe: Type)
  extends Word:
    def span = repr.span

    def isValueDrop = repr.tpe.isValueType && tpe.isVoidType

  case class TypeTree
    (tpe: Type)
    (val span: Span)
  extends Tree

  //----------------------------------------------------------------------------
  // patterns

  sealed trait Pattern extends Tree:
    val tpe: Type

  case class TypePattern
    (tpt: TypeTree)
  extends Pattern:
    val tpe: Type = tpt.tpe
    val span: Span = tpt.span

  case class WildcardPattern
    ()
    (val tpe: Type, val span: Span)
  extends Pattern

  case class AscribePattern
    (id: Ident, nested: Pattern)
  extends Pattern:
    val tpe = nested.tpe
    val span = id.span | nested.span

  case class ApplyPattern
    (fun: Word, nested: List[Pattern])
    (val tpe: Type, val span: Span)
  extends Pattern:
    fun match
      case Ident(sym) if sym.isPattern =>
      case TypeApply(Ident(sym), _) if sym.isPattern =>
      case _ => throw new Exception("expect a pattern predicate, found = " + fun)

    for pat <- nested do
      pat match
        case AscribePattern(_, _: TypePattern | _: WildcardPattern) =>
        case _ => assert(false, "expect ident, found = " + pat)

  case class TagPattern
    (tagTree: Literal, nested: List[Pattern])
    (val tpe: Type)
  extends Pattern:
    for pat <- nested do
      pat match
        case AscribePattern(_, _: TypePattern | _: WildcardPattern) =>
        case _ => assert(false, "expect ident, found = " + pat)

    val span = if nested.isEmpty then tagTree.span else tagTree.span | nested.last.span

    val tag = tagTree.constant match
      case Constant.String(name) => name
      case c => throw new Exception("Expect string, found = " + c)

  case class Match
    (scrutinee: Word, cases: List[Case])
    (val tpe: Type, val span: Span)
  extends Word

  case class Case
    (pattern: Pattern, body: Word)
    (val span: Span)
  extends Tree:
    def tpe = body.tpe

  //----------------------------------------------------------------------------
  // definitions

  sealed trait Def extends Tree:
    val symbol: Symbol
    val name: String = symbol.name
    val tpe: Type = VoidType

  /** Represents definition of contextual parameters */
  case class ParamDef
    (symbol: Symbol, tpt: TypeTree)
    (val span: Span)
  extends Def

  case class ValDef
    (symbol: Symbol, rhs: Word)
    (val span: Span)
  extends Word, Def:
    val isMutable = symbol.isMutable

  case class TypeDef
    (symbol: Symbol)
    (val span: Span)
  extends Word, Def

  /** Represents a named function or method definition
    *
    * @param locals contains a list of local value symbols (excluding params)
    */
  case class FunDef
    (symbol: Symbol, tparams: List[Symbol], params: List[Symbol], resultType: TypeTree, body: Word)
    (val span: Span)
  extends Word, Def:
    private lazy val census: (List[Symbol], List[Symbol]) =
      SastOps.variableCensus(this)

    lazy val locals: List[Symbol] = census._1
    lazy val freeVariables: List[Symbol] = census._2

    def procType: ProcType = symbol.info.asProcType

    def receives: Option[List[Symbol]] = procType.receives

    def methodReceives: List[Symbol] = receives.getOrElse(Nil)

  /** Represents a pattern definition */
  case class PatDef
    (symbol: Symbol, tparams: List[Symbol], params: List[Symbol], resultType: TypeTree, body: Pattern)
    (val span: Span)
  extends Word, Def:
    def procType: ProcType = symbol.info.asProcType

  case class Namespace
    (symbol: Symbol, imports: List[Symbol], defs: List[Def])
    (val span: Span)
  extends Positioned:
    def info: NameTableInfo = symbol.info.as[NameTableInfo]

    val fullName: String = symbol.fullName

    def mainSymbol: Option[Symbol] =
      val funs = defs.filter(defn => defn.symbol.isFunction && defn.symbol.name == "main")
      funs.map(_.symbol).headOption

    def show: String = Printing.show(this)

  //----------------------------------------------------------------------------
  // helpers

  def StringLit(s: String)(tp: Type, span: Span) =
    Literal(Constant.String(s))(tp, span)

  def IntLit(n: Int)(tp: Type, span: Span) =
    Literal(Constant.Int(n))(tp, span)

  def BoolLit(b: Boolean)(tp: Type, span: Span) =
    Literal(Constant.Bool(b))(tp, span)
