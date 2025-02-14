package phases

import sast.*
import sast.Sast.*
import sast.Symbols.*
import typing.EffectAnalysis

/** This phase normalize usage of context parameters
  *
  * - Optional context parameters are bound at program entry
  * - All transitive captures of context parameters are made explicit in objects
  * - Checks are performed for `allow`-clauses
  */
class NormalizeParams extends Phase[EffectAnalysis.Cache]:
  val contextObject = NormalizeParams.CacheContext

  override def transform(nss: List[Namespace]): List[Namespace] =
    given cache: Context = contextObject.newContext()
    for
      ns <- nss
      case fdef: FunDef <- ns.defs
    do
      cache.code(fdef.symbol) = fdef

    for ns <- nss yield transformNamespace(ns)

  override  def transformFunDef(fdef: FunDef)(using Context): FunDef =
    if !fdef.symbol.isLocal && fdef.name == "main" then
      val effs = EffectAnalysis.effects(fdef.symbol).filter(_.is(Flags.Default))
      val fdef2 = super.transformFunDef(fdef)

      if effs.isEmpty then fdef2
      else
        val span = fdef.body.span
        val args = effs.toList.map: eff =>
          val defaultFunSym = eff.defaultFunction
          val paramRef = Ident(eff)(span)
          val defaultFunRef = Ident(defaultFunSym)(span)
          val rhs = Apply(defaultFunRef, args = Nil)(eff.info, span)
          WithArg(paramRef, rhs)(span)

        val body2 = With(fdef2.body, args, allow = None)(fdef2.body.tpe, span)
        fdef2.copy(body = body2)(fdef.span)

    else
      super.transformFunDef(fdef)

object NormalizeParams:

  object CacheContext extends Phase.ContextObject[EffectAnalysis.Cache]:
    def newContext(owner: Symbol, old: EffectAnalysis.Cache) = old
    def newContext() = EffectAnalysis.Cache()
