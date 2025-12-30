package sast

import Symbols.*
import Types.*

import ast.Positions.{ Positioned, Span, DerivedSpan, Source }

/***********************************************************************
 *
 * Semantic Abstract Syntax Trees
 *
 * All names are resolved to symbols according to scoping rules.
 *
 ***********************************************************************/
object Trees:
  sealed abstract class Tree extends Positioned with Product

  sealed abstract class Word extends Tree:
    def tpe: Type

    def isEmpty: Boolean =
      this match
        case Block(Nil) => true
        case _ => false

    def dropValue(using Definitions): Word =
      assert(this.tpe.isValueType, this.tpe)
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
          && !sym.isAllOf(Flags.Context)

        case Select(qual, _) => qual.isIdempotent

        case Encoded(expr) => expr.isIdempotent

        case _ => false

    /** Whether the tree is a call or an identifier to a symbol */
    def refers(symbol: Symbol)(using Definitions): Boolean =
      // selection is never symblic after normalization, thus no need to handle
      this match
        case Ident(sym) => sym == symbol
        case Apply(fun, _, _) => fun.refers(symbol)
        case TypeApply(fun, _) => fun.refers(symbol)
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
    (val span: Span)
  extends Word:
    val tpe = RecordType(args.map((n, w) => NamedInfo(n, w.tpe)))

  case class Ident
    (symbol: Symbol)
    (val span: Span)
  extends Word:
    assert(!symbol.is(Flags.Alias), "Alias not resolved: " + symbol)

    val tpe: Type = StaticRef(symbol)

    def name: String = symbol.name

  case class Select
    (qual: Word, name: String)
    (val span: Span)
    (using Definitions)
  extends Word:
    assert(qual.tpe.isValueType, "Select node must have value prefix, qual.tpe = " + qual.tpe + ", select = " + this.show)

    val tpe: Type = qual.tpe.termMember(name)

  /** Assignment to local vars
    *
    * It also represents local val/var definitions in later phases after
    * destruction of ValDef.
    */
  case class Assign
    (ident: Ident, rhs: Word)
  extends Word with DerivedSpan:
    val symbol = ident.symbol

    def tpe: Type = VoidType

    def deriveSpan = ident.span | rhs.span

  /** Assignment to fields */
  case class FieldAssign
    (lhs: Select, rhs: Word)
  extends Word with DerivedSpan:
    def tpe: Type = VoidType

    def deriveSpan = lhs.span | rhs.span

  case class If
    (cond: Word, thenp: Word, elsep: Word)
    (val tpe: Type, val span: Span)
  extends Word

  case class While
    (cond: Word, body: Word)
    (val span: Span)
  extends Word:
    def tpe: Type = VoidType

  case class IsExpr
    (scrutinee: Word, pattern: Pattern)
    (using defn: Definitions)
  extends Word with DerivedSpan:
    val tpe: Type = defn.BoolType

    def deriveSpan = scrutinee.span | pattern.span

  case class Block
    (words: List[Word])
    (val span: Span)
  extends Word:
    val tpe: Type = if words.isEmpty then VoidType else words.last.tpe

  case class With
    (expr: Word, args: List[Assign])
  extends Word with DerivedSpan:
    assert(args.nonEmpty, "With args cannot be empty")

    def tpe: Type = expr.tpe

    def deriveSpan = expr.span | args.last.span

  case class Allow
    (expr: Word, params: List[Ident])
  extends Word with DerivedSpan:
    def tpe: Type = expr.tpe

    def deriveSpan = params.foldLeft(expr.span)(_ | _.span)

  case class TypeApply
    (fun: Word, targs: List[TypeTree])
    (val tpe: Type, val span: Span)
  extends Word:
    assert(targs.nonEmpty, "type args should not be empty")

  case class Apply
    (fun: Word, args: List[Word], autos: List[Word])
    (val span: Span)
    (using Definitions)
  extends Word:
    val tpe = fun.tpe.asProcType match
      case procType =>
        assert(procType.tparams.size == 0, "tparams = " + procType.tparams)
        assert(procType.paramTypes.size == args.size, procType.show + ", " + args)
        assert(procType.autos.size == autos.size, procType.show + ", " + autos)

        procType.resultType

    def allArgs: List[Word] = args ++ autos

    def funSymbol: Option[Symbol] =
      fun match
        case Ident(sym)               => Some(sym)
        case TypeApply(Ident(sym), _) => Some(sym)
        case _                        => None

  case class New
    (classType: TypeTree)
    (val span: Span)
  extends Word:
    val tpe = classType.tpe

  // TODO: remove `tpe` from the parameters
  case class Object(self: Symbol, members: List[ValDef | FunDef])
    (val tpe: Type, val span: Span)
  extends Word

  /** Represents a lambda closure
    *
    * @param symbol The lambda symbol that owns the parameters and body definitions
    * @param params The parameter symbols
    * @param receives The effect parameters captured by this lambda (defaults to Nil unless inherited from target type)
    * @param body The lambda body
    */
  case class Lambda
    (symbol: Symbol, params: List[Symbol], receives: List[Symbol], body: Word)
    (val span: Span)
    (using Definitions)
  extends Word:
    val tpe =
      val resultType = body.tpe.widen
      LambdaType(params.map(_.info), resultType, receives)

  /** Encoding of a type with another type
    *
    * It is also used to explicitly represent dropped values.
    */
  case class Encoded
    (repr: Word)(val tpe: Type)
  extends Word with DerivedSpan:
    def deriveSpan = repr.span
    def isValueDrop(using Definitions) = repr.tpe.isValueType && tpe.isVoidType

  case class TypeTree
    (tpe: Type)
    (val span: Span)
  extends Tree

  //----------------------------------------------------------------------------
  // patterns

  sealed trait Pattern extends Tree:
    /** The type of the scrutinee */
    def scrutineeType: Type

    /** The refined type of the scrutinee if the pattern succeeds */
    def valueType: Type

    def show(using Definitions): String = Printing.show(this)

    def isWildcard: Boolean =
      this match
        case _: WildcardPattern => true
        case BindPattern(_, nested) => nested.isWildcard
        case _ => false

  case class TypePattern
    (tpt: TypeTree)(val scrutineeType: Type)
  extends Pattern with DerivedSpan:
    def valueType = tpt.tpe

    def deriveSpan: Span = tpt.span

  case class WildcardPattern
    ()
    (val scrutineeType: Type, val span: Span)
  extends Pattern:
    def valueType = scrutineeType

  case class BindPattern
    (id: Ident, nested: Pattern)
    (isDef: Boolean)
  extends Pattern with DerivedSpan:
    def scrutineeType = nested.scrutineeType
    def valueType = nested.valueType

    def deriveSpan = id.span | nested.span

    /** Whether the symbol is a reference or a definition
      *
      * - A symbol defined at the lhs of or pattern can be referred on rhs
      * - A symbol defined as params of patdef can be referred in patterns
      */
    def isDefinition: Boolean = isDef

  case class OrPattern
    (lhs: Pattern, rhs: Pattern)
    (val valueType: Type)
  extends Pattern with DerivedSpan:
    def scrutineeType = lhs.scrutineeType

    def deriveSpan = lhs.span | rhs.span

  case class AndPattern
    (lhs: Pattern, rhs: Pattern)
    (val valueType: Type)
  extends Pattern with DerivedSpan:
    def scrutineeType = lhs.scrutineeType

    def deriveSpan = lhs.span | rhs.span

  case class ApplyPattern
    (fun: Word, nested: List[Pattern])
    (val scrutineeType: Type, val span: Span)
    (using Definitions)
  extends Pattern:
    val valueType = fun.tpe.asProcType.resultType.stripPartial

    val symbol =
      fun match
        case Ident(sym) if sym.isPattern => sym
        case TypeApply(Ident(sym), _) if sym.isPattern => sym
        case _ => throw new Exception("expect a pattern predicate, found = " + fun)

    def deriveSpan = nested.foldLeft(fun.span)(_ | _.span)

  case class ValuePattern
    (value: Word)(val scrutineeType: Type)
  extends Pattern with DerivedSpan:
    def valueType = value.tpe

    def deriveSpan = value.span

  /** Represents patterns `if e`
    *
    * The scrutinee is ignored.
    */
  case class GuardPattern
    (guard: Word)
    (val scrutineeType: Type)
  extends Pattern with DerivedSpan:
    def valueType = scrutineeType

    def deriveSpan = guard.span

  case class AssignPattern
    (assignments: List[Assign])
    (val scrutineeType: Type)
  extends Pattern with DerivedSpan:
    assert(assignments.nonEmpty, "AssignPattern must have at least one assignment")

    def valueType = scrutineeType

    def deriveSpan = assignments.head.span | assignments.last.span

  case class SeqPattern
    (patterns: List[SeqPartPattern])
    (val scrutineeType: Type, val span: Span)
  extends Pattern:
    def valueType = scrutineeType

    /** The distance from the end of a pattern to the end of sequence */
    val distanceToEnd: Seq[SeqPattern.Size] = SeqPattern.computeDistanceToEnd(patterns)

    val totalSize: SeqPattern.Size =
      if patterns.isEmpty then SeqPattern.Size.Exact(0)
      else distanceToEnd(0) + patterns(0).size

    def apply(i: Int): SeqPartPattern = patterns(i)

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

    def computeDistanceToEnd(patterns: Seq[SeqPartPattern]): Seq[Size] =
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

  /** A subpattern that appears inside a sequence pattern */
  sealed trait SeqPartPattern extends Tree:
    def show(using Definitions): String = Printing.show(this)

    def headPattern: Pattern =
      this match
        case AtomPattern(pat) => pat
        case SkipToPattern(pat) => pat
        case StarPattern(pat) => pat
        case RestPattern(pat) => WildcardPattern()(AnyType, pat.span)

    /** The number of items the pattern consumes when the match is successful */
    def size: SeqPattern.Size =
      this match
        case AtomPattern(pat)    => SeqPattern.Size.Exact(1)
        case SkipToPattern(pat)  => SeqPattern.Size.GreatEq(1)
        case StarPattern(pat)    => SeqPattern.Size.GreatEq(0)
        case RestPattern(pat)    => SeqPattern.Size.GreatEq(0)

  case class AtomPattern
    (pattern: Pattern)
  extends SeqPartPattern with DerivedSpan:
    def deriveSpan: Span = pattern.span

  case class SkipToPattern
    (pattern: Pattern)
    (val span: Span)
  extends SeqPartPattern

  /** Takes the rest of a sequence
    *
    * May only be the last of a sequence pattern
    */
  case class RestPattern
    (pattern: Pattern)
    (val span: Span)
  extends SeqPartPattern

  /** Represent a * pattern
    *
    * For each variable bound in the inner pattern, a variable is introduced to
    * accumulate the inner-bound results.
    *
    * @param bindings Pairs of (outerX, innerX)
    */
  case class StarPattern
    (pattern: Pattern)
    (val span: Span, val bindings: List[(Symbol, Symbol)])
  extends SeqPartPattern:
    for (outer, inner) <- bindings do
      assert(outer.name == inner.name, s"outer = $outer, inner = inner")

  case class Match
    (scrutinee: Word, cases: List[Case])
    (val tpe: Type, val span: Span)
  extends Word

  case class Case
    (pattern: Pattern, body: Word)
    (val span: Span)
  extends Tree

  case class CaseDef
    (pattern: Pattern, rhs: Word)
    (val span: Span)
  extends Word:
    def tpe: Type = VoidType

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

  // TODO: add tparam and rhs
  case class TypeDef
    (symbol: Symbol)
    (val span: Span)
  extends Word, Def

  enum AutoCandidate extends Positioned:
    case Value(symbol: Symbol)(val span: Span)
    case Member(tpt: TypeTree, name: String)(val span: Span)

    def show(using Definitions): String =
      this match
        case Value(sym) => sym.name
        case Member(tpt, name) => "[" + tpt.tpe.show + "]." + name

  /** Represents a named function or method definition */
  case class FunDef
    (symbol: Symbol,
      tparams: List[Symbol],
      params: List[Symbol],
      autos: List[Symbol],
      candidates: List[List[AutoCandidate]],
      resultType: TypeTree,
      effectPolicy: Effects.Policy,
      body: Word)
    (val span: Span)
    (using defn: Definitions)
  extends Word, Def:
    defn.setCode(symbol, this)

    assert(autos.size == candidates.size)

    private var censusCache: (List[Symbol], List[Symbol]) | Null = null

    val allParams: List[Symbol] = params ++ autos

    def census(using Definitions): (List[Symbol], List[Symbol]) =
      if censusCache == null then
        censusCache = TreeOps.variableCensus(this)
        censusCache.nn
      else
        censusCache.nn

    /** contains a list of local value symbols (excluding params) */
    def locals(using Definitions): List[Symbol] = census._1
    def freeVariables(using Definitions): List[Symbol] = census._2

    def procType(using Definitions): ProcType = symbol.info.as[ProcType]

  /** Represents a pattern definition */
  case class PatDef
    (symbol: Symbol, tparams: List[Symbol], params: List[Symbol], resultType: TypeTree, body: Pattern)
    (val span: Span)
  extends Word, Def:
    def procType(using Definitions): ProcType = symbol.info.asProcType

  case class ClassDef
    (symbol: Symbol, self: Symbol, tparams: List[Symbol], vals: List[Symbol], funs: List[FunDef])
    (val span: Span)
  extends Def

  case class InterfaceDef
    (symbol: Symbol, self: Symbol, tparams: List[Symbol], methods: List[FunDef])
    (val span: Span)
  extends Def

  case class AliasDef
    (symbol: Symbol, target: Ident)
    (val span: Span)
  extends Def:
    assert(symbol.is(Flags.Alias), "alias symbol expected, found = " + symbol)

  case class Section
    (symbol: Symbol, defs: List[Def])
    (val span: Span)
  extends Def:

    def foreach(f: Def => Unit): Unit =
      defs.foreach:
        case sec: Section => sec.foreach(f)
        case defn => f(defn)

  case class Namespace
    (symbol: Symbol, imports: List[Symbol], defs: List[Def])
    (val span: Span)
  extends Positioned:

    def fullName(using Definitions): String = symbol.fullName

    def foreach(f: Def => Unit): Unit =
      defs.foreach:
        case sec: Section => sec.foreach(f)
        case defn => f(defn)

    def show(using Definitions): String = Printing.show(this)

    def source: String = symbol.sourcePos.source.file

  //----------------------------------------------------------------------------
  // Utility definitions

  class DelayedDef[+T](val symbol: Symbol, val delayed: () => T):
    private lazy val definition: T = delayed()
    def force()(using Definitions): T =
      symbol.info // force symbol
      definition

  //----------------------------------------------------------------------------
  // helpers

  def StringLit(s: String)(span: Span)(using defn: Definitions) =
    Literal(Constant.String(s))(defn.StringType, span)

  def IntLit(n: Int)(span: Span)(using defn: Definitions) =
    Literal(Constant.Int(n))(defn.IntType, span)

  def BoolLit(b: Boolean)(span: Span)(using defn: Definitions) =
    Literal(Constant.Bool(b))(defn.BoolType, span)

  def all(cond: Word, conds: Word*)(using defn: Definitions, source: Source): Word =
    conds.foldLeft(cond): (acc, cond) =>
      Ident(defn.Bool_both)(cond.span).appliedTo(acc, cond)

  def unitValue(span: Span)(using defn: Definitions): Word =
    Encoded(RecordLit(args = Nil)(span))(defn.UnitType)

  def errorWord(span: Span) = Encoded(Block(words = Nil)(span))(ErrorType)

  extension (word: Word)

    def select(name: String)(using Definitions): Word =
      Select(word, name)(word.span)

    /** No adaption is performed except for numeric adaptation */
    def appliedTo(args: Word*)(using Definitions): Word =
      val procType = word.tpe.asProcType

      assert(procType.paramCount == args.size, "args mismatch")
      assert(procType.tparams.isEmpty, "type params not supplied")
      assert(procType.autos.isEmpty, "autos not supplied")

      val args2 =
        for
          (arg, paramType) <- args.zip(procType.paramTypes)
        yield
          // Both fun and arg are fully instantiated
          Adaptation.adapt(arg, paramType, Adaptation.NoAdapter)

      val span = args.foldLeft(word.span)(_ | _.span)

      Apply(word, args2.toList, autos = Nil)(span)

    def appliedToTypes(targs: Type*)(using Definitions): Word =
      val procType = word.tpe.asProcType
      val targList = targs.toList
      val tpe = procType.instantiate(targList)
      val span = word.span
      TypeApply(word, targList.map(targ => TypeTree(targ)(word.span.endPoint)))(tpe, span)

    def appliedToTypeTrees(targs: TypeTree*)(using Definitions): Word =
      val procType = word.tpe.asProcType
      val targList = targs.toList
      val span = targs.foldLeft(word.span)(_ | _.span)
      val tpe = procType.instantiate(targList.map(_.tpe))
      TypeApply(word, targList)(tpe, span)

    def encodedAs(tpe: Type): Word = Encoded(word)(tpe)

    def isEqualTo(rhs: Word)(using defn: Definitions): Word =
      val span = word.span | rhs.span
      Apply(Ident(defn.Int_eql)(word.span), word :: rhs :: Nil, autos = Nil)(span)
