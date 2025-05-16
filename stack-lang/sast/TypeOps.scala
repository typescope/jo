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
  def substSymbols(tpe: Type, substs: Map[Symbol, Type])(using Definitions): Type =
    val typeMap = new TypeOps.SymbolsTypeMap
    typeMap(tpe)(using substs)

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
        case tref @ TypeRef(sym) =>
          if encountered.contains(tref) then
            tref
          else
            encountered += tref
            recur(sym.info, isUp)
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
        case tref @ TypeRef(sym) =>
          if encountered.contains(tref) || sym.isTypeParameter || !sym.isType && !sym.isAlias then
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
    * - constant
    */
  def isGrounded(tp: Type)(using Definitions): Boolean =
    tp match
      case TypeRef(sym) => (!sym.isType && !sym.isAlias) || sym.info.isInstanceOf[TypeBound]

      case AppliedType(TypeRef(sym), _) =>
        sym.info match
          case TypeLambda(_, _: TypeBound, _) => true
          case _ => false

      case tvar: TypeVar => !tvar.isInstantiated

      case _: ConstantType => false

      case _ => true

  /** A grouned proxy type dealiases to a grounded type
    *
    * It is used as a guard in subtype checking to defend against simple cycles
    * such as A = A.
    */
  def isGroundedProxy(tp: ProxyType)(using Definitions): Boolean = isGrounded(tp.dealias)


  abstract class TypeMap(using Definitions):
    type Context

    def apply(tp: Type)(using Context): Type

    def recur(tp: Type)(using Context): Type =
      tp match
        case VoidType | ErrorType | AnyType | BottomType =>
          tp

        case _: TypeRef | _: TypeVar | _: NameTableInfo | _: ConstantType =>
          tp

        case RecordType(fields) =>
          val fields2 =
            for field <- fields
            yield field.copy(info = this(field.info))
          RecordType(fields2)

        case UnionType(branches) =>
          val branches2 =
            for branch <- branches
            yield this(branch)

          UnionType(branches2)

        case TagType(tag, params) =>
          val params2 =
            for param <- params yield param.copy(info = this(param.info))
          TagType(tag, params2)

        case ObjectType(fields, methods, muts) =>
          val fields2 =
            for field <- fields
            yield field.copy(info = this(field.info))

          val methods2 =
            for method <- methods
            yield method.copy(info = this(method.info))

          ObjectType(fields2, methods2, muts)

        case AppliedType(tctor, targs) =>
          val tctor2 = apply(tctor)
          val targs2 = for targ <- targs yield this(targ)
          AppliedType(tctor2, targs2)

        case TypeLambda(tparams, resType, preParamCount) =>
          // TODO: Once type bounds are supported, we need to transform bounds
          TypeLambda(tparams, this(resType), preParamCount)

        case TypeBound(lo, hi) =>
          TypeBound(this(lo), this(hi))

        case ProcType(tparams, params, autos, resType, receivesOpt, preParamCount) =>
          // TODO: Once type bounds are supported, we need to transform bounds
          val params2 =
            for param <- params
            yield param.copy(info = this(param.info))

          val autos2 =
            for auto <- autos
            yield auto.copy(info = this(auto.info))

          val resType2 = this(resType)
          ProcType(tparams, params2, autos2, resType2, receivesOpt, preParamCount)

  class SymbolsTypeMap(using Definitions) extends TypeMap:
    type Context = Map[Symbol, Type]

    def apply(tp: Type)(using ctx: Context): Type =
      tp match
        case TypeRef(sym) =>
          ctx.getOrElse(sym, tp)

        case _ =>
          recur(tp)

  abstract class TypeTraverser:
    type Context = Definitions

    def apply(tp: Type)(using Context): Unit

    def recur(tp: Type)(using Context): Unit =
      tp match
        case VoidType | ErrorType | AnyType | BottomType =>

        case _: TypeRef | _: TypeVar | _: NameTableInfo | _: ConstantType =>

        case RecordType(fields) =>
          for field <- fields do this(field.info)

        case UnionType(branches) =>
          for branch <- branches do this(branch)

        case TagType(tag, params) =>
          for param <- params do this(param.info)

        case ObjectType(fields, methods, muts) =>
          for field <- fields do this(field.info)
          for method <- methods do this(method.info)

        case AppliedType(tctor, targs) =>
          apply(tctor)
          for targ <- targs do this(targ)

        case TypeLambda(tparams, resType, _) =>
          // TODO: Once type bounds are supported, we need to transform bounds
          this(resType)

        case TypeBound(lo, hi) =>
          this(lo)
          this(hi)

        case ProcType(tparams, params, autos, resType, receivesOpt, preParamCount) =>
          // TODO: Once type bounds are supported, we need to transform bounds
          for param <- params do this(param.info)

          for auto <- autos do this(auto.info)

          this(resType)
