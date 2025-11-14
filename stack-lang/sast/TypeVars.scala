package sast

import Types.{ Type, TypeVar }

/** The shared context for inferencing a group of type variables
  *
  * A type variable belongs exactly to a single context and must be constrained
  * and instantiated at the end of the context.
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
