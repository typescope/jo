import scala.collection.mutable

import Types.*
import Symbols.*

object NamerUtils:
  /** Type provider for fun definitions that might involve cycles
    *
    * Fixed-point computation is performed for soundness.
    *
    * Only self-cycles are allowed.
    */
  final class CyclicTypeProvider(using rp: Reporter) extends InfoProvider:
    /** All pending completers --- never removed */
    private val completers = mutable.Map.empty[Symbol, InfoCompleter.FixedPoint]

    /** The symbols currently in progress of being completed */
    private val completing = new mutable.ArrayBuffer[Symbol]

    /**
      * Add an info provider for the symbol
      *
      * @param initial the initial approximation type for the symbol without computation
      * @param compute compute the type for the symbol
      */
    def addProvider(sym: Symbol, initial: Reporter => Type, compute: Reporter => Type) =
      assert(!completers.contains(sym), "Duplicate provider " + sym)
      completers(sym) = new InfoCompleter.FixedPoint(initial, compute)

    /**
      * We only allow self cycles, so it suffices to compute fixed point for the
      * current info completer.
      */
    def apply(sym: Symbol): Type = Debug.trace(s"Retriving $sym", (_: Type).show, enable = false):
      if !completers.contains(sym) then
        Reporter.abort("No completer for " + sym, sym.sourcePos)

      val completer = completers(sym)

      def iterate(current: Type)(using rp: Reporter): Type = Debug.trace(s"Compute type for $sym", (_: Type).show, enable = false):
        if Subtyping.conforms(current, completer.currentType) then
          // Due to monotonicity, prev <: current, now current <: prev,
          // thus fix-point has reached
          for item <- rp.reports do this.rp.report(item)
          current
        else
          // update cache, run another iteration
          completer.completing(current)
          // throw the old reporter away without reporting any errors
          val reporter = rp.withSource(sym.sourcePos.source).fresh()
          iterate(completer.compute(reporter))(using reporter)
      end iterate

      if completing.contains(sym) && completing.last != sym then
        val cycle = completing.dropWhile(_ != sym).map(_.name).mkString(", ")
        Reporter.error("Mutual recursion needs explicit return type: " + cycle, sym.sourcePos)
        completing.dropRightInPlace(cycle.size)
        val tp = completer.currentType
        completer.complete(tp)
        tp
      else if completing.contains(sym) then
        completer.currentType
      else if completer.isComplete then
        completer.currentType
      else
        completing += sym

        val tp0 = completer.initial(rp)
        completer.completing(tp0)

        // trigger at list one computation
        val reporter = rp.withSource(sym.sourcePos.source).fresh()
        val tp = iterate(completer.compute(reporter))(using reporter)
        completer.complete(tp)

        completing -= sym
        tp

  /** Type provider for value definitions
    *
    * No worries about cycles and no fixed-point computation is performed.
    */
  final class ValueTypeProvider(using rp: Reporter) extends InfoProvider:
    /** All completers --- never removed  */
    private val completers = mutable.Map.empty[Symbol, InfoCompleter.Simple]

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
      completers(sym) = new InfoCompleter.Simple(provider)

    def apply(sym: Symbol): Type = Debug.trace(s"Retriving $sym", (_: Type).show, enable = false):
      if !completers.contains(sym) then
        Reporter.abort("No completer for " + sym, sym.sourcePos)

      val completer = completers(sym)

      if completing.contains(sym) && completing.last != sym then
        val cycle = completing.dropWhile(_ != sym).map(_.name).mkString(", ")
        Reporter.error("Mutual recursion needs explicit return type: " + cycle, sym.sourcePos)
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
    case Completing(current: Type)
    case Completed(cache: Type)

  private enum InfoCompleter:
    case FixedPoint(initial: Reporter => Type, compute: Reporter => Type)
    case Simple(compute: () => Type)

    private var state = InfoState.Incomplete

    def complete(tp: Type): Unit =
      assert(state != InfoState.Completed, "Double completion")
      state = InfoState.Completed(tp)

    def completing(tp: Type): Unit =
      assert(state != InfoState.Completed, "monotonicity violated")
      state = InfoState.Completing(tp)

    def isComplete: Boolean = state.isInstanceOf[InfoState.Completed]

    def currentType: Type =
      state match
        case InfoState.Completing(tp) => tp

        case InfoState.Completed(tp) => tp

        case InfoState.Incomplete =>
           throw new Exception("Unexpected condition")
