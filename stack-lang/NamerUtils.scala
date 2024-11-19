import scala.collection.mutable

import Types.*
import Symbols.*

object NamerUtils:
  /** Type provider for value definitions
    *
    * Cycles are forbidden and no fixed-point computation is performed.
    */
  final class ValueTypeProvider(using rp: Reporter) extends InfoProvider:
    /** All completers --- never removed  */
    private val completers = mutable.Map.empty[Symbol, InfoCompleter]

    /** The symbols currently in progress of being completed */
    private val completing = new mutable.ArrayBuffer[Symbol]

    /**
      * Add an info provider for the symbol
      *
      * @param initial the initial approximation type for the symbol without computation
      * @param compute compute the type for the symbol
      */
    def addProvider(sym: Symbol, provider: () => Type) =
      assert(!completers.contains(sym), "Duplicate provider " + sym)
      completers(sym) = new InfoCompleter(provider)

    def apply(sym: Symbol): Type = Debug.trace(s"Retriving $sym", (_: Type).show, enable = false):
      if !completers.contains(sym) then
        Reporter.abort("No completer for " + sym, sym.sourcePos)

      val completer = completers(sym)

      if completing.contains(sym) then
        val cycle = completing.dropWhile(_ != sym).map(_.name).mkString(", ")
        Reporter.error("Recursive function needs explicit return type: " + cycle, sym.sourcePos)
        ErrorType
      else if completing.contains(sym) then
        completer.currentType
      else if completer.isComplete then
        completer.currentType
      else
        completing += sym

        val tp = completer.compute()
        completer.complete(tp)

        completing -= sym
        tp

  private enum InfoState:
    case Incomplete
    case Completed(cache: Type)

  private class InfoCompleter(val compute: () => Type):

    private var state = InfoState.Incomplete

    def complete(tp: Type): Unit =
      assert(state != InfoState.Completed, "Double completion")
      state = InfoState.Completed(tp)

    def isComplete: Boolean = state.isInstanceOf[InfoState.Completed]

    def currentType: Type =
      state match
        case InfoState.Completed(tp) => tp

        case InfoState.Incomplete =>
          throw new Exception("Unexpected condition")
