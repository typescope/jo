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
          && !sym.isAllOf(Flags.Context | Flags.Param)

        case Select(qual, _) => qual.isIdempotent

        case Encoded(expr) => expr.isIdempotent

        case _ => false

    def refers(symbol: Symbol)(using Definitions): Boolean =
      // selection is never symblic after normalization, thus no need to handle
      this match
        case Ident(sym) => sym.refers(symbol)
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
    val tpe: Type = StaticRef(symbol)

    def name: String = symbol.name

  case class Select
    (qual: Word, name: String)
    (val tpe: Type, val span: Span)
    (using Definitions)
  extends Word:
    assert(qual.tpe.isValueType, "Select node must have value prefix, qual.tpe = " + qual.tpe + ", select = " + this.show)

  /** Assignment to local vars
    *
    * It also represents local val/var definitions in later phases after
    * destruction of ValDef.
    */
  case class Assign
    (ident: Ident, rhs: Word)
  extends Word:
    def span = ident.span | rhs.span
    val symbol = ident.symbol
    def tpe: Type = VoidType

  /** Assignment to fields */
  case class FieldAssign
    (lhs: Select, rhs: Word)
  extends Word:
    def span = lhs.span | rhs.span
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
    (expr: Word, args: List[Assign])
    (val tpe: Type)
  extends Word:
    assert(args.nonEmpty)

    def span = expr.span | args.last.span

  case class Allow
    (expr: Word, params: List[Ident])
    (val tpe: Type)
  extends Word:
    def span = params.foldLeft(expr.span)(_ | _.span)

  case class TypeApply
    (fun: Word, targs: List[TypeTree])
    (val tpe: Type)
  extends Word:
    assert(targs.nonEmpty)

    def span = fun.span | targs.last.span

  case class Apply
    (fun: Word, args: List[Word], autos: List[Word])
    (val tpe: Type)
    (using Definitions)
  extends Word:
    fun.tpe.asProcType match
      case procType =>
        assert(procType.paramTypes.size == args.size, procType.show + ", " + args)
        assert(procType.autos.size == autos.size, procType.show + ", " + autos)

    def span = args.foldLeft(fun.span)(_ | _.span)

    def allArgs: List[Word] = args ++ autos

    def funSymbol: Option[Symbol] =
      fun match
        case Ident(sym)               => Some(sym)
        case TypeApply(Ident(sym), _) => Some(sym)
        case _                        => None

  object Apply:
    def apply(fun: Word, args: List[Word])(tpe: Type)(using Definitions): Apply =
      apply(fun, args, autos = Nil)(tpe)

  case class New
    (classRef: Ident, targs: List[TypeTree])
    (val tpe: Type)
  extends Word:
    def span = targs.foldLeft(classRef.span)(_ | _.span)

  case class Object(self: Symbol, vals: List[ValDef], funs: List[FunDef])
    (val tpe: Type, val span: Span)
  extends Word

  /** Encoding of a type with another type
    *
    * It is also used to explicitly represent dropped values.
    */
  case class Encoded
    (repr: Word)(val tpe: Type)
  extends Word:
    def span = repr.span
    def isValueDrop(using Definitions) = repr.tpe.isValueType && tpe.isVoidType

  case class TypeTree
    (tpe: Type)
    (val span: Span)
  extends Tree

  //----------------------------------------------------------------------------
  // patterns

  sealed trait Pattern extends Tree:
    val scrutineeType: Type

    def tpe: Type = scrutineeType

    def show(using Definitions): String = Printing.show(this)

    def isWildcard: Boolean =
      this match
        case _: WildcardPattern => true
        case AliasPattern(_, nested) => nested.isWildcard
        case _ => false

  case class TypePattern
    (tpt: TypeTree)(val scrutineeType: Type)
  extends Pattern:
    val span: Span = tpt.span

  case class WildcardPattern
    ()
    (val scrutineeType: Type, val span: Span)
  extends Pattern

  case class AliasPattern
    (id: Ident, nested: Pattern)
  extends Pattern:
    val scrutineeType = nested.scrutineeType
    val span = id.span | nested.span

  case class OrPattern
    (lhs: Pattern, rhs: Pattern)
  extends Pattern:
    val scrutineeType = lhs.scrutineeType
    val span = lhs.span | rhs.span

  case class ApplyPattern
    (fun: Word, nested: List[Pattern])
    (val scrutineeType: Type)
  extends Pattern:
    def span = nested.foldLeft(fun.span)(_ | _.span)
    val symbol =
      fun match
        case Ident(sym) if sym.isPattern => sym
        case TypeApply(Ident(sym), _) if sym.isPattern => sym
        case _ => throw new Exception("expect a pattern predicate, found = " + fun)

  case class TagPattern
    (tagTree: Literal, nested: List[Pattern])
    (val scrutineeType: Type)
  extends Pattern:
    val span = if nested.isEmpty then tagTree.span else tagTree.span | nested.last.span

    val tag = tagTree.constant match
      case Constant.String(name) => name
      case c => throw new Exception("Expect string, found = " + c)

  case class ValuePattern
    (value: Word)(val scrutineeType: Type)
  extends Pattern:
    val span = value.span

  case class GuardPattern
    (pattern: Pattern, guard: Word)
  extends Pattern:
    val scrutineeType = pattern.scrutineeType
    val span = pattern.span | guard.span

  case class BindPattern
    (pattern: Pattern, bindings: List[Assign])
  extends Pattern:
    val scrutineeType = pattern.scrutineeType
    val span = bindings.foldLeft(pattern.span)(_ | _.span)

  case class SeqPattern
    (patterns: List[SeqPartPattern])
    (val scrutineeType: Type, val span: Span)
  extends Pattern:
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
    def tpe: Type = throw new Exception("No type associated with seq part pattern")

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
  extends SeqPartPattern:
    val span: Span = pattern.span

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
    (symbol: Symbol, tparams: List[Symbol], params: List[Symbol], autos: List[Symbol], resultType: TypeTree, body: Word)
    (val span: Span)
  extends Word, Def:
    private var censusCache: (List[Symbol], List[Symbol]) | Null = null

    val allParams: List[Symbol] = params ++ autos

    def census(using Definitions): (List[Symbol], List[Symbol]) =
      if censusCache == null then
        censusCache = SastOps.variableCensus(this)
        censusCache.nn
      else
        censusCache.nn

    /** contains a list of local value symbols (excluding params) */
    def locals(using Definitions): List[Symbol] = census._1
    def freeVariables(using Definitions): List[Symbol] = census._2

    def procType(using Definitions): ProcType = symbol.info.as[ProcType]

    def effectsBound(using Definitions): Option[List[Symbol]] = procType.effectsBound

    def effectPolicy(using Definitions): Effects.Policy = procType.receives

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

  case class Section
    (symbol: Symbol, defs: List[Def])
    (val span: Span)
  extends Def:
    def info(using Definitions): ContainerInfo = symbol.info.as[ContainerInfo]

    def foreach(f: Def => Unit): Unit =
      defs.foreach:
        case sec: Section => sec.foreach(f)
        case defn => f(defn)

  case class Namespace
    (symbol: Symbol, imports: List[Symbol], defs: List[Def])
    (val span: Span)
  extends Positioned:
    def info(using Definitions): ContainerInfo = symbol.info.as[ContainerInfo]

    def fullName(using Definitions): String = symbol.fullName

    def foreach(f: Def => Unit): Unit =
      defs.foreach:
        case sec: Section => sec.foreach(f)
        case defn => f(defn)

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
      Ident(defn.Bool_both)(cond.span).appliedTo(acc, cond)

  def unitValue(span: Span)(using defn: Definitions): Word =
    RecordLit(args = Nil)(defn.UnitType, span)

  extension (word: Word)

    def select(name: String)(using Definitions): Word =
      val memberType = word.tpe.termMember(name)
      Select(word, name)(memberType, word.span)

    def appliedTo(args: Word*)(using Definitions): Word =
      val procType = word.tpe.asProcType

      assert(procType.paramCount == args.size, "args mismatch")
      assert(procType.tparams.isEmpty, "type params not supplied")
      assert(procType.autos.isEmpty, "autos not supplied")

      val args2 =
        for (arg, paramType) <- args.zip(procType.paramTypes)
        yield SastOps.adapt(arg, paramType)

      Apply(word, args2.toList, autos = Nil)(procType.resultType)

    def appliedToTypes(targs: Type*)(using Definitions): Word =
      val procType = word.tpe.asProcType
      val targList = targs.toList
      val tpe = procType.instantiate(targList)
      TypeApply(word, targList.map(targ => TypeTree(targ)(word.span.endPoint)))(tpe)

    def appliedToTypeTrees(targs: TypeTree*)(using Definitions): Word =
      val procType = word.tpe.asProcType
      val targList = targs.toList
      val tpe = procType.instantiate(targList.map(_.tpe))
      TypeApply(word, targList)(tpe)

    def encodedAs(tpe: Type): Word = Encoded(word)(tpe)

    def isEqualTo(rhs: Word)(using defn: Definitions): Word =
      Ident(defn.Int_eql)(word.span).appliedTo(word, rhs)
