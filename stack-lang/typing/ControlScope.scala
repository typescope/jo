package typing

import sast.Symbols.Symbol
import sast.Types.Type

final class LoopFrame(val breakLabel: Symbol, val continueLabel: Symbol):
  private var breakUsed: Boolean = false
  private var continueUsed: Boolean = false

  def markBreakUsed(): Unit =
    breakUsed = true

  def markContinueUsed(): Unit =
    continueUsed = true

  def isBreakUsed: Boolean =
    breakUsed

  def isContinueUsed: Boolean =
    continueUsed

  def isUsed: Boolean =
    breakUsed || continueUsed

final case class ControlScope(
  funReturn: Option[(Symbol, Type)],
  loops: List[LoopFrame],
  inLambda: Boolean
):
  def enterLoop(frame: LoopFrame): ControlScope =
    copy(loops = frame :: loops)

object ControlScope:
  val NoReturn: ControlScope = ControlScope(funReturn = None, loops = Nil, inLambda = false)

  def fun(sym: Symbol, resultType: Type): ControlScope =
    ControlScope(funReturn = Some((sym, resultType)), loops = Nil, inLambda = false)

  val InLambda: ControlScope = ControlScope(funReturn = None, loops = Nil, inLambda = true)
