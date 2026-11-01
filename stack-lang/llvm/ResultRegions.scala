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
  *   - the values assigned to a marked variable
  *   - the fields of a marked object
  *   - the values stored into the object of a marked variable
  *   - the arguments of a marked call that flow into the callee's result
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
  * allocate many values of which only one is returned.
  *
  * Placement only affects performance. The write barrier keeps every
  * placement sound.
  */
class ResultRegions(fdef: FunDef, resultParams: Symbol => Set[Int]):
  /** Allocations that go to the result region */
  val allocations: mutable.Set[RecordLit] = identitySet()

  /** Calls whose callee inherits the result region */
  val calls: mutable.Set[Apply] = identitySet()

  /** The values assigned to a variable */
  private val assigns = mutable.Map.empty[Symbol, List[Word]]

  /** The values stored into fields of the object of a variable */
  private val stores = mutable.Map.empty[Symbol, List[Word]]

  /** The returned values */
  private val returns = new mutable.ArrayBuffer[Word]

  /** Allocations and calls inside a loop */
  private val inLoop: mutable.Set[Word] = identitySet()

  private val marked = mutable.Set.empty[Symbol]

  private def identitySet[T <: AnyRef](): mutable.Set[T] =
    import scala.jdk.CollectionConverters.*
    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[T, java.lang.Boolean]).asScala

  collect(fdef.body, loop = false)
  returns += fdef.body
  returns.foreach(mark)

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
        collect(app.fun, loop)
        app.allArgs.foreach(collect(_, loop))

      case assign: Assign =>
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

  /** The function a call calls directly, if any */
  private def callee(app: Apply): Option[Symbol] =
    app.fun match
      case Encoded(Ident(sym)) if sym.is(Flags.Fun) => Some(sym)
      case Ident(sym) if sym.is(Flags.Fun) => Some(sym)
      case _ => None

  /** The variable a word reads, if any */
  private def variable(word: Word): Option[Symbol] =
    word match
      case Encoded(repr) => variable(repr)
      case Ident(sym) if !sym.is(Flags.Fun) => Some(sym)
      case _ => None

  /** Mark a value that must end up in the result region */
  private def mark(word: Word): Unit =
    word match
      case Block(words) => if words.nonEmpty then mark(words.last)

      case Encoded(repr) => mark(repr)

      case If(_, thenp, elsep) =>
        mark(thenp)
        mark(elsep)

      case Ident(sym) if !sym.is(Flags.Fun) =>
        if marked.add(sym) then
          assigns.getOrElse(sym, Nil).foreach(mark)
          stores.getOrElse(sym, Nil).foreach(mark)

      case rec: RecordLit =>
        if !inLoop.contains(rec) then allocations += rec
        rec.args.foreach((_, arg) => mark(arg))

      case app: Apply =>
        if !inLoop.contains(app) then calls += app

        for
          sym <- callee(app)
          i <- resultParams(sym)
        do
          mark(app.allArgs(i))

      case _ =>
