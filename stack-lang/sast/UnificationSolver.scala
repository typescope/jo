package sast

import Types.*

/** A solver that instantiate on the strategy of early commitment.
  *
  * The algorithm is intentionally chosen to be simple yet useful in daily
  * programming.
  *
  * Type inference is guessing user intentions. The guess should not be too
  * smart to for both usability and maintainability.
  *
  * For complex cases, users need to make their intentions clear either by
  * explicit expected type or type parameters.
  *
  * This algorithm is in the same spirit as Cardelli's greedy type inference
  * algorithm (1993).
  */
class UnificationSolver extends TypeVars:
  private var tvars = Vector.empty[TypeVar]
  private var instantiations: Map[TypeVar, Type] = Map.empty

  private def instantiate(tvar: TypeVar, tp: Type)(using Definitions) =
    assert(!instantiations.contains(tvar), "double instantiation: " + tvar)
    // println("Instantiating " + tvar + " to " + tp)
    // println("tvar.hashCode = " + System.identityHashCode(tvar))
    // println("tp.hashCode = " + System.identityHashCode(tp))
    // common.Debug.displayPrompt()

    // We do not
    //
    // - substitute occurrence in existing substitutions
    // - check that tvar does not occur in tp
    //
    // They are handled by subtype checking implicitly.
    if TypeOps.dealias(tp) != tvar then
      // TODO: Use the order of tvars to avoid the check and ensure in the case of
      // two tvars X <: Y, we instantite the one with greater id
      instantiations = instantiations.updated(tvar, tp)

  private def constrain(tvar: TypeVar, tp: Type, tvarLeft: Boolean)(using defn: Definitions): List[Subtyping.Task] =
    instantiations.get(tvar) match
      case Some(inst) =>
        if tvarLeft then Subtyping.Task(inst, tp) :: Nil
        else Subtyping.Task(tp, inst) :: Nil

      case None =>
        assert(tvar != tp, "cyclic instantiation " + tvar)

        instantiate(tvar, tp)
        Nil

  def add(tvar: TypeVar): Unit =
    tvars = tvars :+ tvar

  def typeVars: List[TypeVar] = tvars.toList

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

  def tryOrRevert(test: => Boolean): Boolean =
    val stateBefore = instantiations
    val tvarsBefore = tvars
    val res = test

    if res then
      true
    else
      instantiations = stateBefore
      tvars = tvarsBefore
      false
