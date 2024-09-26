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

    def dealias(tvar: TypeVar): Type

    def approx(tvar: TypeVar, isUp: Boolean): Type

    def isSubtype(tvar: TypeVar, tp: Type): Boolean

    def isSuptype(tvar: TypeVar, tp: Type): Boolean

  def isWithinBound(tp: Type, bound: TypeBound): Boolean =
    Subtyping.conforms(tp, bound.hi) && Subtyping.conforms(bound.lo, tp)

  class UnificationHandler extends Handler:
    private val instantiations: mutable.Map[TypeVar, Type] = mutable.Map.empty
    private val bounds: mutable.Map[TypeVar, TypeBound] = mutable.Map.empty

    // TODO: ensure instantiation of unconstrained type variables
    // TODO: non-termination for dealias, approx and conforms

    def newTypeVars(tparams: List[NamedInfo[TypeBound]]): List[TypeVar] =
      // TODO: F-bounds and dependencies between tparams
      for tparam <- tparams yield
        val tvar = TypeVar(tparam.name, this)
        bounds(tvar) = tparam.info
        tvar

    def dealias(tvar: TypeVar): Type =
      instantiations.get(tvar) match
        case Some(inst) => inst
        case None => bounds(tvar)

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
          else if isWithinBound(tp, bounds(tvar)) then
            instantiations(tvar) = tp
            true
          else
            false

    def isSuptype(tvar: TypeVar, tp: Type): Boolean =
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
