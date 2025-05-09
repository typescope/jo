package phases

import ast.Ast
import sast.*
import sast.Sast.*

import parsing.Parser
import typing.Typer
import reporting.Reporter

object FrontEnd:
  def run
      (runtime: List[String], sources: List[String])
      (using defnLazy: Definitions.Lazy, rp: Reporter, cf: Reporter.Config)
  : List[Namespace] =

    given Definitions = defnLazy.value

    val typeCheck = (nss: List[Ast.Namespace]) =>
      Typer.check(nss, runtime)

    val sast =
      Parser.parse(sources)         |>
      typeCheck                     |+
      Printing.peek(enable = false) |>
      TreeChecker.check

    // normalizer must run before patmat to check effects of guard patterns
    val noramlizer = new phases.NormalizeParams
    val encoder = new phases.EncodeTagged
    val patmat = new phases.PatternMatcher

    sast                          |>
    noramlizer.transform          |>
    TreeChecker.check             |>
    Printing.peek(enable = false) |>
    patmat.transform              |>
    TreeChecker.check             |>
    Printing.peek(enable = false) |>
    encoder.transform             |>
    TreeChecker.check             |>
    Printing.peek(enable = false)
