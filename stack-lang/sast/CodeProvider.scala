package sast

import Sast.FunDef
import Symbols.Symbol

import scala.collection.mutable

class CodeProvider:
  private val codebase = mutable.Map.empty[Symbol, FunDef]

  def get(sym: Symbol): FunDef = codebase(sym)

  def contains(sym: Symbol): Boolean = codebase.contains(sym)

  def set(sym: Symbol, code: FunDef): Unit =
    codebase(sym) = code
