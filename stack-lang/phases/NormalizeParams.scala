package phases

import ast.Positions.*
import sast.*
import sast.Trees.*
import sast.Types.*
import sast.Symbols.*
import reporting.Reporter

import scala.collection.mutable

/** This phase normalize the usage of context parameters and some others
  *
  * - All transitive captures of context parameters are made explicit in objects
  * - Checks are performed for `allow`-clauses
  * - Rewire optional context parameters to its implementation
  *
  * The desugaring for optional context parameters
  *
  *    param a: T = rhs
  *
  * was desugered in Namer to
  *
  *    <Context> <Default> param a: T
  *
  *    def a$default: T receives none = rhs
  *
  *    param a$option: Option[T] // automatically bound to None when a necessary binding is needed
  *
  *    fun a$value: T =
  *      a$option match
  *        case #None   => a$default
  *        case #Some v => v
  *
  * In this phase, the binding to `with a = e` is rewired to `with a$option =
  * #Some e` and an access to `a` is replaced by `a$value`.
  *
  * The effect check will happen for `a`, the semantics will only use
  * `a$option`.
  */
class NormalizeParams(using rp: Reporter, defn: Definitions) extends Phase[Symbol]:
  val contextObject = Phase.OwnerContext

  /*
   * Note that for effect analysis we cannot use the CodeProvider in Definitions
   * due to the partial update in desugaring default context parameters.
   *
   * Instead, we need to use a snapshot CodeProvider after type checking.
   *
   * TODO: make it contextual such that it can be released by GC
   */
  given CodeProvider = defn.snapshotCodeProvider()

  val NoneType = TagType("None", params = Nil)

  /** Bind optional context parameters at program entry.
    *
    * Only bind optional context parameters whose default value are needed.
    *
    * TODO: Need to do the same for each thread.
    */
  override  def transformFunDef(fdef: FunDef)(using ctx: Context): FunDef =
    if !fdef.symbol.isLocal && fdef.name == "main" then
      val effs = defn.effectEngine.effects(fdef.symbol)
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
        val body2 = With(fdef2.body, args)
        fdef2.copy(body = body2)(fdef.span)

    else
      val symbol = fdef.symbol

      if symbol.isFunction then
        fdef.effectPolicy match
          case Effects.Policy.CheckBound(params) =>
            val allowed = params.toSet
            val effs = defn.effectEngine.effects(symbol)
            val pos = symbol.sourcePos
            for (eff, trace) <- effs if !allowed.exists(param => eff.refers(param)) do
              Reporter.error("Parameter not allowed: " + eff, pos, trace)

          case _ =>

      super.transformFunDef(fdef)

  override def transformIdent(ident: Ident)(using ctx: Context): Word =
    val sym = ident.symbol
    if sym.isAllOf(Flags.Context | Flags.Default) then
      Ident(sym.valueFunction)(ident.span).appliedTo()

    else
      ident

  private def synthesizeNoneBindings(params: List[Symbol], span: Span): List[Assign] =
    params.map: param =>
      val optionParamSym = param.optionParam
      val paramRef = Ident(optionParamSym)(span)
      val noneType = TagType("None", params = Nil)
      val noneValue = TaggedEncoding.encodeVariant(noneType, Nil, span, span)
      Assign(paramRef, noneValue)

  /** Check `allow`-clause */
  override  def transformAllow(allowExpr: Allow)(using ctx: Context): Word =
    val expr2 = transform(allowExpr.expr)

    given Source = ctx.owner.sourcePos.source
    val effsInner = defn.effectEngine.effects(allowExpr.expr)
    val allowed = allowExpr.params.map(_.symbol).toSet

    val unprovided = effsInner.filter((k, _) => !allowed.exists(param => k.refers(param)))

    for
      (eff, trace) <- unprovided if !eff.is(Flags.Default)
    do
      Reporter.error("Parameter not allowed: " + eff, allowExpr.expr.pos, trace)

    val defaultEffs = unprovided.keys.filter(_.is(Flags.Default)).toList
    if defaultEffs.isEmpty then
      expr2

    else
      val argsAdded = synthesizeNoneBindings(defaultEffs, allowExpr.span)
      With(expr2, argsAdded)

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
            for effRaw <- effs yield
              val eff = if effRaw.is(Flags.Default) then effRaw.optionParam else effRaw
              val paramRef = Ident(eff)(span)
              aliasMap.get(eff) match
                case None =>
                  val alias = Symbol.createSymbol("alias_" + eff.name, eff.info, Flags.Synthetic, owner = ctx, pos = obj.pos)
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

  override  def transformWith(withExpr: With)(using ctx: Context): Word =
    /** rewrite `with a = rhs` to `with a$option = #Some rhs` */
    def rewireArgs(args: List[Assign]): List[Assign] =
      for arg @ Assign(paramRef, rhs) <- args yield
        if paramRef.symbol.is(Flags.Default) then
          val optionParamRef = Ident(paramRef.symbol.optionParam)(paramRef.span)
          val someType = TagType("Some", NamedInfo("value", paramRef.symbol.info) :: Nil)
          val rhs2 = TaggedEncoding.encodeVariant(someType, rhs :: Nil, paramRef.span, rhs.span)
          Assign(optionParamRef, rhs2)
        else
          arg

    val expr2 = transform(withExpr.expr)
    val args2 = withExpr.args.map: arg =>
      arg.copy(arg.ident, transform(arg.rhs))

    val args3 = rewireArgs(args2)
    With(expr2, args3)

  private def checkTermInPattern(word: Word)(using ctx: Context): Word =
    given Source = ctx.owner.sourcePos.source
    val effs = defn.effectEngine.effects(word)

    for
      (eff, trace) <- effs
    do
      Reporter.error("External context parameters not allowed in patterns: " + eff, word.pos, trace)

    // The code might still bind and use default parameters
    this(word)

  override def transformGuardPattern(pat: GuardPattern)(using ctx: Context): Pattern =
    pat.copy(pattern = this(pat.pattern), guard = checkTermInPattern(pat.guard))

  override def transformBindPattern(pat: BindPattern)(using ctx: Context): Pattern =
    val assigns =
      for ass <- pat.bindings
      yield ass.copy(rhs = checkTermInPattern(ass.rhs))

    pat.copy(pattern = this(pat.pattern), bindings = assigns)

  override def transformValuePattern(pat: ValuePattern)(using ctx: Context): Pattern =
    pat.copy(value = checkTermInPattern(pat.value))(pat.scrutineeType)
