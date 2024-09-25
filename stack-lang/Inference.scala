import Types.*

import scala.collection.mutable

/** Type inference logic */
object Inference:
  enum TargetType:
    case Unknown
    case ValueType
    case ProperType // value type or void
    case Member(name: String)
    case Known(tpe: Type)

  trait Handler:
    def dealias(tvar: TypeVar): Type

    def approx(tvar: TypeVar, isUp: Boolean): Type

    def isSubtype(tvar: TypeVar, tp: Type): Boolean

    def isSuptype(tvar: TypeVar, tp: Type): Boolean

  class UnificationHandler extends Handler:
    private val instantiations: mutable.Map[TypeVar, Type] = mutable.Map.empty

    // TODO: record initial bounds of type variables
    // TODO: ensure instantiation of unconstrained type variables
    // TODO: non-termination for dealias, approx and conforms

    def dealias(tvar: TypeVar): Type =
      instantiations.get(tvar) match
        case Some(inst) => inst
        case None => AnyType

    def approx(tvar: TypeVar, isUp: Boolean): Type =
      instantiations.get(tvar) match
        case Some(inst) => TypeOps.approx(inst, isUp)
        case None => AnyType

    def isSubtype(tvar: TypeVar, tp: Type): Boolean =
      instantiations.get(tvar) match
        case Some(inst) =>
          Subtyping.conforms(inst, tp)

        case None =>
          if tvar == tp then
            true
          else
            instantiations(tvar) = tp
            true

    def isSuptype(tvar: TypeVar, tp: Type): Boolean =
      instantiations.get(tvar) match
        case Some(inst) =>
          Subtyping.conforms(tp, inst)

        case None =>
          if tvar == tp then
            true
          else
            instantiations(tvar) = tp
            true
