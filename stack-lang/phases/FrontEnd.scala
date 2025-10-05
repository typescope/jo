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

      // normalizer must run before patmat to check effects of guard patterns
      val noramlizer = new phases.NormalizeParams
      val encoder = new phases.EncodeTagged
      val patmat = new phases.PatternMatcher

      sast       |>
      noramlizer |>
      patmat     |>
      encoder
