package js

import ast.Positions.Source
import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import scala.collection.mutable

/** The compiler phase translate context parameters to runtime calls */
object LowerContextParams extends SastOps.TreeMap:
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
        fdef.copy(body = body2)(locals2.toList, fdef.captures, fdef.span)

    Namespace(ns.symbol, ns.imports, funs)(ns.span)

  override def apply(word: Word)(using ctx: Context): Word =
    word match
      case Ident(sym) if sym.isAllOf(Flags.Context | Flags.Param) =>
        val arg = StringLit(sym.fullName)(word.span)
        val fun = Ident(JSRuntime.instance.JS_getParam)(word.span)
        val app = Apply(fun, arg :: Nil)(word.tpe, word.span)
        app

      case With(expr, args) =>
        // pushParams(runtimeArray({ key = "a.key", value = rhs })
        val span = args.head.span | args.last.span
        given Source = ctx.funSymbol.sourcePos.source

        val paramRefs = args.map(_.paramRef)

        val stats = new mutable.ArrayBuffer[Word]

        // 1. args are evaluated with the outer context
        val argValueSyms = args.map: arg =>
          val paramName = arg.paramRef.symbol.fullName
          val argValueSym = new Symbol("arg_" + paramName, arg.rhs.tpe, Flags.Val, owner = ctx.funSymbol, sourcePos = arg.rhs.pos)
          ctx.locals += argValueSym
          stats += Assign(argValueSym, this(arg.rhs))(arg.rhs.span)
          argValueSym

        // 2. val oldX = setParam("x", v)
        val oldValueSyms = args.zip(argValueSyms).map: (arg, argValueSym) =>
          val paramName = arg.paramRef.symbol.fullName
          val key = StringLit(paramName)(arg.paramRef.span)
          val value = Ident(argValueSym)(arg.rhs.span)
          val funSetParam = Ident(JSRuntime.instance.JS_setParam)(arg.span)
          val setParamCall = Apply(funSetParam, key :: value :: Nil)(AnyType, span)
          val oldValueSym = new Symbol("old_" + paramName, arg.rhs.tpe, Flags.Val, owner = ctx.funSymbol, sourcePos = arg.rhs.pos)
          ctx.locals += oldValueSym
          stats += Assign(oldValueSym, setParamCall)(arg.span)
          oldValueSym

        // 3. val res = expr
        val resSym = new Symbol("res", expr.tpe, Flags.Val, owner = ctx.funSymbol, sourcePos = null)
        ctx.locals += resSym
        stats += Assign(resSym, this(expr))(expr.span)

        // 4. setParam("x", oldX)
        paramRefs.zip(oldValueSyms).foreach: (paramRef, oldValueSym) =>
          val paramName = paramRef.symbol.fullName
          val key = StringLit(paramName)(paramRef.span)
          val value = Ident(oldValueSym)(paramRef.span)
          val funSetParam = Ident(JSRuntime.instance.JS_setParam)(paramRef.span)
          stats += dropValue(Apply(funSetParam, key :: value :: Nil)(AnyType, span))

        // 5. res
        stats += Ident(resSym)(expr.span)

        Phrase(stats.toList)(expr.tpe, word.span)

      case _ => recur(word)
