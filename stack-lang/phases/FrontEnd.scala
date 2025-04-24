package phases

import ast.Ast
import sast.*
import sast.Sast.*

import parsing.Parser
import typing.Typer
import reporting.Reporter

object FrontEnd:

  def run(
    stdlib: List[String], runtime: List[String], sources: List[String])
    (using rp: Reporter)
  : List[Namespace] =
    val rootNameTable = new NameTable
    val runtimeNameTable = new NameTable

    given lazyDefn: Definitions.Lazy = new Definitions.Lazy(rootNameTable)
    run(stdlib, runtime, sources, runtimeNameTable)

  def run(
    stdlib: List[String], runtime: List[String], sources: List[String],
    runtimeNameTable: NameTable)
    (using defnLazy: Definitions.Lazy, rp: Reporter)
  : List[Namespace] =

    given Definitions = defnLazy.value

    val typeCheck = (nss: List[Ast.Namespace]) =>
      Typer.check(nss, stdlib, runtime, runtimeNameTable)

    val sast =
      Parser.parse(sources)         |>
      typeCheck                     |+
      Printing.peek(enable = false) |>
      TreeChecker.check

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
