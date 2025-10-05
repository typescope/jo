package sast

import Trees.FunDef
import Symbols.Symbol

import scala.collection.mutable

class CodeProvider(codebase: mutable.Map[Symbol, FunDef]):
  def this() = this(mutable.Map.empty)

  def get(sym: Symbol): FunDef = codebase(sym)

  def contains(sym: Symbol): Boolean = codebase.contains(sym)

  def set(sym: Symbol, code: FunDef): Unit =
    codebase(sym) = code

  def snapshot(): CodeProvider =
    new CodeProvider(codebase.map(_ -> _))
