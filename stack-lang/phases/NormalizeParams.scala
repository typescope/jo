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
      val effs = EffectAnalysis.effects(fdef.symbol)(using ctx.cache)
      val fdef2 = super.transformFunDef(fdef)

      val nonDefaultEffs = effs.filter(!_.is(Flags.Default))
      if nonDefaultEffs.nonEmpty then
        given Source = fdef.symbol.sourcePos.source
        val list = nonDefaultEffs.mkString(", ")
        Reporter.error("Context parameters not provided: " + list, fdef2.pos)

      val defaultEffs = effs.filter(_.is(Flags.Default))
      if defaultEffs.isEmpty then fdef2
      else
        val span = fdef.body.span
        val args = defaultEffs.toList.map: eff =>
          val defaultFunSym = eff.defaultFunction
          val paramRef = Ident(eff)(span)
          val defaultFunRef = Ident(defaultFunSym)(span)
          val rhs = Apply(defaultFunRef, args = Nil)(eff.info, span)
          WithArg(paramRef, rhs)(span)

        val body2 = With(fdef2.body, args, allow = None)(fdef2.body.tpe, span)
        fdef2.copy(body = body2)(fdef.span)

    else
      super.transformFunDef(fdef)

  /** Check `allow`-clause */
  override  def transformWith(withExpr: With)(using ctx: Context): Word =
    withExpr.allow match
      case Some(ids) =>
        val allowed = ids.map(_.symbol)
        val effs = EffectAnalysis.effects(withExpr.expr)(using ctx.cache)
        val notAllowed = effs -- allowed
        if notAllowed.nonEmpty then
          given Source = ctx.owner.sourcePos.source
          val list = notAllowed.mkString(", ")
          Reporter.error("More context parameters used than allowed: " + list, withExpr.expr.pos)
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
      val effs = EffectAnalysis.effects(ddef.symbol)(using ctx.cache)
      val args =
        for eff <- effs.toList yield
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
