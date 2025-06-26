package phases

import ast.Positions.*
import sast.*
import sast.Sast.*
import sast.Types.*
import sast.Symbols.*
import typing.EffectAnalysis
import reporting.Reporter

import scala.collection.mutable

/** This phase normalize the usage of context parameters and some others
  *
  * - All transitive captures of context parameters are made explicit in objects
  * - Checks are performed for `allow`-clauses
  * - Synthesize `None` for optional parameters if needed
  */
class NormalizeParams(using rp: Reporter, defn: Definitions) extends Phase[NormalizeParams.Context]:
  val contextObject = NormalizeParams.CacheContext

  val NoneType = TagType("None", params = Nil)

  override def transform(nss: List[Namespace]): List[Namespace] =
    val cache = EffectAnalysis.Cache()

    for ns <- nss do index(cache, ns.defs)

    for ns <- nss yield
      given Context = NormalizeParams.Context(cache, ns.symbol)
      transformNamespace(ns)

  private def index(cache: EffectAnalysis.Cache, defs: List[Def]): Unit =
    defs.map:
      case fdef: FunDef =>
        cache.code(fdef.symbol) = fdef

      case cdef: ClassDef =>
        for fdef <- cdef.funs do
          cache.code(fdef.symbol) = fdef

      case Section(symbol, defs) =>
        index(cache, defs)

      case _ =>


  /** Bind optional context parameters at program entry.
    *
    * Only bind optional context parameters whose default value are needed.
    *
    * TODO: Need to do the same for each thread.
    */
  override  def transformFunDef(fdef: FunDef)(using ctx: Context): FunDef =
    if !fdef.symbol.isLocal && fdef.name == "main" then
      val effs = EffectAnalysis.effects(fdef.symbol)(using ctx.cache)
      val fdef2 = super.transformFunDef(fdef)

      val pos = fdef.symbol.sourcePos
      for
        (eff, trace) <- effs
        if !eff.is(Flags.Option) && !defn.isRuntimeContextParam(eff)
      do
        Reporter.error("Context parameter not provided: " + eff, pos, trace)

      val defaultEffs = effs.keys.filter(_.is(Flags.Option)).toList
      if defaultEffs.isEmpty then
        fdef2

      else
        val args = synthesizeNoneBindings(defaultEffs, fdef2.body.span)
        val body2 = With(fdef2.body, args)(fdef2.body.tpe, fdef2.body.span)
        fdef2.copy(body = body2)(fdef.span)

    else
      val symbol = fdef.symbol
      if symbol.isLocal then ctx.cache.code(symbol) = fdef

      if symbol.isFunction then
        fdef.effectPolicy match
          case Effects.Policy.CheckBound(params) =>
            val allowed = params.map(_.dealias).toSet
            val effs = EffectAnalysis.effects(symbol)(using ctx.cache)
            val pos = symbol.sourcePos
            for (eff, trace) <- effs if !allowed.exists(param => eff.refers(param)) do
              Reporter.error("Parameter not allowed: " + eff, pos, trace)

          case _ =>

      super.transformFunDef(fdef)

  private def synthesizeNoneBindings(params: List[Symbol], span: Span): List[WithArg] =
    params.map: param =>
      val optionParamSym = param.optionParam
      val paramRef = Ident(optionParamSym)(span)
      val noneType = TagType("None", params = Nil)
      val noneValue = TaggedEncoding.encodeVariant(noneType, Nil, span, span)
      WithArg(paramRef, noneValue)(span)

  /** Check `allow`-clause */
  override  def transformAllow(allowExpr: Allow)(using ctx: Context): Word =
    val expr2 = transform(allowExpr.expr)

    given Source = ctx.owner.sourcePos.source
    val effsInner = EffectAnalysis.effects(allowExpr.expr)(using ctx.cache)
    val allowed = allowExpr.params.map(_.symbol.dealias).toSet

    val unprovided = effsInner.filter((k, _) => !allowed.exists(param => k.refers(param)))

    for
      (eff, trace) <- unprovided if !eff.is(Flags.Option)
    do
      Reporter.error("Parameter not allowed: " + eff, allowExpr.expr.pos, trace)

    val defaultEffs = unprovided.keys.filter(_.is(Flags.Option)).toList
    if defaultEffs.isEmpty then
      expr2

    else
      val argsAdded = synthesizeNoneBindings(defaultEffs, allowExpr.span)
      With(expr2, argsAdded)(allowExpr.tpe, allowExpr.span)

  /** Capture all context parameters used in the methods of an object
    *
    * An object
    *
    *     object {
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
    *     object {
    *       def foo() = ... with param1 = a
    *       def bar() = ... with param2 = b
    *       def baz() = ... with param1 = a, param2 = b
    *     }
    *
    * Closure conversion will later turn `a` and `b` to fields of the object.
    */
  override def transformObject(obj: Object)(using ctx: Context): Word =
    val newDefs = new mutable.ArrayBuffer[FunDef]
    val aliasMap = mutable.Map.empty[Symbol, Assign]

    given Source = ctx.owner.sourcePos.source
    val span = obj.span

    for ddef <- obj.funs do
      ctx.cache.code(ddef.symbol) = ddef
      val effsTraced = EffectAnalysis.effects(ddef.symbol)(using ctx.cache)
      val effs = (effsTraced -- ddef.effectsBound.getOrElse(Nil)).keys.toList

      if effs.isEmpty then
        val body2 = this(ddef.body)
        newDefs += ddef.copy(body = body2)(ddef.span)
      else
        val args =
          for eff <- effs yield
            val paramRef = Ident(eff)(span)
            aliasMap.get(eff) match
              case None =>
                val alias = Symbol.createSymbol("alias_" + eff.name, eff.info, Flags.Synthetic, owner = ctx.owner, pos = obj.pos)
                aliasMap(eff) = Assign(Ident(alias)(span), paramRef)(span)
                WithArg(paramRef, Ident(alias)(span))(span)

              case Some(vdef) =>
                WithArg(paramRef, Ident(vdef.symbol)(span))(span)
            end match
          end for
        val body2 = With(this(ddef.body), args)(ddef.body.tpe, ddef.body.span)
        newDefs += ddef.copy(body = body2)(ddef.span)
    end for

    val aliases = aliasMap.values.toSeq
    val obj2 = obj.copy(funs = newDefs.toList)(obj.tpe, obj.span)
    Block((aliases :+ obj2).toList)(obj.tpe, obj.span)


  private def checkTermInPattern(word: Word)(using ctx: Context): Word =
    given Source = ctx.owner.sourcePos.source
    val effs = EffectAnalysis.effects(word)(using ctx.cache)

    for
      (eff, trace) <- effs
    do
      Reporter.error("External context parameters not allowed in patterns: " + eff, word.pos, trace)

    // The code might still bind and use default parameters
    this(word)

  override def transformGuardPattern(pat: GuardPattern)(using ctx: Context): Pattern =
    pat.copy(pattern = this(pat.pattern), guard = checkTermInPattern(pat.guard))

  override def transformTermBindingPattern(pat: TermBindingPattern)(using ctx: Context): Pattern =
    val assigns =
      for ass <- pat.bindings
      yield ass.copy(rhs = checkTermInPattern(ass.rhs))(ass.span)

    pat.copy(pattern = this(pat.pattern), bindings = assigns)

  override def transformValuePattern(pat: ValuePattern)(using ctx: Context): Pattern =
    pat.copy(value = checkTermInPattern(pat.value))(pat.scrutineeType)

object NormalizeParams:
  class Context(val cache: EffectAnalysis.Cache, val owner: Symbol)
  object CacheContext extends Phase.ContextObject[Context]:
    def newContext(owner: Symbol, old: Context) = Context(old.cache, owner)
    def newContext(namespace: Symbol) = throw new Exception("Namespace context should use global cache")
