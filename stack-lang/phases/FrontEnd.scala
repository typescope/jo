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
    (using Reporter)
  : List[Namespace] =

    val rootNameTable = new NameTable
    val runtimeNameTable = new NameTable
    run(stdlib, runtime, sources, rootNameTable, runtimeNameTable)

  def run(
    stdlib: List[String], runtime: List[String], sources: List[String],
    rootNameTable: NameTable, runtimeNameTable: NameTable)
    (using Reporter)
  : List[Namespace] =

    val typeCheck = (nss: List[Ast.Namespace]) =>
      Typer.check(nss, stdlib, runtime, rootNameTable, runtimeNameTable)

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
