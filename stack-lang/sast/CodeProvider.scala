package sast

import Sast.Def
import Symbols.Symbol

import scala.collection.mutable

class CodeProvider:
  private val codebase = mutable.Map.empty[Symbol, Def]

  def get(sym: Symbol): Def = codebase(sym)

  def contains(sym: Symbol): Boolean = codebase.contains(sym)

  def add(sym: Symbol, code: Def): Unit =
    assert(!codebase.contains(sym), "Duplicate addition to code base, sym = " + sym)
    codebase(sym) = code
