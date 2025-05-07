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

    def dropValue(using Definitions): Word =
      assert(this.tpe.isValueType)
      Encoded(this)(VoidType)

    def ensureDropValue(using Definitions): Word =
      if this.tpe.isValueType then dropValue else this

    def dropIfVoid(target: Type)(using Definitions): Word =
      if target.isVoidType then dropValue else this

    def show(using Definitions): String = Printing.show(this)

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
    (using Definitions)
  extends Word:
    assert(qual.tpe.isValueType, "Select node must have value prefix, found = " + qual.tpe.show)

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
    (using Definitions)
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
    (val tpe: Type, val span: Span)
  extends Word:
    def isValueDrop(using Definitions) = repr.tpe.isValueType && tpe.isVoidType

  object Encoded:
    def apply(repr: Word)(tpe: Type): Encoded = apply(repr)(tpe, repr.span)

  case class TypeTree
    (tpe: Type)
    (val span: Span)
  extends Tree

  //----------------------------------------------------------------------------
  // patterns

  sealed trait Pattern extends Tree:
    val tpe: Type

    def show(using Definitions): String = Printing.show(this)

    def isWildcard: Boolean =
      this match
        case _: WildcardPattern => true
        case AscribePattern(_, nested) => nested.isWildcard
        case _ => false

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

  case class OrPattern
    (lhs: Pattern, rhs: Pattern)
  extends Pattern:
    val tpe = lhs.tpe
    val span = lhs.span | rhs.span

  case class ApplyPattern
    (fun: Word, nested: List[Pattern])
    (val tpe: Type, val span: Span)
  extends Pattern:
    val symbol =
      fun match
        case Ident(sym) if sym.isPattern => sym
        case TypeApply(Ident(sym), _) if sym.isPattern => sym
        case _ => throw new Exception("expect a pattern predicate, found = " + fun)

  case class TagPattern
    (tagTree: Literal, nested: List[Pattern])
    (val tpe: Type)
  extends Pattern:
    val span = if nested.isEmpty then tagTree.span else tagTree.span | nested.last.span

    val tag = tagTree.constant match
      case Constant.String(name) => name
      case c => throw new Exception("Expect string, found = " + c)

  case class ValuePattern
    (value: Word)
  extends Pattern:
    val tpe = value.tpe
    val span = value.span

  case class GuardPattern
    (pattern: Pattern, guard: Word)
  extends Pattern:
    val tpe = pattern.tpe
    val span = pattern.span | guard.span

  case class TermBindingPattern
    (pattern: Pattern, bindings: List[Assign])
  extends Pattern:
    val tpe = pattern.tpe
    val span = pattern.span | bindings.last.span

  case class SeqPattern
    (patterns: List[RegexPattern])
    (val tpe: Type, val span: Span)
  extends Pattern:
    /** The distance from the end of a pattern to the end of sequence */
    val distanceToEnd: Seq[SeqPattern.Size] = SeqPattern.computeDistanceToEnd(patterns)

    val totalSize: SeqPattern.Size =
      if patterns.isEmpty then SeqPattern.Size.Exact(0)
      else distanceToEnd(0) + patterns(0).size

    def apply(i: Int): RegexPattern = patterns(i)

    def patternCount: Int = patterns.size

  object SeqPattern:
    enum Size:
      case GreatEq(n: Int)
      case Exact(n: Int)

      def isExact: Boolean = this.isInstanceOf[Exact]

      def isDisjoint(that: Size): Boolean =
        this match
          case GreatEq(m) =>
            that match
              case GreatEq(n) => false
              case Exact(n)   => n < m

          case Exact(m) =>
            that match
              case GreatEq(n) => m < n
              case Exact(n)   => m != n

      def +(that: Size): Size =
        this match
          case GreatEq(m) =>
            that match
              case GreatEq(n) => GreatEq(m + n)
              case Exact(n)   => GreatEq(m + n)

          case Exact(m) =>
            that match
              case GreatEq(n) => GreatEq(m + n)
              case Exact(n)   => Exact(m + n)

      def -(that: Size): List[Size] =
        this match
          case GreatEq(m) =>
            that match
              case GreatEq(n) =>
                if n <= m then Nil
                else (m until n).toList.map(Exact.apply)

              case Exact(n) =>
                if n < m then this :: Nil
                else if n == m then GreatEq(m + 1) :: Nil
                else GreatEq(n + 1) :: (m until n).toList.map(Exact.apply)

          case Exact(m) =>
            that match
              case GreatEq(n) =>
                if n <= m then Nil else this :: Nil

              case Exact(n) =>
                if m == n then Nil else this :: Nil

      override def toString: String =
        this match
          case GreatEq(n) => "size >= " + n
          case Exact(n)   => "size = " + n

    def computeDistanceToEnd(patterns: Seq[RegexPattern]): Seq[Size] =
      val distanceToEnd = new Array[Size](patterns.size)
      if patterns.nonEmpty then
        var i = patterns.size - 1
        distanceToEnd(i) = Size.Exact(0)

        while i > 0 do
          i = i - 1
          distanceToEnd(i) = distanceToEnd(i + 1) + patterns(i + 1).size
        end while
      end if
      distanceToEnd

  sealed trait RegexPattern extends Tree:
    val tpe: Type

    def show(using Definitions): String = Printing.show(this)

    def headPattern: Pattern =
      this match
        case AtomPattern(pat) => pat
        case SkipToPattern(pat) => pat
        case StarPattern(pat) => pat

    /** The number of items the pattern consumes when the match is successful */
    def size: SeqPattern.Size =
      this match
        case AtomPattern(pat)   => SeqPattern.Size.Exact(1)
        case SkipToPattern(pat) => SeqPattern.Size.GreatEq(1)
        case StarPattern(pat)   => SeqPattern.Size.GreatEq(0)

  case class AtomPattern
    (pattern: Pattern)
  extends RegexPattern:
    val tpe: Type = pattern.tpe
    val span: Span = pattern.span

  case class SkipToPattern
    (pattern: Pattern)
    (val tpe: Type, val span: Span)
  extends RegexPattern

  /** Represent a * pattern
    *
    * For each variable bound in the inner pattern, a variable is introduced to
    * accumulate the inner-bound results.
    *
    * @param bindings Pairs of (outerX, innerX)
    */
  case class StarPattern
    (pattern: Pattern)
    (val tpe: Type, val span: Span, val bindings: List[(Symbol, Symbol)])
  extends RegexPattern:
    for (outer, inner) <- bindings do
      assert(outer.name == inner.name, s"outer = $outer, inner = inner")

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

  /** Represents a named function or method definition */
  case class FunDef
    (symbol: Symbol, tparams: List[Symbol], params: List[Symbol], resultType: TypeTree, body: Word)
    (val span: Span)
  extends Word, Def:
    private var censusCache: (List[Symbol], List[Symbol]) | Null = null

    def census(using Definitions): (List[Symbol], List[Symbol]) =
      if censusCache == null then
        censusCache = SastOps.variableCensus(this)
        censusCache.nn
      else
        censusCache.nn

    /** contains a list of local value symbols (excluding params) */
    def locals(using Definitions): List[Symbol] = census._1
    def freeVariables(using Definitions): List[Symbol] = census._2

    def procType(using Definitions): ProcType = symbol.info.asProcType

    def receives(using Definitions): Option[List[Symbol]] = procType.receives

    def methodReceives(using Definitions): List[Symbol] = receives.getOrElse(Nil)

  /** Represents a pattern definition */
  case class PatDef
    (symbol: Symbol, tparams: List[Symbol], params: List[Symbol], resultType: TypeTree, body: Pattern)
    (val span: Span)
  extends Word, Def:
    def procType(using Definitions): ProcType = symbol.info.asProcType

  case class Section
    (symbol: Symbol, defs: List[Def])
    (val span: Span)
  extends Def:
    def info(using Definitions): NameTableInfo = symbol.info.as[NameTableInfo]

    def allFuns: List[FunDef] =
      defs.flatMap:
        case fdef: FunDef => fdef :: Nil
        case sec: Section => sec.allFuns
        case _ => Nil

  case class Namespace
    (symbol: Symbol, imports: List[Symbol], defs: List[Def])
    (val span: Span)
  extends Positioned:
    def info(using Definitions): NameTableInfo = symbol.info.as[NameTableInfo]

    def fullName(using Definitions): String = symbol.fullName

    def allFuns: List[FunDef] =
      defs.flatMap:
        case fdef: FunDef => fdef :: Nil
        case sec: Section => sec.allFuns
        case _ => Nil

    def mainSymbol: Option[Symbol] =
      val funs = defs.filter(defn => defn.symbol.isFunction && defn.symbol.name == "main")
      funs.map(_.symbol).headOption

    def show(using Definitions): String = Printing.show(this)


  //----------------------------------------------------------------------------
  // helpers

  def StringLit(s: String)(span: Span)(using defn: Definitions) =
    Literal(Constant.String(s))(defn.StringType, span)

  def IntLit(n: Int)(span: Span)(using defn: Definitions) =
    Literal(Constant.Int(n))(defn.IntType, span)

  def BoolLit(b: Boolean)(span: Span)(using defn: Definitions) =
    Literal(Constant.Bool(b))(defn.BoolType, span)

  def all(cond: Word, conds: Word*)(using defn: Definitions): Word =
    conds.foldLeft(cond): (acc, cond) =>
      Ident(defn.Predef_both)(cond.span).appliedTo(acc, cond)

  extension (word: Word)

    def select(name: String)(using Definitions): Word =
      val memberType = word.tpe.termMember(name)
      Select(word, name)(memberType, word.span)

    def appliedTo(args: Word*)(using Definitions): Word =
      val procType = word.tpe.asProcType

      assert(procType.paramCount == args.size)
      assert(procType.tparams.isEmpty)

      val args2 =
        for (arg, paramType) <- args.zip(procType.paramTypes)
        yield SastOps.adapt(arg, paramType)

      val span = if args.isEmpty then word.span else word.span | args.last.span
      Apply(word, args2.toList)(procType.resultType, span)

    def appliedToTypes(targs: Type*)(using Definitions): Word =
      val procType = word.tpe.asProcType
      val targList = targs.toList
      val tpe = procType.instantiate(targList)
      TypeApply(word, targList.map(targ => TypeTree(targ)(word.span)))(tpe, word.span)

    def encodedAs(tpe: Type): Word = Encoded(word)(tpe)

    def isEqualTo(rhs: Word)(using defn: Definitions): Word =
      Ident(defn.Predef_eql)(word.span).appliedTo(word, rhs)
