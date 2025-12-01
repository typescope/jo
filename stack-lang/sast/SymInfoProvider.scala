package sast

import Types.Type
import Symbols.Symbol

import common.Debug
import reporting.Reporter

import scala.collection.mutable

/** Info provider for symbols
  *
  * Cycles are forbidden and no fixed-point computation is performed.
  */
final class SymInfoProvider(using rp: Reporter) extends InfoProvider:
  private val infos = mutable.Map.empty[Symbol, Type]

  private class InfoCompleter(
    val sym: Symbol, val compute: () => Type, val errorType: () => Type)

  /** Pending completers --- removed once completed  */
  private val completers = mutable.Map.empty[Symbol, InfoCompleter]

  /** The symbols currently in progress of being completed */
  private val completing = new mutable.ArrayBuffer[Symbol]

  def get(sym: Symbol): Option[Type] = Debug.trace(s"Forcing $sym", enable = false):
    infos.get(sym) match
      case res @ Some(_) => res

      case None =>
        if completers.contains(sym) then
          val completer = completers(sym)
          // cycle
          if completing.contains(sym) then
            val errorType = completer.errorType()
            val cycle = completing.dropWhile(_ != sym).map(_.name).mkString(", ")
            if sym.isFunction then
              Reporter.error("Recursive function needs explicit return type: " + cycle, sym.sourcePos)

            else
              Reporter.error("Illegal recursive definition: " + cycle, sym.sourcePos)

            completing.dropRightInPlace(cycle.size)

            infos(sym) = errorType
            Some(errorType)

          else
            completing += sym

            val info = completer.compute()

            completers -= sym
            completing -= sym

            // In case of cycles, prefer the error type instead of computed type
            infos.get(sym) match
              case res @ Some(_) => res

              case _ =>
                infos(sym) = info
                Some(info)
        else
          None

  def add(sym: Symbol, tp: Type): Unit =
    assert(!infos.contains(sym), "Duplicate symbol " + sym)
    infos(sym) = tp

  def addLazy(sym: Symbol, infoLazy: () => Type, errorType: () => Type): Unit =
    assert(!completers.contains(sym), "Duplicate provider " + sym)
    completers(sym) = new InfoCompleter(sym, infoLazy, errorType)
