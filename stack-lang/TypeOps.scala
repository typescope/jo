import Types.*
import Symbols.*

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

        case ProcType(names, paramTypes, resType) =>
          val paramTypes2 = paramTypes.map(tp => this(tp))
          val resType2 = this(resType)
          ProcType(names, paramTypes2, resType2)

        case FunctionType(paramTypes, resType) =>
          val paramTypes2 = paramTypes.map(tp => this(tp))
          val resType2 = this(resType)
          FunctionType(paramTypes2, resType2)

        case tp: DelayedType =>
          this(tp.underlying)

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
