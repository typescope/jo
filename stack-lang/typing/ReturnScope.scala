package typing

import sast.Symbols.Symbol
import sast.Types.Type

enum ReturnScope:
  case NoReturn
  case InLambda
  case Fun(sym: Symbol, resultType: Type)
