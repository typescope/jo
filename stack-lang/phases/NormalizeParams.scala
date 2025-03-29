package phases

import ast.Positions.*
import sast.*
import sast.Sast.*
import sast.Types.*
import sast.Symbols.*
import typing.Desugaring
import typing.EffectAnalysis
import reporting.Reporter

import scala.collection.mutable

/** This phase normalize the usage of context parameters and some others
  *
  * - Optional context parameters are desugared to normal context parameters
  * - All transitive captures of context parameters are made explicit in objects
  * - Checks are performed for `allow`-clauses
  * - Desugar short-cutting || and &&
  *
  * The desugaring for optional context parameters
  *
  *    param a: T = rhs
  *
  * was desugered in Namer to
  *
  *    <Context> <Default> param a: T
  *
  *    <Default> a$default: T = rhs
  *
  *
  * and in this phase access to `a` is desugared to `a$value`
  *
  *    param a$option: Option[T] // automatically bound to None when a necessary binding is needed
  *
  *    fun a$value: T =
  *      a$option match
  *        case #None   => a$default
  *        case #Some v => v
  *
  * and binding to `with a = e` is desugared to `with a$option = #Some e`
  *
  * The effect check will happen for `a`, the semantics will only use
  * `a$option`.
  */
class NormalizeParams(using Reporter) extends Phase[NormalizeParams.Context]:
  val contextObject = NormalizeParams.CacheContext

  val NoneType = TagType("None", params = Nil)

  override def transform(nss: List[Namespace]): List[Namespace] =
    given ctx: Context = contextObject.newContext()
    for
      ns <- nss
      defn <- ns.defs
    do
      defn match
        case fdef: FunDef =>
          ctx.cache.code(fdef.symbol) = fdef

        case ParamDef(param, _) if param.is(Flags.Default) =>
          // First synthesize all symbols
          val optType = UnionType(
            NoneType
              :: TagType("#Some", NamedInfo("value", param.info) :: Nil)
              :: Nil
          )

          val optionParamSym =
            Symbol.createSymbol(param.name + "$option", optType, Flags.Context | Flags.Param, param.owner, param.sourcePos)

          val valueFunInfo = param.defaultFunction.info
          val valueFunSym =
            Symbol.createSymbol(param.name + "$value", valueFunInfo, Flags.Fun, param.owner, param.sourcePos)

          ns.info.define(optionParamSym)
          ns.info.define(valueFunSym)

        case _ =>
      end match
    end for

    for ns <- nss yield transformNamespace(ns)

  /** Synthesize the following function for context parameter `a`:
    *
    *    fun a$value: T =
    *      a$option match
    *        case #None   => a$default
    *        case #Some v => v
    */
  private def createValueFunction(pdef: ParamDef): FunDef =
    val param = pdef.symbol
    val valueFunSym = pdef.symbol.valueFunction
    val defaultFunSym = pdef.symbol.defaultFunction
    val optionParamSym = pdef.symbol.optionParam

    val valueSym = Symbol.createValueSymbol(pdef.name + "Value", optionParamSym.info, valueFunSym, param.sourcePos)
    val vdef = ValDef(valueSym, Ident(optionParamSym)(pdef.span))(pdef.span)

    val noneTypeEncoded = Desugaring.encodeTagType(NoneType)
    val refOpt = Encoded(Ident(valueSym)(pdef.span))(noneTypeEncoded)
    val cond = Desugaring.testVariantTag(refOpt, "None", pdef.span)
    val trueBranch = Apply(Ident(defaultFunSym)(pdef.span), args = Nil)(param.info, pdef.span)

    val someType = TagType("Some", params = NamedInfo("value", param.info) :: Nil)
    val falseBranch = Desugaring.selectVariantField(refOpt, someType, "value", pdef.span)

    val ifStat = If(cond, trueBranch, falseBranch)(param.info, pdef.span)

    val body = Block(vdef :: ifStat :: Nil)(param.info, pdef.span)

    FunDef(valueFunSym, tparams = Nil, params = Nil, body)(pdef.span)

  override def transformNamespace(ns: Namespace)(using ctx: Context): Namespace =
    val defs = ns.defs.flatMap:
      case fdef: FunDef =>
        given Context = contextObject.newContext(ns.symbol, ctx)
        transformFunDef(fdef) :: Nil

      case pdef: ParamDef if pdef.symbol.is(Flags.Default) =>
        val optionParamSym = pdef.symbol.optionParam
        val tpt = TypeTree(optionParamSym.info)(pdef.span)
        val optionParamDef = ParamDef(optionParamSym, tpt)(pdef.span)
        val valueFunDef = createValueFunction(pdef)

        pdef :: optionParamDef :: valueFunDef :: Nil

      case defn => defn :: Nil


    Namespace(ns.symbol, ns.imports, defs)(ns.span)

  /** Desguar short-cutting || and &&
    *
    *     lhs || rhs    ===>    if lhs then true else rhs
    *     lhs && rhs    ===>    if lhs then rhs  else false
    */
  override def transformApply(apply: Apply)(using ctx: Context): Word =
    val Apply(fun, args) = apply
    val defn = Definitions.instance

    if fun.refersTo(defn.Predef_and) then
      val lhs :: rhs :: Nil = args: @unchecked
      val falseLit = BoolLit(false)(apply.tpe, rhs.span)
      If(transform(lhs), transform(rhs), falseLit)(apply.tpe, apply.span)

    else if fun.refersTo(defn.Predef_or) then
      val lhs :: rhs :: Nil = args: @unchecked
      val trueLit = BoolLit(true)(apply.tpe, lhs.span)
      If(transform(lhs), trueLit, transform(rhs))(apply.tpe, apply.span)

    else
      super.transformApply(apply)

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

      val defaultEffs = effs.keys.filter(_.is(Flags.Default)).toList
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
        fdef.receives match
          case Some(params) =>
            val allowed = params.toSet
            val effs = EffectAnalysis.effects(symbol)(using ctx.cache)
            val pos = symbol.sourcePos
            for (eff, trace) <- effs if !allowed.contains(eff) do
              Reporter.error("Parameter not allowed: " + eff, pos, trace)

          case None =>
            if symbol.is(Flags.Default) then
              val effs = EffectAnalysis.effects(symbol)(using ctx.cache)
              val pos =
                given Source = symbol.sourcePos.source
                fdef.body.pos

              for (eff, trace) <- effs do
                Reporter.error("Parameter not allowed for default values: " + eff, pos, trace)

      super.transformFunDef(fdef)

  override  def transformIdent(ident: Ident)(using ctx: Context): Word =
    val sym = ident.symbol
    if sym.isAllOf(Flags.Context | Flags.Default) then
      Apply(Ident(sym.valueFunction)(ident.span), args = Nil)(sym.info, ident.span)

    else
      ident

  private def synthesizeNoneBindings(params: List[Symbol], span: Span): List[WithArg] =
    params.map: param =>
      val optionParamSym = param.optionParam
      val paramRef = Ident(optionParamSym)(span)
      val noneType = TagType("None", params = Nil)
      val noneValue = Desugaring.encodeVariant(noneType, Nil, span, span)
      WithArg(paramRef, noneValue)(span)

  /** Check `allow`-clause */
  override  def transformAllow(allowExpr: Allow)(using ctx: Context): Word =
    val expr2 = transform(allowExpr.expr)

    given Source = ctx.owner.sourcePos.source
    val effsInner = EffectAnalysis.effects(allowExpr.expr)(using ctx.cache)
    val allowed = allowExpr.params.map(_.symbol).toSet

    val unprovided = effsInner -- allowed

    for
      (eff, trace) <- unprovided if !eff.is(Flags.Default)
    do
      Reporter.error("Parameter not allowed: " + eff, allowExpr.expr.pos, trace)

    val defaultEffs = unprovided.keys.filter(_.is(Flags.Default)).toList
    if defaultEffs.isEmpty then
      expr2

    else
      val argsAdded = synthesizeNoneBindings(defaultEffs, allowExpr.span)
      With(expr2, argsAdded)(allowExpr.tpe, allowExpr.span)

  override  def transformWith(withExpr: With)(using ctx: Context): Word =
    /** rewrite `with a = rhs` to `with a$option = #Some rhs` */
    def rewireArgs(args: List[WithArg]): List[WithArg] =
      for arg @ WithArg(paramRef, rhs) <- args yield
        if paramRef.symbol.is(Flags.Default) then
          val optionParamRef = Ident(paramRef.symbol.optionParam)(paramRef.span)
          val someType = TagType("Some", NamedInfo("value", paramRef.symbol.info) :: Nil)
          val rhs2 = Desugaring.encodeVariant(someType, rhs :: Nil, paramRef.span, rhs.span)
          WithArg(optionParamRef, rhs2)(arg.span)
        else
          arg

    val expr2 = transform(withExpr.expr)
    val args2 = withExpr.args.map: arg =>
      arg.copy(arg.paramRef, transform(arg.rhs))(arg.span)

    val args3 = rewireArgs(args2)
    With(expr2, args3)(expr2.tpe, withExpr.span)

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
        val body2 = this(ddef.body)
        newDefs += ddef.copy(body = body2)(ddef.span)
      else
        val args =
          for effRaw <- effs yield
            val eff = if effRaw.is(Flags.Default) then effRaw.optionParam else effRaw

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
        val body2 = With(this(ddef.body), args)(ddef.body.tpe, ddef.body.span)
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
