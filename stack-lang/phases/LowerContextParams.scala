package phases

import ast.Positions.Source
import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import scala.collection.mutable

/** This phase translate context parameters to runtime calls
  *
  * This phase is generic and can be used for all platforms, as long as the
  * following support functions are provided:
  *
  *     fun hasParam(key: String): Bool = ...
  *     fun getParam(key: String): Any = ...
  *     fun setParam(key: String, value: Any): Any = ...
  *     fun delParam(key: String): Unit = ...
  *
  * Currently, only JS backend uses phase. The native backend handles it in
  * during assembly translation in a different way for speed.
  */
class LowerContextParams(
  hasParamSym: Symbol, getParamSym: Symbol,
  setParamSym: Symbol, delParamSym: Symbol)
  (using defn: Definitions)
extends Phase[Symbol]:
  val contextObject = Phase.OwnerContext

  val StringType = defn.StringType
  val BoolType = defn.BoolType
  val UnitType = defn.UnitType

  override def transformIdent(word: Ident)(using ctx: Context): Word =
    word match
      case Ident(sym) if sym.isAllOf(Flags.Context | Flags.Param) =>
        val key = StringLit(sym.fullName)(word.span)
        val getParamFun = Ident(getParamSym)(word.span)
        val getParamCall = Encoded(Apply(getParamFun, key :: Nil)(AnyType, word.span))(word.tpe)
        getParamCall

      case _ =>
        word

  override def transformWith(word: With)(using ctx: Context): Word =
    val With(expr, args) = word
    given Source = ctx.sourcePos.source

    val paramRefs = args.map(_.paramRef)
    val stats = new mutable.ArrayBuffer[Word]

    // 1. args are evaluated with the outer context
    val argValueSyms = args.map: arg =>
      val paramName = arg.paramRef.symbol.fullName
      val argValueSym = Symbol.createSymbol("arg_" + paramName, arg.rhs.tpe, Flags.Synthetic, owner = ctx, pos = arg.rhs.pos)
      stats += Assign(Ident(argValueSym)(arg.rhs.span), this(arg.rhs))(arg.rhs.span)
      argValueSym

    // 2. val hasX = hasParam("x")
    val hasXSyms = args.map: arg =>
      val paramName = arg.paramRef.symbol.fullName
      val key = StringLit(paramName)(arg.paramRef.span)
      val funHasParam = Ident(hasParamSym)(arg.span)
      val hasParamCall = Apply(funHasParam, key :: Nil)(BoolType, arg.paramRef.span)
      val hasXSym = Symbol.createSymbol("has_" + paramName, BoolType, Flags.Synthetic, owner = ctx, pos = arg.rhs.pos)
      stats += Assign(Ident(hasXSym)(arg.paramRef.span), hasParamCall)(arg.span)
      hasXSym

    // 3. val oldX = setParam("x", v)
    val oldValueSyms = args.zip(argValueSyms).map: (arg, argValueSym) =>
      val paramName = arg.paramRef.symbol.fullName
      val key = StringLit(paramName)(arg.paramRef.span)
      val value = Ident(argValueSym)(arg.rhs.span)
      val funSetParam = Ident(setParamSym)(arg.span)
      val setParamCall = Apply(funSetParam, key :: value :: Nil)(AnyType, arg.span)
      val oldValueSym = Symbol.createSymbol("old_" + paramName, arg.rhs.tpe, Flags.Synthetic, owner = ctx, pos = arg.rhs.pos)
      stats += Assign(Ident(oldValueSym)(arg.paramRef.span), setParamCall)(arg.span)
      oldValueSym

    // 4. val res = expr only if expr is not void
    val resSym = Symbol.createSymbol("res", expr.tpe, Flags.Synthetic, owner = ctx, pos = expr.pos)
    if expr.tpe.isVoidType then
      stats += this(expr)
    else
      stats += Assign(Ident(resSym)(expr.span), this(expr))(expr.span)

    // 5. if hasX then setParam("x", oldX) else delParam("x")
    paramRefs.zip(hasXSyms).zip(oldValueSyms).foreach:
      case ((paramRef, hasX), oldValueSym) =>
        val paramName = paramRef.symbol.fullName

        val key = StringLit(paramName)(paramRef.span)
        val value = Ident(oldValueSym)(paramRef.span)
        val funSetParam = Ident(setParamSym)(paramRef.span)
        val setParamCall = Apply(funSetParam, key :: value :: Nil)(AnyType, paramRef.span).dropValue

        val funDelParam = Ident(delParamSym)(paramRef.span)
        val delParamCall = Apply(funDelParam, key :: Nil)(UnitType, paramRef.span).dropValue

        val cond = Ident(hasX)(paramRef.span)
        val ifStat = If(cond, setParamCall, delParamCall)(VoidType, paramRef.span)

        stats += ifStat

    // 6. res
    if !expr.tpe.isVoidType then
      stats += Ident(resSym)(expr.span)

    Block(stats.toList)(expr.tpe, word.span)
