package native

import ast.Positions.Source
import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import native.runtime.NativeRuntime

import scala.collection.mutable

/** The compiler phase translate context parameters to runtime calls
  *
  * This phase assumes the following support functions defined in
  * runtime/native/ParamSupport.stk:
  *
  *     fun getParam(key: String): Any = ...
  *     fun setParam(key: String, value: Any): Int = ...
  *     fun getLastOverwrittenValue(): Any = ...
  *     fun restoreParam(index: Int, value: Any): Void = ...
  *
  * The implementation makes the following assumptions:
  *
  * (1) The keys for manipulating the same param share the same physical address.
  * (2) The underlying physical address do not change.
  * (3) The physical addresses for the keys are positive (as signed integers).
  *
  * The native backend dedup constant strings and compile them as globals, thus
  * satisfies the constraints above.
  */
class LowerContextParams(runtime: NativeRuntime) extends SastOps.TreeMap:
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
        val fun = Ident(runtime.ParamSupport_getParam)(word.span)
        val app = Apply(fun, arg :: Nil)(word.tpe, word.span)
        app

      case DefaultParam(paramRef, default) =>
        val paramName = paramRef.symbol.fullName
        val key = StringLit(paramName)(paramRef.span)
        val funGetParamIndex = Ident(runtime.ParamSupport_getParamIndex)(paramRef.span)
        val getParamIndexCall = Apply(funGetParamIndex, key :: Nil)(IntType, paramRef.span)

        val indexSym = new Symbol("index_" + paramName, IntType, Flags.Val, owner = ctx.funSymbol, sourcePos = null)
        ctx.locals += indexSym
        val indexAssign = Assign(indexSym, getParamIndexCall)(paramRef.span)

        val indexIdent = Ident(indexSym)(paramRef.span)

        val funReadValueAt = Ident(runtime.ParamSupport_readValueAt)(paramRef.span)
        val readValueAtCall = Apply(funReadValueAt, indexIdent :: Nil)(word.tpe, paramRef.span)

        val funLessThan = Ident(Definitions.instance.Predef_lt)(paramRef.span)
        val zero = IntLit(0)(paramRef.span)
        val cond = Apply(funLessThan, indexIdent :: zero :: Nil)(BoolType, paramRef.span)
        val ifExpr = If(cond, default, readValueAtCall)(word.tpe, word.span)

        Phrase(indexAssign :: ifExpr  :: Nil)(word.tpe, word.span)

      case With(expr, args) =>
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

        // 2. val hashIndex = setParam("x", v)
        //    val oldValueX = getLastOverwrittenValue()
        //    (hashIndex, oldX)
        val restorePairSyms = args.zip(argValueSyms).map: (arg, argValueSym) =>
          val paramName = arg.paramRef.symbol.fullName
          val key = StringLit(paramName)(arg.paramRef.span)
          val value = Ident(argValueSym)(arg.rhs.span)
          val funSetParam = Ident(runtime.ParamSupport_setParam)(arg.span)
          val setParamCall = Apply(funSetParam, key :: value :: Nil)(IntType, arg.span)
          val hashIndexSym = new Symbol("hash_index_" + paramName, IntType, Flags.Val, owner = ctx.funSymbol, sourcePos = arg.rhs.pos)
          ctx.locals += hashIndexSym
          stats += Assign(hashIndexSym, setParamCall)(arg.span)

          val funGetLastOverwrittenValue = Ident(runtime.ParamSupport_getLastOverwrittenValue)(arg.span)
          val getLastOverwrittenValueCall = Apply(funGetLastOverwrittenValue, Nil)(AnyType, arg.paramRef.span)
          val oldValueSym = new Symbol("old_value_" + paramName, arg.rhs.tpe, Flags.Val, owner = ctx.funSymbol, sourcePos = arg.rhs.pos)
          ctx.locals += oldValueSym
          stats += Assign(oldValueSym, getLastOverwrittenValueCall)(arg.span)

          (hashIndexSym, oldValueSym)

        // 3. val res = expr only if expr is not void
        val resSym = new Symbol("res", expr.tpe, Flags.Val, owner = ctx.funSymbol, sourcePos = null)
        if expr.tpe.isVoidType then
          stats += this(expr)
        else
          ctx.locals += resSym
          stats += Assign(resSym, this(expr))(expr.span)

        // 4. restore(hashIndex, oldValueX)
        paramRefs.zip(restorePairSyms).foreach:
          case (paramRef, (hashIndexSym, oldValueSym)) =>
            val index = Ident(hashIndexSym)(paramRef.span)
            val value = Ident(oldValueSym)(paramRef.span)
            val restoreParam = Ident(runtime.ParamSupport_restoreParam)(paramRef.span)
            val restoreParamCall = Apply(restoreParam, index :: value :: Nil)(AnyType, paramRef.span)

            stats += restoreParamCall

        // 5. res
        if !expr.tpe.isVoidType then
          stats += Ident(resSym)(expr.span)

        Phrase(stats.toList)(expr.tpe, word.span)

      case _ => recur(word)
