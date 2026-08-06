package phases

import sast.Constant
import sast.Symbols.Symbol
import sast.Trees.*

import scala.collection.mutable

/** Numbering for context parameters: filled by `LowerContextParams`, read by
  * whichever backend wants it.
  *
  * `LowerContextParams` rewrites a context read into
  * `getParam(ctx, paramKey(n))`, where `n` is this numbering rather than the
  * parameter itself. That matters because `paramKey`'s argument is consumed
  * for its *identity*, never as a value — but an argument typed `T` attracts
  * whatever boxing, casting or lambda adaptation `T`'s representation
  * requires, and every backend then had to see back through those wrappers to
  * recover the symbol. Each kept its own hand-maintained list of shapes an
  * argument might have been rewritten into, the lists disagreed, and they grew
  * a case at a time as failures appeared. An `Int` literal carries no `T`, so
  * nothing is inserted around it and there is nothing to see through.
  *
  * A key is only ever compared for equality, so the numbering needs to be
  * unique, not meaningful. It is assigned in first-encounter order during the
  * single lowering pass, which covers linked library code too: `.sast` stores
  * pre-lowering typed trees, so every unit — local or linked — is numbered by
  * the same pass in the same compilation.
  *
  * That is the invariant to preserve: **if a future mode ever emits *lowered*
  * artifacts, this numbering must move into the pickle or be derived from the
  * name.** Symbol names are globally unique for free; integers are only unique
  * because one authority hands them out.
  */
class ContextParamKeys:
  private val keys = mutable.Map.empty[Symbol, Int]
  private val symbols = mutable.ArrayBuffer.empty[Symbol]

  /** The key for `sym`, assigning one if this is the first encounter. */
  def keyOf(sym: Symbol): Int =
    keys.getOrElseUpdate(sym, {
      symbols += sym
      symbols.size - 1
    })

  /** The parameter `key` denotes. Backends use this to build their own key
    * representation — an interned name, a global variable — from the symbol,
    * exactly as they did before, just without reading it back out of the tree.
    */
  def symbolOf(key: Int): Symbol =
    if key >= 0 && key < symbols.size then symbols(key)
    else throw new Exception("No context parameter for key " + key)

  def size: Int = symbols.size

object ContextParamKeys:
  /** The key an argument to `paramKey` denotes.
    *
    * It is an `Int` literal by construction: `LowerContextParams` emits one,
    * and an `Int`-typed argument attracts no coercion on any backend, so it
    * arrives here unchanged. Failing loudly rather than hunting for a symbol
    * keeps that a checked property instead of an assumption — this is the
    * one place a backend needs to know anything about the argument's shape,
    * and there is exactly one shape.
    */
  def intKeyOf(arg: Word): Int = arg match
    case Literal(Constant.Int(n)) => n.toInt
    case _ => throw new Exception("paramKey expects an Int literal key, found: " + arg)
