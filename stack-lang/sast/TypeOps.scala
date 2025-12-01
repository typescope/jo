package sast

import Types.*
import Symbols.*

import ast.Positions.Span

import common.Debug

import scala.collection.mutable

/** Operations on types */
object TypeOps:
  /** Substitute type symbols with the supplied types.
    *
    * This method is used in type checking definitions with type parameters.
    */
  def substSymbols(tpe: Type, tparams: List[Symbol], targs: List[Type])(using defn: Definitions): Type =
    defn.cache.substitute(tpe, targs):
      val subst = tparams.zip(targs).toMap
      substSymbols(tpe, subst)

  def substSymbols(tpe: Type, subst: Map[Symbol, Type])(using Definitions): Type =
    val typeMap = new TypeOps.SymbolsTypeMap
    typeMap(tpe)(using subst)

  /** Approximate top-level type aliases, applied types and type parameters
    *
    * The difference with `dealias` is that this method approximates type
    * bounds while `dealias` does not.
    *
    * It approximates a type to its upper bound or lower bound according to
    * the spec.
    */
  def approx(tp: Type, isUp: Boolean)(using Definitions): Type =
    widen(dealias(tp)) match
      case StaticRef(sym) =>
        sym.info match
          case TypeBound(lo, hi) =>
            approx(if isUp then hi else lo, isUp)

          case tp =>
            approx(tp, isUp)

      case tp => tp

  /** Widen a term reference or constant type to its underlying type */
  def widen(tp: Type)(using Definitions): Type =
    tp match
      case refType: RefType if !refType.symbol.isType => refType.info.widen
      case constType: ConstantType => constType.underlying.widen
      case _ => tp

  /** Check whether a type definition contains aliasing cycles */
  def hasCyclesInType(symbol: Symbol, info: Type)(using Definitions): Boolean =
    // detect cycles in symbol definitions, e.g., type A = A
    val encountered = new mutable.ArrayBuffer[Symbol]
    encountered += symbol
    var hasCycle = false

    def recur(tp: Type): Type = Debug.trace(s"$tp.hascycles", enable = false):
      tp match
        case StaticRef(sym) if sym.isType && !sym.isOneOf(Flags.Class | Flags.Interface | Flags.Param) =>
          if encountered.contains(sym) then
            hasCycle = true
            tp
          else
            encountered += sym
            recur(sym.info)
          end if

        case TypeBound(lo, hi) =>
          recur(lo)
          recur(hi)

        case app @ AppliedType(tctor, targs) =>
          recur(tctor) match
            case tl: TypeLambda =>
              recur(tl.instantiate(targs))

            case _ =>
              app

        case tp => tp
    end recur
    recur(info)
    hasCycle

  /** Normalize the type
    *
    * - Strip instantiated tvars from the type
    *
    * It is used in performance optimization thus it is best effort and needs to
    * be fast.
    */
  def normalize(tp: Type)(using Definitions): Type =
    tp match
      case tvar: TypeVar =>
        if tvar.isInstantiated then tvar.instantiated else tvar

      case AppliedType(tctor, args) if args.exists(_.is[TypeVar]) =>
        AppliedType(tctor, args.map(normalize))

      case _ => tp

  /** Transitively eliminate top-level type aliases and applied types without
    * any approximation but with widening.
    *
    * In particular, type parameters are not reduced to their bounds.
    */
  def dealias(tp: Type)(using Definitions): Type =
    def recur(tp: Type): Type = Debug.trace(s"$tp.dealias", enable = false):
      tp match
        case tref @ StaticRef(sym) =>
          val isRootType = sym.isOneOf(Flags.Param | Flags.Class | Flags.Interface) || sym.info.is[TypeBound]

          if isRootType || !sym.isType && !sym.isAlias then
            tref
          else
            recur(tref.symbol.info)

        case tvar: TypeVar =>
          if !tvar.isInstantiated then
            tvar
          else
            recur(tvar.instantiated)

        case app @ AppliedType(tctor, targs) =>
          recur(tctor) match
            case tl: TypeLambda =>
              recur(tl.instantiate(targs))

            case _ =>
              app

        case tp => tp
    end recur
    recur(tp)
  end dealias

  /** A grounded type cannot be simplied further at the top-level
    *
    * The following proxy types are not grounded:
    *
    * - type aliases
    * - instaniated type variables
    */
  def isGrounded(tp: Type)(using Definitions): Boolean =
    tp match
      case StaticRef(sym) => (!sym.isType && !sym.isAlias) || sym.isClass || sym.isInterface || sym.info.isInstanceOf[TypeBound]

      case AppliedType(StaticRef(sym), _) =>
        sym.info match
          case TypeLambda(_, _: TypeBound | _: ClassInfo, _) => true
          case _ => false

      case tvar: TypeVar => !tvar.isInstantiated

      case _ => true

  /**
    * Warning: If impredicativity is allowed for type parameters, we must
    * perform capture avoidance.
    *
    * Once we enable first-class higher-kinded types, we can have a type:
    *
    *     type C[F: * => *] = [A] => F[A]
    *
    * Now C[C[List]] after dealiasing can have the same symbol A refer to
    * different bindings.
    */
  class SymbolsTypeMap(using Definitions) extends TypeMap:
    type Context = Map[Symbol, Type]

    def apply(tp: Type)(using ctx: Context): Type =
      tp match
        case StaticRef(sym) =>
          ctx.getOrElse(sym, tp)

        case _ =>
          recur(tp)

  /** Replace all type parameters with fresh type vars */
  class InstantiateTypeParam(span: Span)(using Definitions, TypeVars) extends TypeMap:
    type Context = Unit

    def apply(tp: Type)(using ctx: Context): Type =
      tp match
        case StaticRef(sym) if sym.isTypeParameter =>
          TypeVar(sym.name, span)

        case _ =>
          recur(tp)

  class FullyInstantiatedChecker(using Definitions) extends TypeAccumulator[Boolean](true):
    type Context = Unit

    def combine(acc: Boolean, op: => Boolean): Boolean = acc && op

    def apply(tp: Type)(using Context): Boolean =
      tp match
        case tvar: TypeVar =>
          // We should not need the recursion --- but it can detect broken invariants
          if tvar.isInstantiated then this(tvar.instantiated)
          else false

        case _ =>
          recur(tp)

  class UninstantiatedCensor(using Definitions) extends TypeAccumulator[Set[TypeVar]](Set.empty):
    type Context = Unit

    def combine(acc: Set[TypeVar], op: => Set[TypeVar]): Set[TypeVar] = acc ++ op

    def apply(tp: Type)(using Context): Set[TypeVar] =
      tp match
        case tvar: TypeVar =>
          // We should not need the recursion --- but it can detect broken invariants
          if tvar.isInstantiated then this(tvar.instantiated)
          else Set(tvar)

        case _ =>
          recur(tp)
