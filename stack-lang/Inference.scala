import Types.*

/** Type inference logic */
object Inference:
  enum TargetType:
    case Unknown
    case ValueType
    case ProperType // value type or void
    case Known(tpe: Type)
