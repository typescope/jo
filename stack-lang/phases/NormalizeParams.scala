package phases

import ast.Positions.Source
import sast.*
import sast.Sast.*
import sast.Symbols.*
import typing.EffectAnalysis
import reporting.Reporter

import scala.collection.mutable

/** This phase normalize the usage of context parameters
  *
  * - Optional context parameters are bound at program entry
  * - All transitive captures of context parameters are made explicit in objects
  * - Checks are performed for `allow`-clauses
  */
class NormalizeParams(using Reporter) extends Phase[NormalizeParams.Context]:
  val contextObject = NormalizeParams.CacheContext

  override def transform(nss: List[Namespace]): List[Namespace] =
    given ctx: Context = contextObject.newContext()
    for
      ns <- nss
      case fdef: FunDef <- ns.defs
    do
      ctx.cache.code(fdef.symbol) = fdef

    for ns <- nss yield transformNamespace(ns)

  /** Bind optional context parameters at program entry.
    *
    * Only bind optional context parameters whose default value are needed.
    *
    * TODO: Need to do the same for each thread.
    */
  override  def transformFunDef(fdef: FunDef)(using ctx: Context): FunDef =
    if !fdef.symbol.isLocal && fdef.name == "main" then
      val defn = Definitions.instance

      val effs = EffectAnalysis.effects(fdef.symbol)(using ctx.cache)
      val fdef2 = super.transformFunDef(fdef)

      val pos = fdef.symbol.sourcePos
      for
        (eff, trace) <- effs
        if !eff.is(Flags.Default) && !defn.isRuntimeContextParam(eff)
      do
        Reporter.error("Context parameter not provided: " + eff, pos, trace)

      fdef2

    else
      if fdef.symbol.isLocal then ctx.cache.code(fdef.symbol) = fdef

      if fdef.symbol.isFunction then
        fdef.receives match
          case Some(params) =>
            val allowed = params.toSet
            val effs = EffectAnalysis.effects(fdef.symbol)(using ctx.cache)
            val pos = fdef.symbol.sourcePos
            for (eff, trace) <- effs if !allowed.contains(eff) do
              Reporter.error("Parameter not allowed: " + eff, pos, trace)

          case None =>

      super.transformFunDef(fdef)

  /** Check `allow`-clause */
  override  def transformWith(withExpr: With)(using ctx: Context): Word =
    withExpr.allow match
      case Some(ids) =>
        given Source = ctx.owner.sourcePos.source
        val zero = Map.empty[Symbol, EffectAnalysis.Trace]
        val effsInner = EffectAnalysis.effects(withExpr.expr)(using ctx.cache)
        val effsArgs = withExpr.args.foldLeft(zero): (acc, arg) =>
          acc ++ EffectAnalysis.effects(arg.rhs)(using ctx.cache)

        val masked = withExpr.args.map(_.paramRef.symbol)
        val allowed = ids.map(_.symbol).toSet

        // println("effsInner = " + effsInner)
        // println("effsArgs = " + effsArgs)
        // println("masked = " + masked)

        for
          (eff, trace) <- (effsInner -- masked) ++ effsArgs
          if !eff.is(Flags.Default) && !allowed.contains(eff)
        do
          Reporter.error("Parameter not allowed: " + eff, withExpr.expr.pos, trace)
      case _ =>
    end match
    withExpr

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
    val aliasMap = mutable.Map.empty[Symbol, ValDef]

    given Source = ctx.owner.sourcePos.source
    val span = obj.span

    for ddef <- obj.defs do
      ctx.cache.code(ddef.symbol) = ddef
      val effsTraced = EffectAnalysis.effects(ddef.symbol)(using ctx.cache)
      val effs = (effsTraced -- ddef.methodReceives).keys.toList

      if effs.isEmpty then
        newDefs += ddef
      else
        val args =
          for eff <- effs yield
            val paramRef = Ident(eff)(span)
            aliasMap.get(eff) match
              case None =>
                val alias = new Symbol("alias_" + eff.name, eff.info, Flags.Val, owner = ctx.owner, sourcePos = obj.pos)
                aliasMap(eff) = ValDef(alias, paramRef)(span)
                WithArg(paramRef, Ident(alias)(span))(span)

              case Some(vdef) =>
                WithArg(paramRef, Ident(vdef.symbol)(span))(span)
            end match
          end for
        val body2 = With(this(ddef.body), args, allow = None)(ddef.body.tpe, ddef.body.span)
        newDefs += ddef.copy(body = body2)(ddef.span)
    end for

    val aliases = aliasMap.values.toSeq
    val obj2 = obj.copy(defs = newDefs.toList)(obj.tpe, obj.span)
    Block((aliases :+ obj2).toList)(obj.tpe, obj.span)

object NormalizeParams:
  class Context(val cache: EffectAnalysis.Cache, val owner: Symbol)
  object CacheContext extends Phase.ContextObject[Context]:
    def newContext(owner: Symbol, old: Context) = Context(old.cache, owner)
    def newContext() = Context(EffectAnalysis.Cache(), null)
