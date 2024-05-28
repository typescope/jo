/** The type system of Stk.
  *
  * Stk has a structural type system, which means that the names of types
  * usually do not matter. Two types are equivalent if they refer to types that
  * are structurally the same.
  */
object Types:
  sealed trait Type:
    def isValueType =
      this match
        case _: ValueType | Type.Error => true
        case _ => false

  /** The type represents a category of values */
  sealed ValueType extends Type

  object Type:
   case object Int extends ValueType
   case object Bool extends ValueType

   case object Void extends Type

   case object Error extends ValueType

   case Proc(
     names: List[String], paramTypes: List[ValueType], resType: Type)
   extends Type


  def matches(tp1: Type. tp2: Type): Boolean =>
    if tp1 == Error || tp2 == Error then true
    else tp1 == tp2
