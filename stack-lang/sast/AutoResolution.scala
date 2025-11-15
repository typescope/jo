package sast

import Types.*
import Trees.*

import ast.Positions.Span

object AutoResolution:
  enum Result:
    case Success(args: List[Word])
    case Failure(message: String)

  def resolve(procType: ProcType, havings: List[Ident], span: Span)(using Definitions): Result = ???
