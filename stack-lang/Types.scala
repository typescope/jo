/** The type system of Stk.
  *
  * Stk has a structural type system, which means that the names of types
  * usually do not matter. Two types are equivalent if they refer to types that
  * are structurally the same.
  */
object Types:
  enum Type:
    case Unit, Int, Bool
    case Proc(names: List[String], paramTypes: List[Type], resType: Type)
