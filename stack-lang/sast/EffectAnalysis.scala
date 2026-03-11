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
    private inline def merge(acc: TracedEffects, effs: TracedEffects): TracedEffects =
      effs.foldLeft(acc): (acc, pair) =>
        val k = pair._1
        val v1 = pair._2
        acc.get(k) match
          case Some(v2) => acc.updated(k, if v1.size > v2.size then v2 else v1)
          case _ => acc.updated(k, v1)

    def apply(pattern: Pattern)(using temp: TempCache, source: Source, defn: Definitions): TracedEffects =
      apply(pattern, zero)

    def apply(pattern: Pattern, acc: TracedEffects)(using temp: TempCache, source: Source, defn: Definitions): TracedEffects =
      pattern match
        case BindPattern(_, nested) =>
          apply(nested, acc)

        case TypePattern(_, nested) =>
          apply(nested, acc)

        case ApplyPattern(_, nested) =>
          nested.foldLeft(acc): (acc1, pat) =>
            apply(pat, acc1)

        case OrPattern(lhs, rhs) =>
          apply(rhs, apply(lhs, acc))

        case AndPattern(lhs, rhs) =>
          apply(rhs, apply(lhs, acc))

        case NotPattern(nested) =>
          apply(nested, acc)

        case ValuePattern(value) =>
          apply(value, acc)

        case GuardPattern(cond) =>
          apply(cond, acc)

        case AssignPattern(assigns) =>
          assigns.foldLeft(acc): (acc1, assign) =>
            apply(assign.rhs, acc1)

        case _: WildcardPattern =>
          acc

        case SeqPattern(parts) =>
          parts.foldLeft(acc): (acc1, part) =>
            part match
              case AtomPattern(pattern) =>
                apply(pattern, acc1)

              case RepeatPattern(_, guard) =>
                guard match
                  case Some(word) => apply(word, acc1)
                  case None => acc1

    def apply(word: Word)(using temp: TempCache, source: Source, defn: Definitions): TracedEffects =
      apply(word, zero)

    def apply(word: Word, acc: TracedEffects)(using temp: TempCache, source: Source, defn: Definitions): TracedEffects =
      Debug.trace("effects for " + word.show, enable = false):
        word match
          case _: Literal =>
            acc

          case Ident(sym) =>
            if sym.is(Flags.Context) then
              acc.updated(sym, Vector(word.pos))

            else if sym.isFunction then
              val effs =
                for (eff, trace) <- getEffects(sym, ignoreSpec = true) yield
                  eff -> (word.pos +: trace)
              merge(acc, effs)

            else
              acc

          case Select(qual, _) =>
            val acc1 = apply(qual, acc)
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

              merge(acc1, callEffs.toMap)
            else
              acc1

          case RecordLit(fields) =>
            fields.foldLeft(acc):
              case (acc1, (_, rhs)) => apply(rhs, acc1)

          case Encoded(repr) =>
            apply(repr, acc)

          case Apply(fun, args, autos) =>
            // Method calls are handled in `Select`, procedure in `Ident`
            val acc1 = apply(fun, acc)
            val acc2 = args.foldLeft(acc1): (accNext, arg) =>
              apply(arg, accNext)

            autos.foldLeft(acc2): (accNext, auto) =>
              apply(auto, accNext)

          case TypeApply(fun, _) =>
            apply(fun, acc)

          case New(_) =>
            acc

          case With(expr, args) =>
            val effsInner = apply(expr)

            val masked = args.map(_.symbol)
            val unmasked = effsInner -- masked

            val effsArgs = args.foldLeft(unmasked): (acc1, arg) =>
              apply(arg.rhs, acc1)

            merge(acc, effsArgs)

          case Allow(expr, params) =>
            val effsInner = apply(expr)
            val allowedSet = params.map(_.symbol).toSet
            merge(acc, effsInner.filter((k, _) => allowedSet.contains(k)))

          case Assign(_, rhs, _) =>
            apply(rhs, acc)

          case FieldAssign(Select(qual, _), rhs) =>
            apply(qual)
            apply(rhs, acc)

          case If(cond, thenp, elsep) =>
            apply(elsep, apply(thenp, apply(cond, acc)))

          case While(cond, body) =>
            apply(body, apply(cond, acc))

          case Labeled(_, _, body) =>
            apply(body, acc)

          case Return(_, value) =>
            apply(value, acc)

          case IsExpr(scrutinee, pattern) =>
            apply(pattern, apply(scrutinee, acc))

          case ClassTest(value, _) =>
            apply(value, acc)

          case Match(scrut, cases) =>
            val acc1 = apply(scrut, acc)
            cases.foldLeft(acc1): (accCase, caseDef) =>
              apply(caseDef.body, apply(caseDef.pattern, accCase))

          case PatValDef(pattern, rhs) =>
            apply(rhs, apply(pattern, acc))

          case Block(words) =>
            words.foldLeft(acc): (acc1, word) =>
              apply(word, acc1)

          case Lambda(_, _, receives, body) =>
            // For lambdas, compute effects of the body and apply capture semantics.
            val bodyEffects = apply(body)
            // Use receives from the Lambda tree directly.
            merge(acc, bodyEffects -- receives)

          case _: Def =>
            acc
    end apply
