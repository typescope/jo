package phases

import ast.Positions.{ Source, Span }
import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import scala.collection.mutable

/** This phase translate context parameters to runtime calls
  *
  * This phase is generic and can be used for all platforms, as long as the
  * following support functions are provided:
  *
  *     def paramKey(id: Any): Key = ...
  *     def hasParam(key: Key): Bool = ...
  *     def getParam(key: Key): Any = ...
  *     def setParam(key: Key, value: Any): Any = ...
  *     def delParam(key: Key): Unit = ...
  *
  * Currently, only JS backend uses phase. The native backend handles it in
  * during assembly translation in a different way for speed.
  */
class LowerContextParams(
  paramKeySym: Symbol,
  hasParamSym: Symbol, getParamSym: Symbol,
  setParamSym: Symbol, delParamSym: Symbol)
  (using defn: Definitions)
extends Phase:

  val StringType = defn.StringType
  val BoolType = defn.BoolType
  val UnitType = defn.UnitType

  /** Create a call to paramKey(paramIdent)
    * where paramIdent is an Ident referring to the context parameter symbol
    */
  private def makeParamSymbol(paramSym: Symbol, span: Span): Word =
    val paramIdent = Ident(paramSym)(span)
    val funParamKey = Ident(paramKeySym)(span)
    funParamKey.appliedTo(paramIdent)

  override def transformIdent(word: Ident)(using Context): Word =
    word match
      case Ident(sym) if sym.isAllOf(Flags.Context) =>
        val key = makeParamSymbol(sym, word.span)
        val getParamFun = Ident(getParamSym)(word.span)
        val getParamCall = Encoded(getParamFun.appliedTo(key))(word.tpe)
        getParamCall

      case _ =>
        word

  override def transformWith(word: With)(using Context): Word =
    val With(expr, args) = word
    given Source = Phase.source.value

    val paramRefs = args.map(_.ident)
    val stats = new mutable.ArrayBuffer[Word]
    val owner = Phase.owner.value

    // 1. args are evaluated with the outer context
    val argValueSyms = args.map: arg =>
      val paramName = arg.ident.symbol.fullName
      val argValueSym = TermSymbol.create("arg_" + paramName, arg.rhs.tpe, Flags.Synthetic, Visibility.Default, owner, pos = arg.rhs.pos)
      stats += Assign(Ident(argValueSym)(arg.rhs.span), this(arg.rhs))
      argValueSym

    // 2. val hasX = hasParam(paramKey(x))
    val hasXSyms = args.map: arg =>
      val paramName = arg.symbol.fullName
      val key = makeParamSymbol(arg.symbol, arg.ident.span)
      val funHasParam = Ident(hasParamSym)(arg.span)
      val hasParamCall = funHasParam.appliedTo(key)
      val hasXSym = TermSymbol.create("has_" + paramName, BoolType, Flags.Synthetic, Visibility.Default, owner, pos = arg.rhs.pos)
      stats += Assign(Ident(hasXSym)(arg.ident.span), hasParamCall)
      hasXSym

    // 3. val oldX = setParam(paramKey(x), v)
    val oldValueSyms = args.zip(argValueSyms).map: (arg, argValueSym) =>
      val paramName = arg.symbol.fullName
      val key = makeParamSymbol(arg.symbol, arg.ident.span)
      val value = Ident(argValueSym)(arg.rhs.span)
      val funSetParam = Ident(setParamSym)(arg.span)
      val setParamCall = funSetParam.appliedTo(key, value)
      val oldValueSym = TermSymbol.create("old_" + paramName, arg.rhs.tpe, Flags.Synthetic, Visibility.Default, owner, pos = arg.rhs.pos)
      stats += Assign(Ident(oldValueSym)(arg.ident.span), setParamCall.encodedAs(arg.rhs.tpe))
      oldValueSym

    // 4. val res = expr only if expr is not void
    val resSym = TermSymbol.create("res", expr.tpe, Flags.Synthetic, Visibility.Default, owner, pos = expr.pos)
    if expr.tpe.isVoidType then
      stats += this(expr)
    else
      stats += Assign(Ident(resSym)(expr.span), this(expr))

    // 5. if hasX then setParam(paramKey(x), oldX) else delParam(paramKey(x))
    paramRefs.zip(hasXSyms).zip(oldValueSyms).foreach:
      case ((paramRef, hasX), oldValueSym) =>
        val paramSym = paramRef.symbol

        val key = makeParamSymbol(paramSym, paramRef.span)
        val value = Ident(oldValueSym)(paramRef.span)
        val funSetParam = Ident(setParamSym)(paramRef.span)
        val setParamCall = funSetParam.appliedTo(key, value).dropValue

        val funDelParam = Ident(delParamSym)(paramRef.span)
        val delParamCall = funDelParam.appliedTo(key).dropValue

        val cond = Ident(hasX)(paramRef.span)
        val ifStat = If(cond, setParamCall, delParamCall)(VoidType, paramRef.span)

        stats += ifStat

    // 6. res
    if !expr.tpe.isVoidType then
      stats += Ident(resSym)(expr.span)

    Block(stats.toList)(word.span)
