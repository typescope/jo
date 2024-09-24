import Types.*

/** Type inference logic */
object Inference:
  enum TargetType:
    case Unknown
    case ValueType
    case ProperType // value type or void
    case Member(name: String)
    case Known(tpe: Type)

  trait Handler:
    def dealias(tvar: TypeVar): Type

    def approx(tvar: TypeVar, isUp: Boolean): Type

    def isSubtype(tvar: TypeVar, tp: Type): Boolean

    def isSuptype(tvar: TypeVar, tp: Type): Boolean
