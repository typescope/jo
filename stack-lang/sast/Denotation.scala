package sast

/** The denotation of a symbol: what a symbol refers to or describes.
  *
  * Subtypes:
  *
  * - Type (expression/surface types),
  * - ClassInfo (class descriptor),
  * - TypeLambda (generic class/alias descriptor),
  * - NameTable (namespace descriptor).
  */
abstract class Denotation:
  def as[T <: Denotation]: T = asInstanceOf[T]

  def asType: Types.Type = asInstanceOf[Types.Type]

  def isType: Boolean = this.isInstanceOf[Types.Type]
