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
  def add(tvar: TypeVar): Unit

  def typeVars: List[TypeVar]

  /** The instantiated type associated with the type variable
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
