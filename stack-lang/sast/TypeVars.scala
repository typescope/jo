package sast

import Types.{ Type, TypeVar }

/** An isolated container for inferencing a group of type variables
  *
  * A type variable belongs exactly to a single isolated container and must be
  * constrained and instantiated when the container goes out of scope.
  *
  * It is important that each group of type variables are isolated: it means
  * it is impossible for type vars of a group to interact with uninstantiated
  * type vars of another group.
  */
trait TypeVars:
  private[sast] def add(tvar: TypeVar): Unit

  def typeVars: List[TypeVar]

  /** The id of the nested container of type variables
    *
    * It is used in initializing type variables: for a constraint a1 = a2, the
    * initialization will prefer the variable with a higher depth.
    */
  def id: Int

  /** The instantiated type associated with the type variable
    *
    * Throws exception is the type var is not yet instantiated.
    */
  def instantiated(tvar: TypeVar): Type

  def isInstantiated(tvar: TypeVar): Boolean

  def isSubtype(tvar: TypeVar, tp: Type)(using Definitions): List[Subtyping.Task]

  def isSuptype(tvar: TypeVar, tp: Type)(using Definitions): List[Subtyping.Task]

  /** The state of inference will be reverted back if the test fails
    *
    * This invariant can only hold if the isolation pre-condition for TypeVars
    * is followed during type checking.
    */
  def tryOrRevert(test: => Boolean): Boolean
