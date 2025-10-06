package phases

import sast.*
import sast.Trees.*

import typing.Typer
import reporting.Config
import reporting.Reporter

object FrontEnd:
  def run
      (runtime: List[String], sources: List[String])
      (using defnLazy: Definitions.Lazy, rp: Reporter, cf: Config)
  : List[Namespace] =
    val sast = sources |> Typer.parseStep |> Typer.typeStep(runtime)

    locally:
      given Definitions = defnLazy.value

      val encoder = new phases.EncodeTagged
      val patmat = new phases.PatternMatcher
      val normalizer = new phases.NormalizeParams

      sast       |>
      normalizer |>
      patmat     |>
      encoder
