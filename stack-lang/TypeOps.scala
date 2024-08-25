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
    else if tp1.isVoid && tp2.isBottom then Some(VoidType)
    else if tp1.isBottom && tp2.isVoid then Some(VoidType)
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

  /** A grounded type cannot be simplied further at the top-level
    *
    * The following types are grounded:
    *
    * - primitive types
    * - procedure types
    * - record types
    * - union types
    */
  def isGrounded(tp: Type): Boolean =
    tp match
      case AnyType | BottomType | IntType | BoolType | ErrorType | VoidType => true
      case _: PolyType | _: ProcType | _: FunctionType | _: RecordType | _: UnionType | _: TypeBound => true
      case _: TypeLambda | _: TypeParamRef => true
      case _: TypeRef | _: AppliedType => false

  /** Erase a poly type by replacing type parameters with Any */
  def erasePolyType(tp: Type): Type =
    // implementation assumption: no nested poly types
    dealias(tp) match
      case PolyType(_, bounds, resType) =>
        // cannot subst with bounds as they might be recursive
        // TODO: do it in a principled way
        TypeOps.substTypeParams(resType, bounds.map(_ => AnyType))

      case tp => tp

  def finalResultType(tp: Type): Type =
    tp match
      case PolyType(_, bounds, resType) => finalResultType(resType)
      case ProcType(_, _, resType) => resType
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
    val encountered = new mutable.ArrayBuffer[Symbol]
    def recur(tp: Type, isUp: Boolean): Type = Debug.trace(s"$tp.approx", enable = false):
      tp match
        case tref @ TypeRef(sym) =>
          if encountered.contains(sym) then
            tref
          else
            encountered += sym
            recur(sym.info, isUp)
          end if

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

  /** Transitively eliminate top-level type aliases and applied types */
  def dealias(tp: Type): Type =
    // detect cycles in symbol definitions, e.g., type A = A
    val encountered = new mutable.ArrayBuffer[Symbol]
    def recur(tp: Type): Type = Debug.trace(s"$tp.dealias", enable = false):
      tp match
        case tref @ TypeRef(sym) =>
          if encountered.contains(sym) then
            tref
          else
            encountered += sym
            recur(sym.info)
          end if

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

      case TypeRef(sym) =>
        sym.name

      case RecordType(fields) =>
        fields.map(_ + ": " + show(_)).mkString("{", ", ", "}")

      case UnionType(branches) =>
        def concat(tps: List[Type]) = tps.map(_.show).mkString(" * ")
        branches.map(_ + " " + concat(_)).mkString("<", ", ", ">")

      case AppliedType(tctor, targs) =>
        show(tctor) + targs.map(show).mkString("[", ", ", "]")

      case TypeLambda(names, bounds, body) =>
        val tparams = names.zip(bounds).map(_ + " <: " + show(_)).mkString("[", ", ", "]")
        tparams + " => " + show(body)

      case PolyType(names, bounds, resType) =>
        val tparams = names.zip(bounds).map(_ + " <: " + show(_)).mkString("[", ", ", "]")
        tparams + show(resType)

      case TypeParamRef(name, _) =>
        name

      case TypeBound(lo, hi) =>
        show(lo) + " .. " + show(hi)

      case ProcType(pre, post, resType) =>
        val preStr = pre.map(info => info.name + ": " + show(info.tpe)).mkString("(", ", ", ")")
        val postStr = post.map(info => info.name + ": " + show(info.tpe)).mkString("(", ", ", ")")
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

        case _: TypeRef | _: TypeParamRef =>
          tp

        case RecordType(fields) =>
          val fields2 =
            for (name, tpe) <- fields
            yield name -> this(tpe)
          RecordType(fields2)

        case UnionType(branches) =>
          val branches2 =
            for
              (tag, tps) <- branches
            yield
              tag -> tps.map(tp => this(tp))
          UnionType(branches2)

        case AppliedType(tctor, targs) =>
          val tctor2 = apply(tctor)
          val targs2 = for targ <- targs yield this(targ)
          AppliedType(tctor2, targs2)

        case TypeLambda(names, bounds, resType) =>
          val bounds2 = for bound <- bounds yield this(bound)
          val resType2 = this(resType)
          TypeLambda(names, bounds2, resType2)

        case PolyType(names, bounds, resType) =>
          val bounds2 = for bound <- bounds yield this(bound)
          val resType2 = this(resType)
          PolyType(names, bounds2, resType2)

        case TypeBound(lo, hi) =>
          TypeBound(this(lo), this(hi))

        case ProcType(preParams, postParams, resType) =>
          val preParams2 =
            for info <- preParams
            yield info.copy(tpe = this(info.tpe))

          val postParams2 =
            for info <- postParams
            yield info.copy(tpe = this(info.tpe))

          val resType2 = this(resType)

          ProcType(preParams2, postParams2, resType2)

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
