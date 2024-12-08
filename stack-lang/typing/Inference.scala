package typing

import sast.Types.*
import sast.Subtyping

import scala.collection.mutable

/** Type inference logic */
object Inference:
  enum TargetType:
    case Unknown
    case ValueType
    case ProperType // value type or void
    case TermMember(name: String)
    case Known(tpe: Type)

    def knownType: Option[Type] =
      this match
        case Known(tpe) => Some(tpe)
        case _ => None

  trait Inferencer:
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

    def isSubtype(tvar: TypeVar, tp: Type): List[Subtyping.Task]

    def isSuptype(tvar: TypeVar, tp: Type): List[Subtyping.Task]

  class UnificationSolver extends Inferencer:
    private val instantiations: mutable.Map[TypeVar, Type] = mutable.Map.empty

    private def instantiate(tvar: TypeVar, tp: Type) =
      assert(!instantiations.contains(tvar), "double instantiation: " + tvar)
      // println("Instantiating " + tvar + " to " + tp.show)

      // We do not
      //
      // - substitute occurrence in existing substitutions
      // - check that tvar does not occur in tp
      //
      // They are handled by subtype checking implicitly.
      instantiations(tvar) = tp

    private def constrain(tvar: TypeVar, tp: Type, tvarLeft: Boolean): List[Subtyping.Task] =
      instantiations.get(tvar) match
        case Some(inst) =>
          if tvarLeft then Subtyping.Task(inst, tp) :: Nil
          else Subtyping.Task(tp, inst) :: Nil

        case None =>
          assert(tvar != tp)
          instantiate(tvar, tp)
          Nil

    def dealias(tvar: TypeVar): Type =
      instantiations.get(tvar) match
        case Some(inst) => inst
        case None => tvar

    def approx(tvar: TypeVar, isUp: Boolean): Type =
      instantiations.get(tvar) match
        case Some(inst) => inst

        case None =>
          tvar

    def isSubtype(tvar: TypeVar, tp: Type): List[Subtyping.Task] =
      constrain(tvar, tp, tvarLeft = true)

    def isSuptype(tvar: TypeVar, tp: Type): List[Subtyping.Task] =
      constrain(tvar, tp, tvarLeft = false)
