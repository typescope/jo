package typing

import sast.TypeOps
import sast.Types.*
import sast.Symbols.*
import sast.Subtyping
import sast.Definitions

/** Type inference logic */
object Inference:
  enum TargetType:
    case Unknown
    case ValueType
    case VoidType
    case TypeApply
    case Fun(args: Int)
    case TermMember(name: String)
    case TypeMember(name: String)
    case Known(tpe: Type, adapters: List[Symbol] = Nil, isVarargSplice: Boolean = false)

    def knownType: Option[Type] =
      this match
        case Known(tpe, _, _) => Some(tpe)
        case _ => None

  /** The common result type of two different types.
    *
    * This method is used to compute the result type of if- and match-
    * expressions.
    *
    * The logic is different from computing join in the subtype lattice:
    *
    * - ErrorType always dominates
    * - VoidType dominates anything else
    * - Reference to terms are widened
    *
    * Also, do not infer Any as common type, which is useless.
    */
  def commonResultType(tp1: Type, tp2: Type)
      (using defn: Definitions, tt: TargetType): Option[Type] =

    val tp1Widen = tp1.widenTermRef
    val tp2Widen = tp2.widenTermRef
    if tp1.isError || tp2.isError then Some(ErrorType)
    else if tp1.isVoidType || tp2.isVoidType then Some(VoidType)
    else if Subtyping.conforms(tp1, tp2Widen) then Some(tp2Widen)
    else if Subtyping.conforms(tp2, tp1Widen) then Some(tp1Widen)
    else
      tt match
        case TargetType.Known(tp, _, _) =>
          if Subtyping.conforms(tp1, tp) && Subtyping.conforms(tp2, tp) then
            Some(tp)
          else
            None

        case _ =>
          if tp1.isTagType && tp2.isTagType then
            Some(UnionType(tp1 :: tp2 :: Nil))

          else
            None

  trait Inferencer:
    /** The instantiated type associated with the type varialbe
      *
      * Throws exception is the type var is not yet instantiated.
      */
    def instantiated(tvar: TypeVar): Type

    /** Approximate the type of the tvar to its bounds
      *
      * The method shoud not recursively call `TypeOps.approx`.
      */
    def approx(tvar: TypeVar, isUp: Boolean): Type

    def isInstantiated(tvar: TypeVar): Boolean

    def isSubtype(tvar: TypeVar, tp: Type)(using Definitions): List[Subtyping.Task]

    def isSuptype(tvar: TypeVar, tp: Type)(using Definitions): List[Subtyping.Task]

    /** The state of inference will be reverted back after test */
    def test[T](op: => T): T

  class UnificationSolver extends Inferencer:
    private var instantiations: Map[TypeVar, Type] = Map.empty

    private def instantiate(tvar: TypeVar, tp: Type)(using Definitions) =
      assert(!instantiations.contains(tvar), "double instantiation: " + tvar)
      // println("Instantiating " + tvar + " to " + tp)
      // println("tvar.hashCode = " + System.identityHashCode(tvar))
      // println("tp.hashCode = " + System.identityHashCode(tp))

      // We do not
      //
      // - substitute occurrence in existing substitutions
      // - check that tvar does not occur in tp
      //
      // They are handled by subtype checking implicitly.
      if TypeOps.dealias(tp) != tvar then
        instantiations = instantiations.updated(tvar, tp)

    private def constrain(tvar: TypeVar, tp: Type, tvarLeft: Boolean)(using Definitions): List[Subtyping.Task] =
      instantiations.get(tvar) match
        case Some(inst) =>
          if tvarLeft then Subtyping.Task(inst, tp) :: Nil
          else Subtyping.Task(tp, inst) :: Nil

        case None =>
          assert(tvar != tp)
          instantiate(tvar, tp)
          Nil

    def isInstantiated(tvar: TypeVar): Boolean =
      instantiations.get(tvar).nonEmpty

    def instantiated(tvar: TypeVar): Type =
      instantiations.get(tvar) match
        case Some(inst) => inst
        case None => throw new Exception("Not instantiated: " + tvar)

    def approx(tvar: TypeVar, isUp: Boolean): Type =
      instantiations.get(tvar) match
        case Some(inst) => inst

        case None =>
          tvar

    def isSubtype(tvar: TypeVar, tp: Type)(using Definitions): List[Subtyping.Task] =
      constrain(tvar, tp, tvarLeft = true)

    def isSuptype(tvar: TypeVar, tp: Type)(using Definitions): List[Subtyping.Task] =
      constrain(tvar, tp, tvarLeft = false)

    def test[T](op: => T): T =
      val stateBefore = instantiations
      val res = op
      instantiations = stateBefore
      res
