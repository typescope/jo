package sast

import Sast.*
import Symbols.Symbol

import ast.Positions.Source
import reporting.Reporter

object Effects:
  import Policy.*

  /** Effect policy of ProcType */
  enum Policy:
    /** Effects will be inferred and nothing is captured */
    case Infer

    /** Effects will be inferred and captured except for the exceptions */
    case Capture(except: List[Symbol])

    /** Effects will be inferred and captured */
    case InferCapture

    /** Infer effects and check against the given bound */
    case CheckBound(effects: List[Symbol])

    def bound: Option[List[Symbol]] =
      this match
        case Infer | InferCapture => None
        case Capture(except) => Some(except)
        case CheckBound(effects) => Some(effects)

  def conforms(policy1: Policy, policy2: Policy): Boolean =
    policy1 match
      case Infer => false

      case InferCapture => true

      case Capture(except1) =>
        policy2 match
          case Infer | InferCapture => except1.isEmpty

          case Capture(except2) =>
            except1.forall(except2.contains)

          case CheckBound(bound2) =>
            except1.forall(bound2.contains)

      case CheckBound(bound1) =>
        policy2 match
          case Infer | InferCapture => bound1.isEmpty

          case Capture(except2) =>
            bound1.forall(except2.contains)

          case CheckBound(bound2) =>
            bound1.forall(bound2.contains)

  def checkEffectsConform(effs: List[Ident], policy: Policy)(using Reporter, Source) =
    policy.bound match
      case Some(allowed)  =>
          for eff <- effs if !allowed.contains(eff.symbol) do
            Reporter.error("Parameter not allowed from expected type: " + eff.symbol, eff.pos)

      case None =>
