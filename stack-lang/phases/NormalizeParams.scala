package phases

import ast.Positions.*
import sast.*
import sast.Trees.*
import sast.Symbols.*

import scala.collection.mutable

/** This phase normalize the usage of context parameters and some others
  *
  * - All transitive captures of context parameters are made explicit in objects
  * - All unsupplied optional parameters are provided at effect boundaries
  * - Rewrite "expr allow x, y, z" to just "expr"
  *
  */
class NormalizeParams(using defn: Definitions) extends Phase[Symbol]:
  val contextObject = Phase.OwnerContext

  /** Bind optional context parameters at effect boundaries */
  override def transformFunDef(fdef: FunDef)(using ctx: Context): FunDef =
    val symbol = fdef.symbol
    given Source = symbol.source

    fdef.effectPolicy match
      case Effects.Policy.CheckBound(params) =>
        val effs = defn.effectEngine.effects(symbol)
        val allowed = params.toSet

        val rejectedDefaults =
          for
            (eff, trace) <- effs
            if eff.is(Flags.Default) && !allowed.exists(param => eff == param)
          yield
            eff

        val fdef2 = super.transformFunDef(fdef)

        if rejectedDefaults.isEmpty then
          fdef2
        else
          val args = synthesizeDefaultBindings(rejectedDefaults.toList, fdef.body.span)
          val body2 = With(fdef2.body, args)
          fdef2.copy(body = body2)(fdef.span)

      case _ =>
        super.transformFunDef(fdef)


  private def synthesizeDefaultBindings(params: List[Symbol], span: Span)(using Source): List[Assign] =
    params.map: param =>
      val paramRef = Ident(param)(span)
      val defaultFunSym = param.defaultFunction
      val defaultValue = Ident(defaultFunSym)(span).appliedTo()
      Assign(paramRef, defaultValue)

  /** Check `allow`-clause */
  override  def transformAllow(allowExpr: Allow)(using ctx: Context): Word =
    val expr2 = transform(allowExpr.expr)

    given Source = ctx.owner.sourcePos.source
    val effsInner = defn.effectEngine.effects(allowExpr.expr)
    val allowed = allowExpr.params.map(_.symbol).toSet

    val unprovided = effsInner.filter((k, _) => !allowed.exists(param => k == param))

    val rejectedDefaults = unprovided.keys.filter(_.is(Flags.Default)).toList
    if rejectedDefaults.isEmpty then
      expr2

    else
      val argsAdded = synthesizeDefaultBindings(rejectedDefaults, allowExpr.span)
      With(expr2, argsAdded)

  /** Capture all context parameters used in the lambda
    *
    * A lambda
    *
    *     (x_i: T_i) => ...
    *
    * is transformed to
    *
    *     val a = param1
    *     val b = param2
    *
    *     (x_i: T_i) => ... with param1 = a, param2 = b
    *
    * Closure conversion will later turn `a` and `b` to captured fields.
    */
  /** Capture all context parameters used in the lambda
    *
    * A lambda
    *
    *     (x_i: T_i) => ...
    *
    * is transformed to
    *
    *     val a = param1
    *     val b = param2
    *
    *     (x_i: T_i) => ... with param1 = a, param2 = b
    *
    * Closure conversion will later turn `a` and `b` to captured fields.
    */
  override def transformLambda(lam: Lambda)(using ctx: Context): Word =
    val (lam2, assigns) = deepCaptureTransform(lam)

    if assigns.isEmpty then lam2
    else Block(assigns :+ lam2)(lam.span)

  override def transformEncoded(encoded: Encoded)(using ctx: Context): Word =
      encoded.repr match
        case lam: Lambda if encoded.tpe.isLambdaInterface =>
          val (lam2, assigns) = deepCaptureTransform(lam)

          if assigns.isEmpty then Encoded(lam2)(encoded.tpe)
          else Block(assigns :+ Encoded(lam2)(encoded.tpe))(lam.span)

        case _ =>
          super.transformEncoded(encoded)


  private def deepCaptureTransform(lam: Lambda)(using ctx: Context): (Lambda, List[Assign]) =
    val Lambda(sym, params, receives, body) = lam
    val aliases = new mutable.ArrayBuffer[Assign]

    given Source = ctx.sourcePos.source
    val span = lam.span

    val effsTraced = defn.effectEngine.effects(body)
    val effs = (effsTraced -- receives).keys.toList

    if effs.isEmpty then
      val body2 = this(body)
      (lam.copy(body = body2)(span), Nil)

    else
      val args =
        for eff <- effs yield
          val paramRef = Ident(eff)(span)
          val alias =
            TermSymbol.create("alias_" + eff.name, eff.info, Flags.Synthetic,
                visibility = Visibility.Default,
                owner = ctx,
                pos = lam.pos)

          aliases += Assign(Ident(alias)(span), paramRef)
          Assign(paramRef, Ident(alias)(span))
        end for
      val body2 = With(this(body), args)
      (lam.copy(body = body2)(lam.span), aliases.toList)

  override def transformGuardPattern(pat: GuardPattern)(using ctx: Context): Pattern =
    GuardPattern(this(pat.guard))(pat.scrutineeType)

  override def transformBindPattern(pat: BindPattern)(using ctx: Context): Pattern =
    BindPattern(pat.id, this(pat.nested))(pat.isDefinition)

  override def transformValuePattern(pat: ValuePattern)(using ctx: Context): Pattern =
    pat.copy(value = this(pat.value))(pat.scrutineeType)

  override def transformAssignPattern(pat: AssignPattern)(using ctx: Context): Pattern =
    val assigns =
      for ass <- pat.assignments
      yield ass.copy(rhs = this(ass.rhs))

    pat.copy(assigns)(pat.scrutineeType)
