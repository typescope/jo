package sast

import Types.*
import Symbols.*

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
    *
    * The difference with `dealias` is that this method approximates type
    * bounds while `dealias` does not.
    *
    * It approximates a type to its upper bound or lower bound according to
    * the spec.
    */
  def approx(tp: Type, isUp: Boolean)(using Definitions): Type =
    // detect cycles in symbol definitions, e.g., type A = A
    val encountered = new mutable.ArrayBuffer[ProxyType]
    def recur(tp: Type, isUp: Boolean): Type = Debug.trace(s"$tp.approx", enable = false):
      tp match
        case tref: RefType =>
          if encountered.contains(tref) then
            tref
          else
            encountered += tref
            recur(tref.info, isUp)
          end if

        case tvar: TypeVar =>
          if encountered.contains(tvar) then
            tvar
          else
            encountered += tvar
            recur(tvar.approx(isUp), isUp)

        case TypeBound(lo, hi) =>
          if isUp then recur(hi, isUp) else recur(lo, isUp)

        case app @ AppliedType(tctor, targs) =>
          recur(tctor, isUp) match
            case tl: TypeLambda =>
              recur(tl.instantiate(targs), isUp)

            case _ =>
              app

        case tp => tp
    end recur
    recur(tp, isUp)
  end approx

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
    // detect cycles in symbol definitions, e.g., type A = A
    val encountered = new mutable.ArrayBuffer[ProxyType]
    def recur(tp: Type): Type = Debug.trace(s"$tp.dealias", enable = false):
      tp match
        case tref @ StaticRef(sym) =>
          if encountered.contains(tref) || sym.isTypeParameter || sym.info.is[TypeBound] || !sym.isType && !sym.isAlias then
            tref
          else
            encountered += tref
            recur(tref.symbol.info)

        case tvar: TypeVar =>
          if !tvar.isInstantiated || encountered.contains(tvar) then
            tvar
          else
            encountered += tvar
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
      case StaticRef(sym) => (!sym.isType && !sym.isAlias) || sym.info.isInstanceOf[TypeBound]

      case AppliedType(StaticRef(sym), _) =>
        sym.info match
          case TypeLambda(_, _: TypeBound | _: ClassInfo, _) => true
          case _ => false

      case tvar: TypeVar => !tvar.isInstantiated

      case _ => true

  /** A grouned proxy type dealiases to a grounded type
    *
    * It is used as a guard in subtype checking to defend against simple cycles
    * such as A = A.
    */
  def isGroundedProxy(tp: ProxyType)(using Definitions): Boolean = isGrounded(tp.dealias)

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
