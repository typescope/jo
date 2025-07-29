package sast

import Trees.*
import Symbols.Symbol

import ast.Positions.Source
import reporting.Reporter

object Effects:
  import Policy.*

  /** Effect policy of FunDef */
  enum Policy:
    /** Effects will be inferred and nothing is captured */
    case Infer

    /** Effects will be inferred and captured */
    case InferCapture

    /** Effects will be inferred and captured except for the exceptions */
    case Capture(except: List[Symbol])

    /** Infer effects and check against the given bound */
    case CheckBound(effects: List[Symbol])

    def bound: Option[List[Symbol]] =
      this match
        case Capture(except) => Some(except)
        case CheckBound(effects) => Some(effects)
        case InferCapture => Some(Nil)
        case _ => None

  def checkEffectsConform(effs: List[Ident], policy: Policy)(using Reporter, Source) =
    policy.bound match
      case Some(allowed)  =>
          for eff <- effs if !allowed.contains(eff.symbol) do
            Reporter.error("Context parameter not allowed from expected type: " + eff.symbol, eff.pos)

      case None =>
