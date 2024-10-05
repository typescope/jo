import Types.*
import Symbols.*

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
    * - VoidType dominates BottomType
    *
    * Also, do not infer Any as common type, which is useless.
    */
  def commonResultType(tp1: Type, tp2: Type): Option[Type] =
    if tp1.isError || tp2.isError then Some(ErrorType)
    else if tp1.isVoidType && tp2.isBottom then Some(VoidType)
    else if tp1.isBottom && tp2.isVoidType then Some(VoidType)
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

  /** Erase a poly type by replacing type parameters with Any */
  def erasePolyType(tp: Type): Type =
    // implementation assumption: no nested poly types
    dealias(tp) match
      case PolyType(tparams, resType) =>
        // cannot subst with bounds as they might be recursive
        // TODO: do it in a principled way
        TypeOps.substTypeParams(resType, tparams.map(_ => AnyType))

      case tp => tp

  def finalResultType(tp: Type): Type =
    tp match
      case PolyType(_, resType) => finalResultType(resType)
      case ProcType(_, resType, _) => resType
      case tp => tp

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

  /** Transitively eliminate top-level type aliases and applied types without any approximation
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
      case IntType     => "Int"
      case BoolType    => "Bool"
      case VoidType    => "Void"
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
        fields.map(f => f.name + ": " + show(f.info)).mkString("{", ", ", "}")

      case UnionType(branches) =>
        def concat(tps: List[Type]) = tps.map(_.show).mkString(" * ")
        branches.map(b => b.name + " " + concat(b.info)).mkString("<", ", ", ">")

      case AppliedType(tctor, targs) =>
        show(tctor) + targs.map(show).mkString("[", ", ", "]")

      case TypeLambda(tparams, body) =>
        val tparamStr = tparams.map(tparam => tparam.name + " <: " + show(tparam.info)).mkString("[", ", ", "]")
        tparamStr + " => " + show(body)

      case PolyType(tparams, resType) =>
        val tparamStr = tparams.map(tparam => tparam.name + " <: " + show(tparam.info)).mkString("[", ", ", "]")
        tparamStr + show(resType)

      case TypeParamRef(name, _) =>
        name

      case TypeBound(lo, hi) =>
        show(lo) + " .. " + show(hi)

      case ProcType(params, resType, n) =>
        val preStr = params.take(n).map(param => param.name + ": " + show(param.info)).mkString("(", ", ", ")")
        val postStr = params.drop(n).map(param => param.name + ": " + show(param.info)).mkString("(", ", ", ")")
        preStr + postStr + ": " + show(resType)

      case FunctionType(paramTypes, resType) =>
        val params = paramTypes.map(show).mkString(" * ")
        params + " => " + show(resType)
  end show

  trait TypeMap:
    type Context

    def apply(tp: Type)(using Context): Type

    def recur(tp: Type)(using Context): Type =
      tp match
        case VoidType | ErrorType | AnyType | BottomType | IntType | BoolType =>
          tp

        case _: TypeRef | _: TypeParamRef | _: TypeVar =>
          tp

        case RecordType(fields) =>
          val fields2 =
            for field <- fields
            yield field.copy(info = this(field.info))
          RecordType(fields2)

        case UnionType(branches) =>
          val branches2 =
            for branch <- branches
            yield branch.copy(info = branch.info.map(this.apply))
          UnionType(branches2)

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

        case PolyType(tparams, resType) =>
          val tparams2 =
            for tparam <- tparams
            yield tparam.copy(info = this(tparam.info).as[TypeBound])

          val resType2 = this(resType)
          PolyType(tparams2, resType2)

        case TypeBound(lo, hi) =>
          TypeBound(this(lo), this(hi))

        case ProcType(params, resType, preParamCount) =>
          val params2 =
            for param <- params
            yield param.copy(info = this(param.info))

          val resType2 = this(resType)
          ProcType(params2, resType2, preParamCount)

        case FunctionType(paramTypes, resType) =>
          val paramTypes2 = paramTypes.map(tp => this(tp))
          val resType2 = this(resType)
          FunctionType(paramTypes2, resType2)

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

        case _: TypeLambda | _: PolyType =>
          // nested type lambdas or polymorphic types are not supported
          tp

        case _ =>
          recur(tp)
