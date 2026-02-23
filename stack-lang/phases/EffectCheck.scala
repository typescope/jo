package phases

import ast.Positions.*
import sast.*
import sast.Trees.*
import reporting.Reporter

/** This phase check the usage of effects
  *
  * This phase should run immediately after type checking and DO NOT transform
  * any definitions. Otherwise, the code provider will interfere with effect
  * inference and check.
  */
class EffectCheck(using rp: Reporter, defn: Definitions) extends Phase:

  override  def transformFunDef(fdef: FunDef)(using Context): FunDef =
    val symbol = fdef.symbol

    // force computing effects
    val effs = defn.effectEngine.effects(symbol)

    fdef.effectPolicy match
      case Effects.Policy.CheckBound(params) =>
        val allowed = params.toSet
        val pos = symbol.sourcePos

        // Default value functions of optional context parameters should not
        // depend on any optional context parameters.
        val allowDefault = !symbol.is(Flags.Default)

        for
          (eff, trace) <- effs
          if (!allowDefault || !eff.is(Flags.Default)) && !allowed.exists(param => eff == param)
        do
          Reporter.error("Parameter not allowed: " + eff, pos, trace)

      case _ =>

    super.transformFunDef(fdef)

  /** Check `allow`-clause */
  override def transformAllow(allowExpr: Allow)(using Context): Word =
    transform(allowExpr.expr)

    given Source = Phase.source.value
    val effsInner = defn.effectEngine.effects(allowExpr.expr)
    val allowed = allowExpr.params.map(_.symbol).toSet

    val unprovided = effsInner.filter((k, _) => !allowed.exists(param => k == param))

    for
      (eff, trace) <- unprovided if !eff.is(Flags.Default)
    do
      Reporter.error("Parameter not allowed: " + eff, allowExpr.expr.pos, trace)

    allowExpr

  private def checkTermInPattern(word: Word)(using Context): Word =
    given Source = Phase.source.value
    val effs = defn.effectEngine.effects(word)

    for
      (eff, trace) <- effs
    do
      Reporter.error("External context parameters not allowed in patterns: " + eff, word.pos, trace)

    // The code might still bind and use default parameters
    this(word)

  override def transformGuardPattern(pat: GuardPattern)(using Context): Pattern =
    checkTermInPattern(pat.guard)
    pat

  override def transformValuePattern(pat: ValuePattern)(using Context): Pattern =
    checkTermInPattern(pat.value)
    pat

  override def transformAssignPattern(pat: AssignPattern)(using Context): Pattern =
    for ass <- pat.assignments do checkTermInPattern(ass.rhs)

    pat
