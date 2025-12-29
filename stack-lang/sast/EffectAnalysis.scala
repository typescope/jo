package sast

import ast.Positions.*

import Trees.*
import Types.*
import Symbols.Symbol

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
  /** Computed stable effects for functions */
  private val stableEffects: mutable.Map[Symbol, TracedEffects] = mutable.Map.empty

  /** Compute effects of the given function
    *
    * It should only be called from outside. Internally, `getEffects` should be
    * called.
    */
  def effects(fun: Symbol)(using defn: Definitions): TracedEffects =
    getStable(fun) match
      case Some(effs) =>
        effs

      case None =>
        fixpoint(this)(getEffects(fun, ignoreSpec = true))

  /** Compute effects of the given word
    *
    * It should only be called from outside. Internally, `EffectAnalyzer.apply`
    * should be called.
    */
  def effects(word: Word)(using defn: Definitions, source: Source): TracedEffects =
    fixpoint(this)(EffectAnalyzer.apply(word))

  def getStable(fun: Symbol)(using Definitions): Option[TracedEffects] =
    stableEffects.get(fun) match
      case None =>
        if fun.is(Flags.Loaded) then
          val procType = fun.info.as[Types.ProcType]
          Some(procType.receives.map(_ -> Vector.empty).toMap)
        else
          None

      case res => res

  /** Commit fixed point result to stable cache */
  private def commit(stableEffs: Map[Symbol, TracedEffects]): Unit =
    for (sym, effs) <- stableEffs do
       assert(!stableEffects.contains(sym), sym)
       stableEffects(sym) = effs

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
    val funSym = fun

    defn.effectEngine.getStable(funSym) match
      case Some(res) => res

      case None =>
        // Read from out cache to make sure the computation is performed once.
        temp.getOrElse(funSym):
          val fdef = defn.getCode(funSym)

          // Respect effect policy boundary -- only compute effects for Policy.Infer
          fdef.effectPolicy.bound match
            case Some(effs) if !ignoreSpec =>
              effs.map(_ -> Vector.empty).toMap

            case _ =>
              given Source = funSym.sourcePos.source
              temp.init(funSym)
              val body = fdef.body
              val effects = EffectAnalyzer.apply(body)
              temp.update(funSym, effects)
              effects

  private object EffectAnalyzer:
    val zero = Map.empty[Symbol, Trace]

    def apply(pattern: Pattern)(using temp: TempCache, source: Source, defn: Definitions): TracedEffects =
      pattern match
        case BindPattern(id, nested) => this(nested)

        case _: TypePattern => zero

        case ApplyPattern(fun, nested) =>
          nested.foldLeft(zero): (acc, pat) =>
            acc ++ this(pat)

        case OrPattern(lhs, rhs) => this(lhs) ++ this(rhs)

        case AndPattern(lhs, rhs) => this(lhs) ++ this(rhs)

        case ValuePattern(value) => this(value)

        case GuardPattern(cond) => this(cond)

        case AssignPattern(assigns) =>
          assigns.foldLeft(zero): (acc, assign) =>
            acc ++ this(assign.rhs)

        case _: WildcardPattern => zero

        case SeqPattern(parts) =>
          parts.foldLeft(zero): (acc, part) =>
            val effs = part match
              case AtomPattern(pattern) => this(pattern)
              case SkipToPattern(pattern) => this(pattern)
              case RestPattern(pattern) => this(pattern)
              case StarPattern(pattern) => this(pattern)

            acc ++ effs

    def apply(word: Word)(using temp: TempCache, source: Source, defn: Definitions): TracedEffects =
      word match
        case _: Literal => zero

        case Ident(sym) =>
          if sym.is(Flags.Context) then
            Map(sym -> Vector(word.pos))

          else if sym.isFunction then
            for (eff, trace) <- getEffects(sym, ignoreSpec = false) yield
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

                for (eff, trace) <- getEffects(sym, ignoreSpec = false) yield
                   eff -> (word.pos +: trace)
              else
                procType.receives.map(_ -> Vector(word.pos))

            effs ++ callEffs
          else
            effs

        case RecordLit(fields) =>
          fields.foldLeft(zero):
            case (acc, (f, rhs)) => acc ++ this(rhs)


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

        case New(_) =>
          zero

        case With(expr, args) =>
          val effsInner = this(expr)
          val effsArgs = args.foldLeft(zero): (acc, arg) =>
            acc ++ this(arg.rhs)

          val masked = args.map(_.symbol)
          val unmasked = effsInner -- masked

          unmasked ++ effsArgs

        case Allow(expr, params) =>
          val effsInner = this(expr)
          val allowedSet = params.map(_.symbol).toSet
          effsInner.filter((k, _) => allowedSet.contains(k))

        case Assign(ident, rhs) =>
          this(rhs)

        case FieldAssign(Select(qual, _), rhs) =>
          this(qual)
          this(rhs)

        case vdef: ValDef => this(vdef.rhs)

        case If(cond, thenp, elsep) =>
          this(cond) ++ this(thenp) ++ this(elsep)

        case While(cond, body) =>
          this(cond) ++ this(body)

        case IsExpr(scrutinee, pattern) =>
          this(scrutinee) ++ this(pattern)

        case Match(scrut, cases) =>
          this(scrut) ++ cases.foldLeft(zero): (acc, caseDef) =>
            acc ++ this(caseDef.pattern) ++ this(caseDef.body)

        case CaseDef(pattern, rhs) =>
          this(pattern) ++ this(rhs)

        case Block(words) =>
          words.foldLeft(zero): (acc, word) =>
            acc ++ this(word)

        case Object(self, members) =>
          members.foldLeft(zero): (acc, member) =>
            member match
              case vdef: ValDef =>
                acc ++ this(vdef.rhs)

              case fdef: FunDef =>
                val rawEffects = getEffects(fdef.symbol, ignoreSpec = true)
                fdef.effectPolicy.bound match
                  case Some(except) =>
                    acc ++ (rawEffects -- except)

                  case None =>
                    acc ++ rawEffects

        case Lambda(symbol, params, body) =>
          // For lambdas, compute effects of the body and apply capture semantics
          val bodyEffects = this(body)
          // Lambda type contains receives info for capture
          word.tpe match
            case lambdaType: LambdaType =>
              bodyEffects -- lambdaType.receives
            case tp =>
              throw new Exception("Unexpected type of lambdas, found = " + tp)

        case _: Def => zero
    end apply
