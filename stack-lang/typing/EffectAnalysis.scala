package typing

import ast.Positions.*

import sast.*
import sast.Sast.*
import sast.Symbols.Symbol
import sast.Effects.*

import scala.collection.mutable

object EffectAnalysis:
  type Trace = Vector[SourcePosition]
  type TracedEffects = Map[Symbol, Trace]

  /** The stable cache for effects of functions */
  class Cache(
    val effects: mutable.Map[Symbol, TracedEffects],
    val code: mutable.Map[Symbol, FunDef]):

    def this() = this(mutable.Map.empty, mutable.Map.empty)

  /** Compute effects of the given function
    *
    * It should only be called from outside. Internally, `getEffects` should be
    * called.
    */
  def effects(fun: Symbol)(using cache: Cache, defn: Definitions): TracedEffects =
    fixpoint(getEffects(fun))

  /** Compute effects of the given word
    *
    * It should only be called from outside. Internally, `EffectAnalyzer.apply`
    * should be called.
    */
  def effects(word: Word)(using cache: Cache, source: Source, defn: Definitions): TracedEffects =
    fixpoint(EffectAnalyzer.apply(word))

  /** The fixed point computation stops if the in cache is equal to out cache.
    *
    * For termination, it is important that the function is monotone.
    *
    * See https://en.wikipedia.org/wiki/Knaster%E2%80%93Tarski_theorem
    */
  private def fixpoint(doTask: TempCache ?=> TracedEffects)(using cache: Cache): TracedEffects =
    given temp: TempCache = TempCache()
    var effs = doTask
    while temp.isUsed && temp.hasChanged do
      temp.reset()
      effs = doTask
    end while

    // move temp to global stable cache
    temp.commit(cache)

    effs

  /** Temporary caches are used for computing effects of mutually recursive functions
    *
    * The `in` cache is the result from last round of computation. It is only
    * used to provide initial values for `out` cache of the current round. It
    * should never be read directly.
    *
    * The `out` cache should be read lazily such that computation is performed
    * once in each round. The laziness is implemented by emptying `out` at
    * the beginning of each round.
    */
  private class TempCache(
    private var in: Map[Symbol, TracedEffects],
    private var out: Map[Symbol, TracedEffects]):

    /** Whether the out cache has been used */
    private var used: Boolean = false

    def this() = this(Map.empty, Map.empty)

    def isUsed: Boolean = used

    def hasChanged: Boolean =
      // Changes in trace are ignored
      in.size != out.size || out.exists: (k, v) =>
        v.keySet != in(k).keySet

    def reset(): Unit =
      used = false
      in = out
      // Important to empty out cache to force computation once in each round
      out = Map.empty

    def init(fun: Symbol): Unit =
      out = out.updated(fun, in.getOrElse(fun, Map.empty))

    def update(fun: Symbol, effs: TracedEffects): Unit =
      out = out.updated(fun, effs)

    def getOrElse(fun: Symbol)(otherwise: => TracedEffects): TracedEffects =
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
  private def getEffects(fun: Symbol)(using cache: Cache, temp: TempCache, defn: Definitions): TracedEffects =
    // Usage of stable cache has to be part of the computation for speed
    val funSym = fun.dealias
    cache.effects.get(funSym) match
      case Some(res) => res

      case None =>
        // Read from out cache to make sure the computation is performed once.
        temp.getOrElse(funSym):
          given Source = funSym.sourcePos.source
          temp.init(funSym)
          val body = cache.code(funSym).body
          val effects = EffectAnalyzer.apply(body)
          temp.update(funSym, effects)
          effects

  private object EffectAnalyzer:
    val zero = Map.empty[Symbol, Trace]

    def apply(word: Word)(using cache: Cache, temp: TempCache, source: Source, defn: Definitions): TracedEffects =
      word match
        case _: Literal => zero

        case Ident(sym) =>
          if sym.isAllOf(Flags.Context | Flags.Param) then
            Map(sym -> Vector(word.pos))

          else if sym.isFunction then
            for (eff, trace) <- getEffects(sym) yield
              eff -> (word.pos +: trace)

          else zero

        case Select(qual, name) =>
          val effs = this(qual)
          if word.tpe.isProcType then
            // a select with a ProcType must be a method call
            val procType = word.tpe.asProcType
            effs ++ {
              procType.effectsBound match
                case Some(effs) => effs.map(_ -> Vector(word.pos))
                case _ =>
                  // TODO: Handle effects of methods
                  Nil
            }
          else
            effs

        case RecordLit(fields) =>
          fields.foldLeft(zero):
            case (acc, (f, rhs)) => acc ++ this(rhs)

        case TaggedLit(_, args) =>
          args.foldLeft(zero):
            case (acc, arg) => acc ++ this(arg)

        case Encoded(repr) =>
          this(repr)

        case Apply(fun, args, autos) =>
          // Method calls are handled in `Select`, procedure in `Ident`
          val acc1 = this(fun)
          val acc2 = args.foldLeft(acc1): (acc, arg) =>
            acc ++ this(arg)

          autos.foldLeft(acc2): (acc, auto) =>
            acc ++ this(auto)

        case TypeApply(fun, targs) =>
          this(fun)

        case New(classRef, targs, args, autos) =>
          val acc1 = args.foldLeft(zero): (acc, arg) =>
            acc ++ this(arg)

          autos.foldLeft(acc1): (acc, auto) =>
            acc ++ this(auto)

        case With(expr, args) =>
          val effsInner = this(expr)
          val effsArgs = args.foldLeft(zero): (acc, arg) =>
            acc ++ this(arg.rhs)

          val masked = args.map(_.paramRef.symbol)
          val unmasked = effsInner -- masked

          unmasked ++ effsArgs

        case Allow(expr, params) =>
          val effsInner = this(expr)
          val allowedSet = params.map(_.symbol).toSet
          effsInner.filter((k, _) => allowedSet.contains(k))

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

        case Match(scrut, cases) =>
          this(scrut) ++ cases.foldLeft(zero): (acc, caseDef) =>
            acc ++ this(caseDef.body)

        case Block(words) =>
          words.foldLeft(zero): (acc, word) =>
            acc ++ this(word)

        case Object(self, vals, defs) =>
          val effs = vals.foldLeft(zero): (acc, vdef) =>
            acc ++ this(vdef.rhs)

          defs.foldLeft(effs): (acc, ddef) =>
            // Cache the effects for method such that it can be used for the
            // deep capture transform.
            cache.code(ddef.symbol) = ddef
            ddef.effectPolicy match
              case Policy.Infer => acc

              case Policy.Capture(except) =>
                val rawEffects = getEffects(ddef.symbol)
                acc ++ (rawEffects -- except)

              case Policy.CheckBound(bound) =>
                acc ++ bound.map(_ -> Vector(word.pos))

        case fdef: FunDef =>
          cache.code(fdef.symbol) = fdef
          zero

        case pdef: PatDef => zero

        case tdef: TypeDef => zero
    end apply
  end EffectAnalyzer
