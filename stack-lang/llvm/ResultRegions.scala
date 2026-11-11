package llvm

import sast.*
import sast.Trees.*
import sast.Symbols.*

import scala.collection.mutable

/** The allocations and calls of a function that make up its result
  *
  * A flow-insensitive analysis marks the values that must end up in the
  * result region of the function:
  *
  *   - the returned values
  *   - the value assigned to a marked variable
  *   - the fields of a marked object
  *   - the values stored into the object of a marked variable
  *   - the arguments of a marked call that flow into the callee's result
  *
  * A store into the object of a variable is a field assignment `x.f = v` or
  * an element store `a[i] = v`, a call of `Array.set` or of the intrinsic
  * `Array.setUnchecked`. A store into an object reached through fields of a
  * variable, `x.f.g = v` or `x.f[i] = v`, is a store into the object of `x`.
  *
  * A variable assigned more than once is never marked: the analysis cannot
  * tell which of its values is returned, and marking all of them would keep
  * the overwritten ones in the result region. A parameter is assigned by the
  * call, so a reassigned parameter is not marked either.
  *
  * The marked parameters are the summary of the function for its callers.
  * `resultParams` gives the summary of a callee, by the positions of the
  * parameters. A constructor stores its parameters into `this` and returns
  * it, so the summary covers constructors too.
  *
  * A marked allocation goes directly into the result region, and the callee
  * of a marked call inherits the result region, so neither has to be promoted
  * when the region closes.
  *
  * Allocations and calls inside a loop stay in the current region: a loop may
  * allocate many values of which only one is returned. The exception is a
  * store in a loop into the object of a marked variable that is assigned
  * outside any loop. Its stored expression, the right-hand side seen through
  * blocks, `if` branches and casts, is placed if it is a record literal or a
  * call. If it is a `new` expression, the object it creates is placed too: a
  * local of the block that is assigned a record literal. Nothing else is
  * placed inside a loop: not the arguments of the stored call, not the fields
  * of the record, and not a value that reaches the store through a variable.
  *
  * Placement only affects performance. The write barrier keeps every
  * placement sound.
  */
class ResultRegions(fdef: FunDef, resultParams: Symbol => Set[Int], isElementStore: Symbol => Boolean):
  /** Allocations that go to the result region */
  val allocations: mutable.Set[RecordLit] = identitySet()

  /** Calls whose callee inherits the result region */
  val calls: mutable.Set[Apply] = identitySet()

  /** The values assigned to a variable */
  private val assigns = mutable.Map.empty[Symbol, List[Word]]

  /** The values stored into the object of a variable */
  private val stores = mutable.Map.empty[Symbol, List[Word]]

  /** Variables assigned inside a loop */
  private val loopAssigned = mutable.Set.empty[Symbol]

  /** The locals that hold the object of a stored `new` expression */
  private val storedObjects = mutable.Set.empty[Symbol]

  /** The returned values */
  private val returns = new mutable.ArrayBuffer[Word]

  /** Allocations and calls inside a loop */
  private val inLoop: mutable.Set[Word] = identitySet()

  private val marked = mutable.Set.empty[Symbol]

  private val paramSyms = fdef.allParams.toSet

  private def identitySet[T <: AnyRef](): mutable.Set[T] =
    import scala.jdk.CollectionConverters.*
    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[T, java.lang.Boolean]).asScala

  collect(fdef.body, loop = false)
  returns += fdef.body
  returns.foreach(mark(_, stored = false))

  /** The positions of the parameters that flow into the result */
  val params: Set[Int] =
    fdef.allParams.zipWithIndex.collect { case (param, i) if marked.contains(param) => i }.toSet

  /** Record the assignments, stores, returns and loops of a word */
  private def collect(word: Word, loop: Boolean): Unit =
    word match
      case Block(words) => words.foreach(collect(_, loop))

      case Encoded(repr) => collect(repr, loop)

      case TypeApply(fun, _) => collect(fun, loop)

      case app: Apply =>
        if loop then inLoop += app

        // an element store `a[i] = v`, with the arguments `a`, `i` and `v`
        for
          sym <- directCallee(app)
          if isElementStore(sym)
          target <- variable(app.allArgs(0))
        do
          stores(target) = app.allArgs(2) :: stores.getOrElse(target, Nil)

        collect(app.fun, loop)
        app.allArgs.foreach(collect(_, loop))

      case assign: Assign =>
        if loop then loopAssigned += assign.symbol
        assigns(assign.symbol) = assign.rhs :: assigns.getOrElse(assign.symbol, Nil)
        collect(assign.rhs, loop)

      case FieldAssign(Select(qual, _), rhs) =>
        for sym <- variable(qual) do
          stores(sym) = rhs :: stores.getOrElse(sym, Nil)

        collect(qual, loop)
        collect(rhs, loop)

      case If(cond, thenp, elsep) =>
        collect(cond, loop)
        collect(thenp, loop)
        collect(elsep, loop)

      case While(cond, body) =>
        collect(cond, loop = true)
        collect(body, loop = true)

      case Labeled(_, _, body) => collect(body, loop)

      case Return(target, value) =>
        if target.is(Flags.Fun) then returns += value
        collect(value, loop)

      case rec: RecordLit =>
        if loop then inLoop += rec
        rec.args.foreach((_, arg) => collect(arg, loop))

      case Select(qual, _) => collect(qual, loop)

      case _ =>

  private def reassigned(sym: Symbol): Boolean =
    val n = assigns.getOrElse(sym, Nil).size
    if paramSyms.contains(sym) then n > 0 else n > 1

  /** The variable whose object a store target is, through fields */
  private def variable(word: Word): Option[Symbol] =
    word match
      case Encoded(repr) => variable(repr)
      case Ident(sym) if !sym.is(Flags.Fun) => Some(sym)
      case Select(qual, _) => variable(qual)
      case _ => None

  /** Whether a word is a record literal, seen through casts */
  private def isRecord(word: Word): Boolean =
    word match
      case Encoded(repr) => isRecord(repr)
      case _: RecordLit => true
      case _ => false

  /** Mark a value that must end up in the result region
    *
    * `stored`: the value is the stored expression of a store into the result,
    * so it is placed also inside a loop.
    */
  private def mark(word: Word, stored: Boolean): Unit =
    word match
      case Block(words) if words.nonEmpty =>
        // the object of a stored `new` expression
        if stored then
          for case assign: Assign <- words.init if isRecord(assign.rhs) do
            storedObjects += assign.symbol

        mark(words.last, stored)

      case Encoded(repr) => mark(repr, stored)

      case If(_, thenp, elsep) =>
        mark(thenp, stored)
        mark(elsep, stored)

      case Ident(sym) if !sym.is(Flags.Fun) =>
        if !reassigned(sym) && marked.add(sym) then
          assigns.getOrElse(sym, Nil).foreach(mark(_, storedObjects.contains(sym)))
          stores.getOrElse(sym, Nil).foreach(mark(_, !loopAssigned.contains(sym)))

      case rec: RecordLit =>
        if stored || !inLoop.contains(rec) then allocations += rec
        rec.args.foreach((_, arg) => mark(arg, stored = false))

      case app: Apply =>
        if stored || !inLoop.contains(app) then calls += app

        for
          sym <- directCallee(app)
          i <- resultParams(sym)
        do
          mark(app.allArgs(i), stored = false)

      case _ =>
