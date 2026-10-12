package sast

import Symbols.Symbol
import Denotations.Denotation

import common.Debug
import reporting.Reporter

import scala.collection.mutable

/** Info provider for symbols
  *
  * Cycles are forbidden and no fixed-point computation is performed.
  */
final class SymInfoProvider(using rp: Reporter) extends InfoProvider:
  private val infos = mutable.Map.empty[Symbol, Denotation]

  private class InfoCompleter(val compute: () => Denotation, val errorInfo: () => Denotation)

  /** Pending completers --- removed once completed  */
  private val completers = mutable.Map.empty[Symbol, InfoCompleter]

  /** The symbols currently in progress of being completed. */
  private val completing = new mutable.ArrayBuffer[Symbol]

  /** Errors found by reentrant reads, retained until the outer completion finishes. */
  private val completionErrors = mutable.Map.empty[Symbol, Denotation]

  def get(sym: Symbol): Option[Denotation] = Debug.trace(s"Forcing $sym", enable = false):
    infos.get(sym) match
      case res @ Some(_) => res

      case None =>
        if completers.contains(sym) then
          val completer = completers(sym)
          val info = complete(sym, completer.compute, completer.errorInfo)
          completers -= sym
          infos(sym) = info
          Some(info)

        else
          None

  def add(sym: Symbol, info: Denotation): Unit =
    assert(!infos.contains(sym), "Duplicate symbol " + sym)
    infos(sym) = info

  def addLazy(sym: Symbol, infoLazy: () => Denotation, errorInfo: () => Denotation): Unit =
    assert(!completers.contains(sym), "Duplicate provider " + sym)
    completers(sym) = new InfoCompleter(infoLazy, errorInfo)

  def complete(sym: Symbol, compute: () => Denotation, errorInfo: () => Denotation): Denotation =
    if completing.contains(sym) then
      completionErrors.getOrElseUpdate(sym, {
        val info = errorInfo()
        val cycle = completing.dropWhile(_ != sym).map(_.name).mkString(", ")

        if sym.isFunction then
          Reporter.error("Recursive function needs explicit return type: " + cycle, sym.sourcePos)

        else
          Reporter.error("Illegal recursive definition: " + cycle, sym.sourcePos)

        info
      })

    else
      completing += sym

      try
        val info = compute()
        // A reentrant read may already have supplied the error result.
        completionErrors.getOrElse(sym, info)
      finally
        completing -= sym
        completionErrors -= sym
