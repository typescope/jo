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
    def newTypeVars(tparams: List[NamedInfo[TypeBound]]): List[TypeVar]

    /** Dealias the type without approximation.
      *
      * The method should not recursively call `TypeOps.dealias`.
      */
    def dealias(tvar: TypeVar): Type

    /** Approximate the type of the tvar to its bounds
      *
      * The method shoud not recursively call `TypeOps.approx`.
      */
    def approx(tvar: TypeVar, isUp: Boolean): Type

    def isSubtype(tvar: TypeVar, tp: Type)(using Subtyping.Context): Boolean

    def isSuptype(tvar: TypeVar, tp: Type)(using Subtyping.Context): Boolean

  def isWithinBound(tp: Type, bound: TypeBound): Boolean =
    Subtyping.conforms(tp, bound.hi) && Subtyping.conforms(bound.lo, tp)

  class UnificationHandler extends Handler:
    private val instantiations: mutable.Map[TypeVar, Type] = mutable.Map.empty
    private val bounds: mutable.Map[TypeVar, TypeBound] = mutable.Map.empty

    // TODO: ensure instantiation of unconstrained type variables

    def newTypeVars(tparams: List[NamedInfo[TypeBound]]): List[TypeVar] =
      // TODO: F-bounds and dependencies between tparams
      for tparam <- tparams yield
        val tvar = TypeVar(tparam.name, this)
        bounds(tvar) = tparam.info
        tvar

    def dealias(tvar: TypeVar): Type =
      instantiations.get(tvar) match
        case Some(inst) => inst
        case None => tvar

    def approx(tvar: TypeVar, isUp: Boolean): Type =
      instantiations.get(tvar) match
        case Some(inst) => TypeOps.approx(inst, isUp)
        case None => AnyType

    def isSubtype(tvar: TypeVar, tp: Type)(using Subtyping.Context): Boolean =
      instantiations.get(tvar) match
        case Some(inst) =>
          Subtyping.conforms(inst, tp)

        case None =>
          if tvar == tp then
            true
          else if isWithinBound(tp, bounds(tvar)) then
            instantiations(tvar) = tp
            true
          else
            false

    def isSuptype(tvar: TypeVar, tp: Type)(using Subtyping.Context): Boolean =
      instantiations.get(tvar) match
        case Some(inst) =>
          Subtyping.conforms(tp, inst)

        case None =>
          if tvar == tp then
            true
          else if isWithinBound(tp, bounds(tvar)) then
            instantiations(tvar) = tp
            true
          else
            false
