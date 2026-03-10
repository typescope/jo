package sast

import ast.Positions.*

import Trees.*
import Types.*
import Symbols.Symbol

import common.Debug

import scala.collection.mutable


import EffectAnalysis.*

/** Performs effect inference
  *
  * Note that for effect analysis we need to make sure the CodeProvider provides
  * code for functions as they are just after type checking.
  *
  * This is currently guaranteed by running the EffectCheck phase immediately
  * after type checking.
  */
class EffectAnalysis:
  /** Computed stable effects for function bodies
    *
    * Do not cache policy effects here. Otherwise, it will break the semantics
    * for getBodyEffects.
    */
  private val stableBodyEffects: mutable.Map[Symbol, TracedEffects] = mutable.Map.empty

  /** Compute effects of the given function
    *
    * It should only be called from outside. Internally, `getEffects` should be
    * called.
    */
  def effects(fun: Symbol)(using defn: Definitions): TracedEffects =
    fixpoint(this)(getEffects(fun, ignoreSpec = false))

  /** Compute effects of the given word
    *
    * It should only be called from outside. Internally, `EffectAnalyzer.apply`
    * should be called.
    */
  def effects(word: Word)(using defn: Definitions, source: Source): TracedEffects =
    fixpoint(this)(EffectAnalyzer.apply(word))

  /** Compute effects directly from the current body of a function symbol.
    *
    * Precondition: the function should not be loaded from sast files
    */
  def getBodyEffects(fun: Symbol)(using defn: Definitions): TracedEffects =
    stableBodyEffects.get(fun) match
      case Some(res) => res
      case None =>
        val fdef = defn.getCode(fun)
        given Source = fun.sourcePos.source
        fixpoint(this)(EffectAnalyzer.apply(fdef.body))

  def getStableBodyEffects(fun: Symbol): Option[TracedEffects] =
    stableBodyEffects.get(fun)

  def getKnownEffects(fun: Symbol)(using defn: Definitions): Option[List[Symbol]] =
    if fun.isOneOf(Flags.Defer | Flags.Loaded) then
      val procType = fun.info.asProcType
      Some(procType.receives)

    else
      defn.getCodeOpt(fun) match
        case Some(fdef) =>
          // Respect effect policy boundary -- only compute effects for Policy.Infer
          fdef.effectPolicy.bound match
            case res @ Some(_) => res

            case None =>
              stableBodyEffects.get(fun).map: res =>
                res.keys.toList

        case None =>
          // must have code for non-loaded functions
          throw new Exception("No code for " + fun)

  /** Commit fixed point result to stable cache */
  private def commit(stableEffs: Map[Symbol, TracedEffects]): Unit =
    for (sym, effs) <- stableEffs do
       assert(!stableBodyEffects.contains(sym), sym)
       stableBodyEffects(sym) = effs

