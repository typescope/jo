package sast

import Symbols.Symbol

object Effects:
  import Policy.*

  /** Effect policy of ProcType */
  enum Policy:
    /** Effects will be inferred and nothing is captured */
    case Infer

    /** Effects will be inferred are captured except for the exceptions */
    case Capture(except: List[Symbol])

    /** Infer effects and check against the given bound */
    case CheckBound(effects: List[Symbol])

    def bound: Option[List[Symbol]] =
      this match
        case Infer => None
        case Capture(except) => Some(except)
        case CheckBound(effects) => Some(effects)

  def conforms(policy1: Policy, policy2: Policy): Boolean =
    policy1 match
      case Infer => false

      case Capture(except1) =>
        policy2 match
          case Infer => except1.isEmpty

          case Capture(except2) =>
            except1.forall(except2.contains)

          case CheckBound(bound2) =>
            except1.forall(bound2.contains)


      case CheckBound(bound1) =>
        policy2 match
          case Infer => bound1.isEmpty

          case Capture(except2) =>
            bound1.forall(except2.contains)

          case CheckBound(bound2) =>
            bound1.forall(bound2.contains)
