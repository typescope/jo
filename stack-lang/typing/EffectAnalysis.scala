package typing

import sast.*
import sast.Sast.*
import sast.Symbols.Symbol

import scala.collection.mutable

object EffectAnalysis:
  /** The stable cache for effects of functions */
  class Cache(
    val effects: mutable.Map[Symbol, Set[Symbol]],
    val code: mutable.Map[Symbol, FunDef]):

    def this() = this(mutable.Map.empty, mutable.Map.empty)

  /** Compute effects of the given function
    *
    * It should only be called from outside. Internally, `getEffects` should be
    * called.
    */
  def effects(fun: Symbol)(using cache: Cache): Set[Symbol] =
    fixpoint(getEffects(fun))

  /** Compute effects of the given word
    *
    * It should only be called from outside. Internally, `EffectAnalyzer.apply`
    * should be called.
    */
  def effects(word: Word)(using cache: Cache): Set[Symbol] =
    fixpoint(EffectAnalyzer.apply(word))

  /** The fixed point computation stops if the in cache is equal to out cache.
    *
    * For termination, it is important that the function is monotone.
    *
    * See https://en.wikipedia.org/wiki/Knaster%E2%80%93Tarski_theorem
    */
  private def fixpoint(doTask: TempCache ?=> Set[Symbol])(using cache: Cache): Set[Symbol] =
    given temp: TempCache = TempCache()
    var effs = doTask
    while temp.isUsed && temp.hasChanged do
      temp.reset()
      effs = doTask
    end while

    // move temp to global stable cache
    temp.commit(cache)

    effs

  /** Temporary caches are for temporary result of mutually recursive functions
    *
    * The in cache is only used to provide initial values for out cache. It
    * should never be read directly.
    *
    * The out cache should be read lazily such that computation is performed
    * once in each round. The laziness is implemented by emptying `out` at
    * the beginning of each round.
    */
  private class TempCache(
    private var in: Map[Symbol, Set[Symbol]],
    private var out: Map[Symbol, Set[Symbol]]):

    /** Whether the out cache has been used */
    private var used: Boolean = false

    def this() = this(Map.empty, Map.empty)

    def isUsed: Boolean = used

    def hasChanged: Boolean = in != out

    def reset(): Unit =
      used = false
      in = out
      // Important to empty out cache to force computation once in each round
      out = Map.empty

    def init(fun: Symbol): Unit =
      out = out.updated(fun, in.getOrElse(fun, Set.empty))

    def update(fun: Symbol, effs: Set[Symbol]): Unit =
      out = out.updated(fun, effs)

    def getOrElse(fun: Symbol)(otherwise: => Set[Symbol]): Set[Symbol] =
      out.get(fun) match
        case Some(res) =>
          used = true
          res

        case _ =>
          otherwise

    /** Commit fixed point result to stable cache */
    def commit(cache: Cache): Unit =
      for (sym, effs) <- this.out do
        assert(!cache.effects.contains(sym), sym)
        cache.effects(sym) = effs

  /** Produce a list of transitively reachabe param symbols for the function */
  private def getEffects(fun: Symbol)(using cache: Cache, temp: TempCache): Set[Symbol] =
    // Usage of stable cache has to be part of the computation for performance.
    cache.effects.get(fun) match
      case Some(res) => res

      case None =>
        // Read from out cache to make sure the computation is performance once.
        temp.getOrElse(fun):
          temp.init(fun)
          val body = cache.code(fun).body
          val effects = EffectAnalyzer.apply(body)
          temp.update(fun, effects)
          effects

  private object EffectAnalyzer:
    val zero = Set.empty[Symbol]

    def apply(word: Word)(using cache: Cache, temp: TempCache): Set[Symbol] =
      word match
        case _: Literal => zero

        case Ident(sym) =>
          // Method calls will not contribute effects as each method is
          // self-sufficient after deep capture.
          if sym.isAllOf(Flags.Context | Flags.Param) then Set(sym)
          else if sym.isFunction then getEffects(sym)
          else zero

        case Select(qual, name) =>
          this(qual)

        case RecordLit(fields) =>
          fields.foldLeft(zero):
            case (acc, (f, rhs)) => acc ++ this(rhs)

        case Encoded(repr) =>
          this(repr)

        case Apply(fun, args) =>
          // Method calls will not contribute effects as each method is
          // self-sufficient after deep capture.
          args.foldLeft(this(fun)): (acc, arg) =>
            acc ++ this(arg)

        case TypeApply(fun, targs) =>
          this(fun)

        case With(expr, args, allow) =>
          allow match
            case Some(ids) =>
              ids.map(_.symbol).toSet

            case None =>
              val effsInner = this(expr)
              val effsArgs = args.foldLeft(zero): (acc, arg) =>
                acc ++ this(arg.rhs)

              val masked = args.map(_.paramRef.symbol)
              val unmasked = effsInner -- masked

              effsArgs ++ unmasked

        case Assign(ident, rhs) =>
          this(rhs)

        case FieldAssign(qual, name, rhs) =>
          this(qual)
          this(rhs)

        case vdef: ValDef => this(vdef.rhs)

        case If(cond, thenp, elsep) =>
          this(cond) ++ this(thenp) ++ this(elsep)

        case While(cond, body) =>
          this(cond) ++ this(body)

        case Block(words) =>
          words.foldLeft(zero): (acc, word) =>
            acc ++ this(word)

        case Object(self, vals, defs) =>
          val effs = vals.foldLeft(zero): (acc, vdef) =>
            acc ++ this(vdef.rhs)

          defs.foldLeft(effs): (acc, ddef) =>
            // Cache the effects for method such that it can be used for the
            // deep capture transform.
            //
            // Method calls will not contribute effects as each method is
            // self-sufficient after deep capture.
            cache.code(ddef.symbol) = ddef
            acc ++ getEffects(ddef.symbol)

        case fdef: FunDef =>
          cache.code(fdef.symbol) = fdef
          zero

        case tdef: TypeDef => zero
    end apply
  end EffectAnalyzer