object EffectAnalysis:
  type Trace = Vector[SourcePosition]
  type TracedEffects = Map[Symbol, Trace]

  /** Prefer shorter trace in merge */
  extension (teffs1: TracedEffects) def +++ (teffs2: TracedEffects): TracedEffects =
    teffs1.foldLeft(teffs2): (acc, pair) =>
      val (k, v1) = pair
      acc.get(k) match
        case Some(v2) => acc.updated(k, if v1.size > v2.size then v2 else v1)
        case None => acc.updated(k, v1)

  /** The fixed point computation stops if the in cache is equal to out cache.
    *
    * For termination, it is important that the function is monotone.
    *
    * See https://en.wikipedia.org/wiki/Knaster%E2%80%93Tarski_theorem
    */
  private def fixpoint(engine: EffectAnalysis)(doTask: TempCache ?=> TracedEffects): TracedEffects =
    given temp: TempCache = TempCache()
    var effs = doTask
    while temp.isUsed && temp.hasChanged do
      temp.reset()
      effs = doTask
    end while

    // move temp to global stable cache
    engine.commit(temp.stable)

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
    *
    * Invariant: cache only stores effects for computed effects of body
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

    def stable: Map[Symbol, TracedEffects] = out

    def getOrElse(fun: Symbol)(otherwise: => TracedEffects): TracedEffects =
      out.get(fun) match
        case Some(res) =>
          used = true
          res

        case _ =>
          otherwise
    end getOrElse

  end TempCache

  /** Produce a list of transitively reachabe param symbols for the function */
  private def getEffects(fun: Symbol, ignoreSpec: Boolean)(using temp: TempCache, defn: Definitions): TracedEffects =
    // Usage of stable cache has to be part of the computation for speed

    if fun.isOneOf(Flags.Defer | Flags.Loaded) then
      val procType = fun.info.asProcType
      procType.receives.map(_ -> Vector.empty).toMap

    else
      // prefer body effects for better error diagnosis
      defn.effectEngine.getStableBodyEffects(fun) match
        case Some(res) => res

        case None =>
          // Must have code for non-loaded functions
          val fdef = defn.getCodeOpt(fun) match
            case Some(code) => code
            case None => throw new Exception("No code for " + fun)

          fdef.effectPolicy.bound match
            case Some(effs) if !ignoreSpec =>
              effs.map(_ -> Vector.empty).toMap

            case _ =>
              // Read from out cache to make sure the computation is performed once.
              //
              // Make sure cache only stores effects for computed effects of body
              //
              // Otherwise, getBodyEffects will have wrong semantics.
              temp.getOrElse(fun):
                given Source = fun.sourcePos.source
                temp.init(fun)
                val body = fdef.body
                val effects = EffectAnalyzer.apply(body)
                temp.update(fun, effects)
                effects

  private object EffectAnalyzer:
    val zero = Map.empty[Symbol, Trace]

    def apply(pattern: Pattern)(using temp: TempCache, source: Source, defn: Definitions): TracedEffects =
      pattern match
        case BindPattern(id, nested) => this(nested)

        case TypePattern(_, nested) => this(nested)

        case ApplyPattern(fun, nested) =>
          nested.foldLeft(zero): (acc, pat) =>
            acc ++ this(pat)

        case OrPattern(lhs, rhs) => this(lhs) +++ this(rhs)

        case AndPattern(lhs, rhs) => this(lhs) +++ this(rhs)

        case NotPattern(nested) => this(nested)

        case ValuePattern(value) => this(value)

        case GuardPattern(cond) => this(cond)

        case AssignPattern(assigns) =>
          assigns.foldLeft(zero): (acc, assign) =>
            acc +++ this(assign.rhs)

        case _: WildcardPattern => zero

        case SeqPattern(parts) =>
          parts.foldLeft(zero): (acc, part) =>
            val effs = part match
              case AtomPattern(pattern) => this(pattern)
              case RepeatPattern(_, guard) => guard.map(this.apply).getOrElse(zero)

            acc +++ effs

    def apply(word: Word)(using temp: TempCache, source: Source, defn: Definitions): TracedEffects = Debug.trace("effects for " + word.show, enable = false):
      word match
        case _: Literal => zero

        case Ident(sym) =>
          if sym.is(Flags.Context) then
            Map(sym -> Vector(word.pos))

          else if sym.isFunction then
            for (eff, trace) <- getEffects(sym, ignoreSpec = true) yield
              eff -> (word.pos +: trace)

          else zero

        case Select(qual, name) =>
          val effs = this(qual)
          if word.tpe.isProcType then
            // a select with a ProcType must be a method call
            val procType = word.tpe.asProcType
            val callEffs =
              if qual.tpe.isClassInfoType then
                assert(word.tpe.is[Types.RefType], "Ref type expected, found = " + word.tpe + ", word = " + word.show)
                val sym = word.tpe.as[Types.RefType].symbol

                for (eff, trace) <- getEffects(sym, ignoreSpec = true) yield
                   eff -> (word.pos +: trace)
              else
                procType.receives.map(_ -> Vector(word.pos))

            effs +++ callEffs.toMap
          else
            effs

        case RecordLit(fields) =>
          fields.foldLeft(zero):
            case (acc, (_, rhs)) => acc +++ this(rhs)


        case Encoded(repr) =>
          this(repr)

        case Apply(fun, args, autos) =>
          // Method calls are handled in `Select`, procedure in `Ident`
          val acc1 = this(fun)
          val acc2 = args.foldLeft(acc1): (acc, arg) =>
            acc +++ this(arg)

          autos.foldLeft(acc2): (acc, auto) =>
            acc +++ this(auto)

        case TypeApply(fun, targs) =>
          this(fun)

        case New(_) =>
          zero

        case With(expr, args) =>
          val effsInner = this(expr)
          val effsArgs = args.foldLeft(zero): (acc, arg) =>
            acc +++ this(arg.rhs)

          val masked = args.map(_.symbol)
          val unmasked = effsInner -- masked

          unmasked +++ effsArgs

        case Allow(expr, params) =>
          val effsInner = this(expr)
          val allowedSet = params.map(_.symbol).toSet
          effsInner.filter((k, _) => allowedSet.contains(k))

        case Assign(ident, rhs, _) =>
          this(rhs)

        case FieldAssign(Select(qual, _), rhs) =>
          this(qual)
          this(rhs)

        case If(cond, thenp, elsep) =>
          this(cond) +++ this(thenp) +++ this(elsep)

        case While(cond, body) =>
          this(cond) +++ this(body)

        case Labeled(_, _, body) =>
          this(body)

        case Return(_, value) =>
          this(value)

        case IsExpr(scrutinee, pattern) =>
          this(scrutinee) +++ this(pattern)

        case ClassTest(value, _) =>
          this(value)

        case Match(scrut, cases) =>
          this(scrut) +++ cases.foldLeft(zero): (acc, caseDef) =>
            acc +++ this(caseDef.pattern) +++ this(caseDef.body)

        case PatValDef(pattern, rhs) =>
          this(pattern) +++ this(rhs)

        case Block(words) =>
          words.foldLeft(zero): (acc, word) =>
            acc +++ this(word)

        case Lambda(symbol, params, receives, body) =>
          // For lambdas, compute effects of the body and apply capture semantics
          val bodyEffects = this(body)
          // Use receives from the Lambda tree directly
          bodyEffects -- receives

        case _: Def => zero
    end apply
