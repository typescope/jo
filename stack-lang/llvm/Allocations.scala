package llvm

import sast.*
import sast.Trees.*
import sast.Symbols.*

import scala.collection.mutable

/** The functions that may allocate on the heap
  *
  * A function may allocate if it builds a record, calls the allocator of the
  * runtime, calls a function indirectly, or calls a function that may
  * allocate. A function that cannot allocate needs no region: the region
  * would be empty whenever it closes.
  *
  * A branch that ends in a call that never returns, like the failure of an
  * assertion, does not count: the program stops there.
  */
class Allocations(funDefs: collection.Map[Symbol, FunDef], runtime: LLVMRuntime, resolve: Symbol => Symbol)(using defn: Definitions):
  private val allocators = Set(resolve(runtime.GC_alloc), runtime.Regions_allocResult)

  private val allocating = mutable.Set.empty[Symbol]

  locally:
    val callers = mutable.Map.empty[Symbol, List[Symbol]]
    val work = mutable.Queue.empty[Symbol]

    for (sym, fdef) <- funDefs do
      val scan = new Scan
      scan(fdef.body)(using ())
      if scan.direct then
        allocating += sym
        work += sym

      for callee <- scan.callees do
        callers(callee) = sym :: callers.getOrElse(callee, Nil)

    while work.nonEmpty do
      val sym = work.dequeue()
      for caller <- callers.getOrElse(sym, Nil) if allocating.add(caller) do
        work += caller

  /** Whether a function may allocate, true for a function without definition */
  def mayAllocate(sym: Symbol): Boolean =
    val target = resolve(sym)
    allocating.contains(target) || !funDefs.contains(target)

  /** Whether a word, such as a loop, may allocate */
  def mayAllocate(word: Word): Boolean =
    val scan = new Scan
    scan(word)(using ())
    scan.direct || scan.callees.exists(mayAllocate)

  /** The direct allocations and the callees of a function body, as CodeGen compiles calls */
  private class Scan extends TreeTraverser:
    type Context = Unit

    var direct = false

    val callees = mutable.Set.empty[Symbol]

    def apply(word: Word)(using Unit): Unit =
      word match
        case _: RecordLit => direct = true

        case If(cond, thenp, elsep) =>
          this(cond)

          if !stops(thenp) then this(thenp)

          if !stops(elsep) then this(elsep)

        case app: Apply =>
          directCallee(app) match
            case Some(sym) =>
              if sym.hasAnnotation(defn.intrinsic) then
                if sym == defn.Float_parseFloatDecimal then callees += runtime.Core_parseFloat
                else if sym == runtime.Core_initObjects then direct = true

              // An object accessor reads the object, a C function allocates no object
              else if !sym.is(Flags.Object) && sym.owner != runtime.LibC then
                val target = resolve(sym)
                if allocators.contains(target) then direct = true
                else callees += target

            case None =>
              // an indirect call may call anything
              direct = true

        case _ =>

      word match
        case _: If =>
        case _ => recur(word)

  /** Whether a word ends in a call that never returns, and cannot return from the function */
  private def stops(word: Word): Boolean =
    def endsInStop(word: Word): Boolean =
      word match
        case Block(words) => words.nonEmpty && endsInStop(words.last)
        case Encoded(repr) => endsInStop(repr)
        case app: Apply => app.tpe.dealias.isBottomType
        case _ => false

    endsInStop(word) && !hasReturn(word)

  private def hasReturn(word: Word): Boolean =
    var found = false
    val finder = new TreeTraverser:
      type Context = Unit

      def apply(word: Word)(using Unit): Unit =
        word match
          case _: Return => found = true
          case _ => recur(word)

    finder(word)(using ())
    found
