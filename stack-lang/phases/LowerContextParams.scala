package phases

import ast.Positions.Source
import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import scala.collection.mutable

/** The compiler phase translate context parameters to runtime calls
  *
  * This phase is generic and can be used for all platforms, as long as the
  * following support functions are provided:
  *
  *     fun hasParam(key: String): Bool = ...
  *     fun getParam(key: String): Any = ...
  *     fun setParam(key: String, value: Any): Any = ...
  *     fun delParam(key: String): void = ...
  *     fun newPage(): Any = ...
  *     fun restorePage(old: Any): void = ...
  *
  * Currently, only JS backend uses phase. The native backend handles it in
  * during assembly translation in a different way for speed.
  */
class LowerContextParams(
  hasParamSym: Symbol, getParamSym: Symbol,
  setParamSym: Symbol, delParamSym: Symbol,
  newPageSym: Symbol, restorePageSym: Symbol)
extends SastOps.TreeMap:
  class FunContext(val funSymbol: Symbol, val locals: mutable.ArrayBuffer[Symbol])

  type Context = FunContext

  def transform(nss: List[Namespace]): List[Namespace] =
    for ns <- nss yield transformNamespace(ns)

  def transformNamespace(ns: Namespace): Namespace =
    val funs =
      for case fdef: FunDef <- ns.defs
      yield
        val locals2 = mutable.ArrayBuffer.from(fdef.locals)
        given Context = FunContext(fdef.symbol, locals2)
        val body2 = this(fdef.body)
        fdef.copy(body = body2)(locals2.toList, fdef.span)

    Namespace(ns.symbol, ns.imports, funs)(ns.span)

  override def apply(word: Word)(using ctx: Context): Word =
    word match
      case Ident(sym) if sym.isAllOf(Flags.Context | Flags.Param) =>
        val arg = StringLit(sym.fullName)(word.span)
        val fun = Ident(getParamSym)(word.span)
        val app = Apply(fun, arg :: Nil)(word.tpe, word.span)
        app

      case DefaultParam(paramRef, default) =>
        val paramName = paramRef.symbol.fullName
        val key = StringLit(paramName)(paramRef.span)
        val funHasParam = Ident(hasParamSym)(paramRef.span)
        val hasParamCall = Apply(funHasParam, key :: Nil)(BoolType, paramRef.span)

        val funGetParam = Ident(getParamSym)(paramRef.span)
        val getParamCall = Apply(funGetParam, key :: Nil)(word.tpe, paramRef.span)

        If(hasParamCall, getParamCall, default)(word.tpe, word.span)

      case With(expr, args, only) =>
        given Source = ctx.funSymbol.sourcePos.source

        val paramRefs = args.map(_.paramRef)

        val stats = new mutable.ArrayBuffer[Word]

        // 1. args are evaluated with the outer context
        val argValueSyms = args.map: arg =>
          val paramName = arg.paramRef.symbol.fullName
          val argValueSym = new Symbol("arg_" + paramName, arg.rhs.tpe, Flags.Val, owner = ctx.funSymbol, sourcePos = arg.rhs.pos)
          ctx.locals += argValueSym
          stats += Assign(Ident(argValueSym)(arg.rhs.span), this(arg.rhs))(arg.rhs.span)
          argValueSym

        if only then
          // 2. newPage
          val oldPageSym = new Symbol("oldPage", AnyType, Flags.Val, owner = ctx.funSymbol, sourcePos = word.pos)
          ctx.locals += oldPageSym

          val funNewPage = Ident(newPageSym)(word.span)
          val newPageCall = Apply(funNewPage, args = Nil)(AnyType, word.span)
          stats += Assign(Ident(oldPageSym)(word.span), newPageCall)(word.span)

          // 3. setParam("x", v)
          args.zip(argValueSyms).foreach: (arg, argValueSym) =>
            val paramName = arg.paramRef.symbol.fullName
            val key = StringLit(paramName)(arg.paramRef.span)
            val value = Ident(argValueSym)(arg.rhs.span)
            val funSetParam = Ident(setParamSym)(arg.span)
            val setParamCall = Apply(funSetParam, key :: value :: Nil)(AnyType, arg.span)
            stats += dropValue(setParamCall)

          // 4. val res = expr only if expr is not void
          val resSym = new Symbol("res", expr.tpe, Flags.Val, owner = ctx.funSymbol, sourcePos = expr.pos)
          if expr.tpe.isVoidType then
            stats += this(expr)
          else
            ctx.locals += resSym
            stats += Assign(Ident(resSym)(expr.span), this(expr))(expr.span)

          // 5. restore page
          val funRestorePage = Ident(restorePageSym)(word.span)
          val oldPageIdent = Ident(oldPageSym)(word.span)
          val restorePageCall = Apply(funRestorePage, oldPageIdent :: Nil)(VoidType, word.span)
          stats += restorePageCall

          // 6. res
          if !expr.tpe.isVoidType then
            stats += Ident(resSym)(expr.span)


          Block(stats.toList)(expr.tpe, word.span)

        else

          // 2. val hasX = hasParam("x")
          val hasXSyms = args.map: arg =>
            val paramName = arg.paramRef.symbol.fullName
            val key = StringLit(paramName)(arg.paramRef.span)
            val funHasParam = Ident(hasParamSym)(arg.span)
            val hasParamCall = Apply(funHasParam, key :: Nil)(BoolType, arg.paramRef.span)
            val hasXSym = new Symbol("has_" + paramName, BoolType, Flags.Val, owner = ctx.funSymbol, sourcePos = arg.rhs.pos)
            ctx.locals += hasXSym
            stats += Assign(Ident(hasXSym)(arg.paramRef.span), hasParamCall)(arg.span)
            hasXSym

          // 3. val oldX = setParam("x", v)
          val oldValueSyms = args.zip(argValueSyms).map: (arg, argValueSym) =>
            val paramName = arg.paramRef.symbol.fullName
            val key = StringLit(paramName)(arg.paramRef.span)
            val value = Ident(argValueSym)(arg.rhs.span)
            val funSetParam = Ident(setParamSym)(arg.span)
            val setParamCall = Apply(funSetParam, key :: value :: Nil)(AnyType, arg.span)
            val oldValueSym = new Symbol("old_" + paramName, arg.rhs.tpe, Flags.Val, owner = ctx.funSymbol, sourcePos = arg.rhs.pos)
            ctx.locals += oldValueSym
            stats += Assign(Ident(oldValueSym)(arg.paramRef.span), setParamCall)(arg.span)
            oldValueSym

          // 4. val res = expr only if expr is not void
          val resSym = new Symbol("res", expr.tpe, Flags.Val, owner = ctx.funSymbol, sourcePos = expr.pos)
          if expr.tpe.isVoidType then
            stats += this(expr)
          else
            ctx.locals += resSym
            stats += Assign(Ident(resSym)(expr.span), this(expr))(expr.span)

          // 5. if hasX then setParam("x", oldX) else delParam("x")
          paramRefs.zip(hasXSyms).zip(oldValueSyms).foreach:
            case ((paramRef, hasX), oldValueSym) =>
              val paramName = paramRef.symbol.fullName

              val key = StringLit(paramName)(paramRef.span)
              val value = Ident(oldValueSym)(paramRef.span)
              val funSetParam = Ident(setParamSym)(paramRef.span)
              val setParamCall = dropValue(Apply(funSetParam, key :: value :: Nil)(AnyType, paramRef.span))

              val funDelParam = Ident(delParamSym)(paramRef.span)
              val delParamCall = Apply(funDelParam, key :: Nil)(VoidType, paramRef.span)

              val cond = Ident(hasX)(paramRef.span)
              val ifStat = If(cond, setParamCall, delParamCall)(VoidType, paramRef.span)

              stats += ifStat

          // 6. res
          if !expr.tpe.isVoidType then
            stats += Ident(resSym)(expr.span)

          Block(stats.toList)(expr.tpe, word.span)

      case _ => recur(word)
