package sast

import Types.*
import Symbols.*

import common.Debug

import scala.collection.mutable

/** Operations on types */
object TypeOps:

  /** The common result type of two different types.
    *
    * This method is used to compute the result type of if- and match-
    * expressions.
    *
    * The logic is different from computing join in the subtype lattice:
    *
    * - ErrorType always dominates
    * - VoidType dominates anything else
    *
    * Also, do not infer Any as common type, which is useless.
    */
  def commonResultType(tp1: Type, tp2: Type): Option[Type] =
    if tp1.isError || tp2.isError then Some(ErrorType)
    else if tp1.isVoidType || tp2.isVoidType then Some(VoidType)
    else if Subtyping.conforms(tp1, tp2) then Some(tp2)
    else if Subtyping.conforms(tp2, tp1) then Some(tp1)
    else None

  /** Substitute type params with the given types */
  def substTypeParams(tpe: Type, to: List[Type]): Type =
    val typeMap = new TypeOps.TypeParamTypeMap
    typeMap(tpe)(using to)

  /** Substitute type symbols with the supplied types.
    *
    * This method is used in type checking definitions with type parameters.
    */
  def substSymbols(tpe: Type, substs: Map[Symbol, Type]): Type =
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
  def approx(tp: Type, isUp: Boolean): Type =
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
              recur(TypeOps.substTypeParams(tl.body, targs), isUp)

            case _ =>
              app

        case tp => tp
    end recur
    recur(tp, isUp)
  end approx

  /** Transitively eliminate top-level type aliases and applied types without
    * any approximation but with widening.
    *
    * In particular, type parameters are not reduced to their bounds.
    */
  def dealias(tp: Type): Type =
    // detect cycles in symbol definitions, e.g., type A = A
    val encountered = new mutable.ArrayBuffer[ProxyType]
    def recur(tp: Type): Type = Debug.trace(s"$tp.dealias", enable = false):
      tp match
        case tref: TypeRef =>
          if encountered.contains(tref) || tref.symbol.isTypeParameter then
            tref
          else
            encountered += tref
            recur(tref.symbol.info)

        case tvar: TypeVar =>
          if encountered.contains(tvar) then
            tvar
          else
            encountered += tvar
            recur(tvar.dealias)

        case app @ AppliedType(tctor, targs) =>
          recur(tctor) match
            case tl: TypeLambda =>
              recur(TypeOps.substTypeParams(tl.body, targs))

            case _ =>
              app

        case tp => tp
    end recur
    recur(tp)
  end dealias

  def show(tp: Type): String =
    tp match
      case VoidType    => "void"
      case AnyType     => "Any"
      case BottomType  => "Bottom"
      case ErrorType   => "Error"

      case tvar: TypeVar =>
        val dealias = tvar.dealias
        if dealias != tvar then
          dealias.show
        else
          tvar.toString

      case TypeRef(sym) =>
        if sym.isType then sym.name else sym.name + ": " + sym.info.show

      case RecordType(fields) =>
        fields.map(f => f.name + ": " + show(f.info)).mkString("{ ", ", ", " }")

      case ObjectType(fields, methods, muts) =>
        val fieldList = fields.map: f =>
          val mod = if muts.contains(f.name) then "var " else " val "
          mod + f.name + ": " + show(f.info)

        val methodList = methods.map: m =>
          "def " + m.name + show(m.info.dealias)

        (fieldList ++ methodList).mkString("object { ", "; ", " }")

      case UnionType(branches) =>
        def paramStr(paramInfos: List[NamedInfo[Type]]) = paramInfos.map(param => param.name + ": " + show(param.info)).mkString("(", ", ", ")")
        branches.map(b => b.name + " " + paramStr(b.info)).mkString("<", ", ", ">")

      case AppliedType(tctor, targs) =>
        show(tctor) + targs.map(show).mkString("[", ", ", "]")

      case TypeLambda(tparams, body) =>
        val tparamStr = tparams.map(tparam => tparam.name + " <: " + show(tparam.info)).mkString("[", ", ", "]")
        tparamStr + " => " + show(body)

      case TypeParamRef(name, _) =>
        name

      case TypeBound(lo, hi) =>
        show(lo) + " .. " + show(hi)

      case ProcType(tparams, params, resType, receivesOpt, n) =>
        val tparamStr =
          if tparams.isEmpty then
            ""
          else
            tparams.map(tparam => tparam.name + " <: " + show(tparam.info)).mkString("[", ", ", "]")

        val preStr =
          if n > 0 then
            params.take(n).map(param => param.name + ": " + show(param.info)).mkString("(", ", ", ")")
          else
            ""

        val postStr = params.drop(n).map(param => param.name + ": " + show(param.info)).mkString("(", ", ", ")")
        val receivesStr = if receivesOpt.isEmpty then "" else " receives " + receivesOpt.get.map(_.name).mkString(", ")

        tparamStr + preStr + postStr + ": " + show(resType) + receivesStr

      case _: NameTableInfo => "{ ...nametable }"
  end show

  trait TypeMap:
    type Context

    def apply(tp: Type)(using Context): Type

    def recur(tp: Type)(using Context): Type =
      tp match
        case VoidType | ErrorType | AnyType | BottomType =>
          tp

        case _: TypeRef | _: TypeParamRef | _: TypeVar | _: NameTableInfo =>
          tp

        case RecordType(fields) =>
          val fields2 =
            for field <- fields
            yield field.copy(info = this(field.info))
          RecordType(fields2)

        case UnionType(branches) =>
          val branches2 =
            for branch <- branches
            yield branch.copy(
              info = branch.info.map(
                param => param.copy(info = this.apply(param.info))
              )
            )
          UnionType(branches2)

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

        case TypeLambda(tparams, resType) =>
          val tparams2 =
            for tparam <- tparams
            yield tparam.copy(info = this(tparam.info).as[TypeBound])

          val resType2 = this(resType)
          TypeLambda(tparams2, resType2)

        case TypeBound(lo, hi) =>
          TypeBound(this(lo), this(hi))

        case ProcType(tparams, params, resType, receivesOpt, preParamCount) =>
          val tparams2 =
            for tparam <- tparams
            yield tparam.copy(info = this(tparam.info).as[TypeBound])

          val params2 =
            for param <- params
            yield param.copy(info = this(param.info))

          val resType2 = this(resType)
          ProcType(tparams2, params2, resType2, receivesOpt, preParamCount)

  class SymbolsTypeMap extends TypeMap:
    type Context = Map[Symbol, Type]

    def apply(tp: Type)(using ctx: Context): Type =
      tp match
        case TypeRef(sym) =>
          ctx.getOrElse(sym, tp)

        case _ =>
          recur(tp)

  class TypeParamTypeMap extends TypeMap:
    type Context = List[Type]

    def apply(tp: Type)(using ctx: Context): Type =
      tp match
        case TypeParamRef(_, index) =>
          ctx(index)

        case _: TypeLambda =>
          // nested type lambdas are not supported
          tp

        case _ =>
          recur(tp)
