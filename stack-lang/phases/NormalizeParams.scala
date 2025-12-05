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

  /** Capture all context parameters used in the methods of an object
    *
    * An object
    *
    *     {
    *       def foo() = ...
    *       def bar() = ...
    *       def baz() = ...
    *     }
    *
    * is transformed to
    *
    *     val a = param1
    *     val b = param2
    *
    *     {
    *       def foo() = ... with param1 = a
    *       def bar() = ... with param2 = b
    *       def baz() = ... with param1 = a, param2 = b
    *     }
    *
    * Closure conversion will later turn `a` and `b` to fields of the object.
    */
  override def transformObject(obj: Object)(using ctx: Context): Word =
    val aliasMap = mutable.Map.empty[Symbol, Assign]

    given Source = ctx.sourcePos.source
    val span = obj.span

    val members2 = obj.members.map:
      case ddef: FunDef =>
        val effsTraced = defn.effectEngine.effects(ddef.symbol)
        val effs = (effsTraced -- ddef.effectPolicy.bound.getOrElse(Nil)).keys.toList

        if effs.isEmpty then
          val body2 = this(ddef.body)
          ddef.copy(body = body2)(ddef.span)
        else
          val args =
            for eff <- effs yield
              val paramRef = Ident(eff)(span)
              aliasMap.get(eff) match
                case None =>
                  val alias =
                    TermSymbol.create("alias_" + eff.name, eff.info, Flags.Synthetic,
                        visibility = Visibility.Default,
                        owner = ctx,
                        pos = obj.pos)

                  aliasMap(eff) = Assign(Ident(alias)(span), paramRef)
                  Assign(paramRef, Ident(alias)(span))

                case Some(vdef) =>
                  Assign(paramRef, Ident(vdef.symbol)(span))
              end match
            end for
          val body2 = With(this(ddef.body), args)
          ddef.copy(body = body2)(ddef.span)
      case vdef => vdef


    val aliases = aliasMap.values.toSeq
    val obj2 = obj.copy(members = members2)(obj.tpe, obj.span)
    Block((aliases :+ obj2).toList)(obj.span)

  override def transformGuardPattern(pat: GuardPattern)(using ctx: Context): Pattern =
    pat.copy(pattern = this(pat.pattern), guard = this(pat.guard))

  override def transformBindPattern(pat: BindPattern)(using ctx: Context): Pattern =
    val assigns =
      for ass <- pat.bindings
      yield ass.copy(rhs = this(ass.rhs))

    pat.copy(pattern = this(pat.pattern), bindings = assigns)

  override def transformValuePattern(pat: ValuePattern)(using ctx: Context): Pattern =
    pat.copy(value = this(pat.value))(pat.scrutineeType)
