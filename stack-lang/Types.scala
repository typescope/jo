/** The type system of Stk.
  *
  * Stk has a structural type system, which means that the names of types
  * usually do not matter. Two types are equivalent if they refer to types that
  * are structurally the same.
  */
object Types:
  sealed trait Type:
    def isError: Boolean = this == Type.Error

    def isVoid: Boolean = this == Type.Void

    def isValueType: Boolean =
      this match
        case _: ValueType | Type.Error => true
        case _ => false

  /** The type represents a category of values */
  sealed trait ValueType extends Type

  object Type:
   case object Int extends ValueType
   case object Bool extends ValueType

   case object Void extends Type

   case object Error extends ValueType

   case class Proc(
     names: List[String], paramTypes: List[ValueType], resType: Type)
   extends Type:
     val paramCount = paramTypes.size
     val resCount = if resType.isValueType then 1 else 0

  def matches(tp1: Type, tp2: Type): Boolean =
    if tp1.isError || tp2.isError then true
    else tp1 == tp2
