import Types.*
import Symbols.*

/** Operations on types */
object TypeOps:

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
