package typing

import sast.*
import sast.Sast.*
import sast.Types.*

import ast.Positions.*
import reporting.Reporter

object Autos:
  def derive
      (target: Type, base: Word)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : Word =
    ???
