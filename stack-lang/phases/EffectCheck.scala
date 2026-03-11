package phases

import ast.Positions.*
import sast.*
import sast.Trees.*
import sast.Symbols.Symbol
import reporting.Reporter

/** This phase checks effect usage and normalizes default/allow handling.
  *
  * It should run immediately after type checking, while code provider and
  * inferred effects still match the typed trees.
  */
class EffectCheck(using rp: Reporter, defn: Definitions) extends Phase:

  private def synthesizeDefaultBindings(params: List[Symbol], span: Span): List[Assign] =
    params.map: param =>
      val paramRef = Ident(param)(span)
      val defaultFunSym = param.defaultFunction
      val defaultValue = Ident(defaultFunSym)(span).appliedTo()
      Assign(paramRef, defaultValue)

  override  def transformFunDef(fdef: FunDef)(using Context): FunDef =
    val symbol = fdef.symbol

    // Check against effects from the concrete body of the function.
    val effs = defn.effectEngine.getBodyEffects(symbol)

    val rejectedDefaults =
      fdef.effectPolicy match
      case Effects.Policy.CheckBound(params) =>
        val allowed = params.toSet
        val pos = symbol.sourcePos

        // Default value functions of optional context parameters should not
        // depend on any optional context parameters.
        val allowDefault = !symbol.is(Flags.Default)

        val rejected = scala.collection.mutable.ListBuffer.empty[Symbol]
        for
          (eff, trace) <- effs
          if (!allowDefault || !eff.is(Flags.Default)) && !allowed.exists(param => eff == param)
        do
          Reporter.error("Parameter not allowed: " + eff, pos, trace)
          if eff.is(Flags.Default) then rejected += eff

        rejected.toList

      case _ =>
        Nil

    val fdef2 = super.transformFunDef(fdef)

    if rejectedDefaults.isEmpty then
      fdef2
    else
      val args = synthesizeDefaultBindings(rejectedDefaults, fdef.body.span)
      fdef2.copy(body = With(fdef2.body, args))(fdef.span)

  /** Check `allow`-clause */
  override def transformAllow(allowExpr: Allow)(using Context): Word =
    val expr2 = transform(allowExpr.expr)

    given Source = Phase.source.value
    val effsInner = defn.effectEngine.effects(allowExpr.expr)
    val allowed = allowExpr.params.map(_.symbol).toSet

    val unprovided = effsInner.filter((k, _) => !allowed.exists(param => k == param))

    val rejectedDefaults = scala.collection.mutable.ListBuffer.empty[Symbol]
    for
      (eff, trace) <- unprovided if !eff.is(Flags.Default)
    do
      Reporter.error("Parameter not allowed: " + eff, allowExpr.expr.pos, trace)
    for (eff, _) <- unprovided if eff.is(Flags.Default) do rejectedDefaults += eff

    if rejectedDefaults.isEmpty then
      expr2
    else
      With(expr2, synthesizeDefaultBindings(rejectedDefaults.toList, allowExpr.span))

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
