import Symbols.Symbol

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

    def isTypeRef: Boolean = this.isInstanceOf[Type.TypeRef]

    def isValueType: Boolean =
      this match
        case Type.Int | Type.Bool | _: Type.TypeRef | Type.Error => true
        case _ => false

    def resultType: Type =
      this match
        case Type.Proc(_, _, resType) => resType
        case _ => throw new Exception("Not a proc type: " + this)

  object Type:
    case object Int extends Type
    case object Bool extends Type

    case object Void extends Type

    case object Error extends Type

    case class TypeRef(symbol: Symbol) extends Type

    case class Proc(
      names: List[String], paramTypes: List[Type], resType: Type)
    extends Type:
      val paramCount = paramTypes.size
      val resCount = if resType.isValueType then 1 else 0

  def dealias(tp: Type): Type =
    tp match
      case Type.TypeRef(sym) => sym.info
      case _ => tp

  // TODO: handle non-termination
  def matches(tp1: Type, tp2: Type): Boolean =
    tp1.isError
    || tp2.isError
    || tp1 == tp2
    || tp1.isTypeRef && matches(dealias(tp1), tp2)
    || tp2.isTypeRef && matches(tp1, dealias(tp2))
