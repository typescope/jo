package typing

import sast.Symbols.Symbol
import sast.Types.Type

final case class LoopFrame(breakLabel: Symbol, continueLabel: Symbol)

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
