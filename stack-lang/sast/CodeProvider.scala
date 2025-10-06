package sast

import Trees.FunDef
import Symbols.Symbol

import scala.collection.mutable

class CodeProvider(codebase: mutable.Map[Symbol, FunDef]):
  def this() = this(mutable.Map.empty)

  def get(sym: Symbol): Option[FunDef] = codebase.get(sym)

  def set(sym: Symbol, code: FunDef): Unit =
    codebase(sym) = code
