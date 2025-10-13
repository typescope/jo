package phases

import ast.Positions.*
import sast.*
import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import scala.collection.mutable

/** This phase normalize the usage of context parameters and some others
  *
  * - All transitive captures of context parameters are made explicit in objects
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
class NormalizeParams(using defn: Definitions) extends Phase[Symbol]:
  val contextObject = Phase.OwnerContext

  val NoneType = TagType("None", params = Nil)

  /** Bind optional context parameters at program entry.
    *
    * Only bind optional context parameters whose default value are needed.
    *
    * TODO: Need to do the same for each thread.
    */
  override  def transformFunDef(fdef: FunDef)(using ctx: Context): FunDef =
    val symbol = fdef.symbol

    // force computing effects
    val effs = defn.effectEngine.effects(symbol)

    val fdef2 = super.transformFunDef(fdef)

    if !symbol.isLocal && fdef.name == "main" then
      val defaultEffs = effs.keys.filter(_.is(Flags.Default)).toList
      if defaultEffs.isEmpty then
        fdef2

      else
        val args = synthesizeNoneBindings(defaultEffs, fdef2.body.span)
        val body2 = With(fdef2.body, args)
        fdef2.copy(body = body2)(fdef.span)

    else
      fdef2

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

  override def transformGuardPattern(pat: GuardPattern)(using ctx: Context): Pattern =
    pat.copy(pattern = this(pat.pattern), guard = this(pat.guard))

  override def transformBindPattern(pat: BindPattern)(using ctx: Context): Pattern =
    val assigns =
      for ass <- pat.bindings
      yield ass.copy(rhs = this(ass.rhs))

    pat.copy(pattern = this(pat.pattern), bindings = assigns)

  override def transformValuePattern(pat: ValuePattern)(using ctx: Context): Pattern =
    pat.copy(value = this(pat.value))(pat.scrutineeType)
