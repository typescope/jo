package typing

import sast.*
import sast.Sast.*
import sast.Symbols.Symbol

import scala.collection.mutable

object EffectAnalysis:
  /** The stable cache for effects of functions */
  class Cache(
    val effects: mutable.Map[Symbol, Vector[Symbol]],
    val code: mutable.Map[Symbol, FunDef])

  /** Compute effects of the given function
    *
    * It should only be called from outside. Internally, `getEffects` should be
    * called.
    */
  def effects(fun: Symbol)(using cache: Cache): Vector[Symbol] =
    fixpoint(getEffects(fun))

  /** Compute effects of the given word
    *
    * It should only be called from outside. Internally, `EffectAnalyzer.apply`
    * should be called.
    */
  def effects(word: Word)(using cache: Cache): Vector[Symbol] =
    fixpoint(EffectAnalyzer.apply(word))

  private def fixpoint(doTask: TempCache ?=> Vector[Symbol])(using cache: Cache): Vector[Symbol] =
    given temp: TempCache = TempCache()
    var effs = doTask
    while temp.isUsed && temp.hasChanged do
      temp.reset()
      effs = doTask
    end while

    // move temp to global stable cache
    for (sym, effs) <- temp.effects do
      assert(!cache.effects.contains(sym), sym)
      cache.effects(sym) = effs

    effs

  /** Temporary caches are for temporary result of mutually recursive functions */
  private class TempCache(val effects: mutable.Map[Symbol, Vector[Symbol]]):
    /** Whether the temp cache has changed */
    private var changed: Boolean = false

    /** Whether the temp cache has been used */
    private var used: Boolean = false

    def this() = this(mutable.Map.empty)

    def isUsed: Boolean = used

    def hasChanged: Boolean = changed

    def reset(): Unit =
      used = false
      changed = false

    def update(fun: Symbol, effs: Vector[Symbol]): Unit =
      effects.get(fun) match
        case Some(res) =>
          changed = effs.exists(eff => !res.contains(eff))

        case None =>

      effects(fun) = effs

    def getOrElse(fun: Symbol)(otherwise: => Vector[Symbol]): Vector[Symbol] =
      effects.get(fun) match
        case Some(res) =>
          used = true
          res

        case _ =>
          otherwise

  /** Produce a list of transitively reachabe param symbols for the function */
  private def getEffects(fun: Symbol)(using cache: Cache, temp: TempCache): Vector[Symbol] =
    cache.effects.get(fun) match
      case Some(res) => res

      case None =>
        temp.getOrElse(fun):
          temp.update(fun, effs = Vector.empty)
          val body = cache.code(fun).body
          val effects = EffectAnalyzer.apply(body)
          temp.update(fun, effects)
          effects

  private object EffectAnalyzer:
    val zero = Vector.empty[Symbol]

    def apply(word: Word)(using cache: Cache, temp: TempCache): Vector[Symbol] =
      word match
        case _: Literal => zero

        case Ident(sym) =>
          if sym.isAllOf(Flags.Context | Flags.Param) then Vector(sym)
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
          args.foldLeft(this(fun)): (acc, arg) =>
            acc ++ this(arg)

        case TypeApply(fun, targs) =>
          this(fun)

        case With(expr, args, allow) =>
          val effsInner = this(expr)
          val effsArgs = args.foldLeft(zero): (acc, arg) =>
            acc ++ this(arg.rhs)

          val masked = args.map(_.paramRef.symbol)
          val unmasked = effsInner.filter(eff => !masked.contains(eff))
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
            acc ++ this(ddef.body)

        case fdef: FunDef =>
          cache.code(fdef.symbol) = fdef
          zero

        case tdef: TypeDef => zero
    end apply
  end EffectAnalyzer
