package native

import ast.Positions.Source
import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import native.runtime.NativeRuntime

import scala.collection.mutable

/** The compiler phase translate context parameters to runtime calls
  *
  * This phase assumes the following support functions defined in
  * runtime/native/ParamSupport.stk:
  *
  *     fun getParam(key: Any): Any = ...
  *     fun setParam(key: Any, value: Any): Int = ...
  *     fun getLastOverwrittenValue(): Any = ...
  *     fun restoreParam(index: Int, value: Any): Unit = ...
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
class LowerContextParams(runtime: NativeRuntime)(using defn: Definitions) extends phases.Phase[Symbol]:
  val contextObject = phases.Phase.OwnerContext

  val BoolType = defn.BoolType
  val IntType = defn.IntType
  val AddrType = StaticRef(runtime.Core_Addr)

  override def transformIdent(word: Ident)(using ctx: Context): Word =
    word match
      case Ident(sym) if sym.isAllOf(Flags.Context | Flags.Param) =>
        // Use AnyType instead String to avoid creating String and make sure its address is static
        // At runtime, it's a byte array initialized in the constant area
        val paramName = sym.fullName
        val lit = Literal(Constant.String(paramName))(AnyType, word.span)
        val key = lit.encodedAs(AddrType)
        // The static analysis ensures that the value is available
        val getParamFun = Ident(runtime.ParamSupport_getParam)(word.span)
        Encoded(Apply(getParamFun, key :: Nil)(AnyType))(word.tpe)

      case _ =>
        word

  override def transformWith(word: With)(using ctx: Context): Word =
    val With(expr, args) = word
    given Source = ctx.sourcePos.source

    val paramRefs = args.map(_.ident)

    val stats = new mutable.ArrayBuffer[Word]

    // 1. args are evaluated with the outer context
    val argValueSyms = args.map: arg =>
      val paramName = arg.symbol.fullName
      val argValueSym = Symbol.createSymbol("arg_" + paramName, arg.rhs.tpe, Flags.Synthetic, owner = ctx, pos = arg.rhs.pos)
      stats += Assign(Ident(argValueSym)(arg.ident.span), this(arg.rhs))
      argValueSym

    // 2. val hashIndex = setParam("x", v)
    //    val oldValueX = getLastOverwrittenValue()
    //    (hashIndex, oldX)
    val restorePairSyms = args.zip(argValueSyms).map: (arg, argValueSym) =>
      val paramName = arg.symbol.fullName
      // Use AnyType instead String to avoid creating String and make sure its address is static
      // At runtime, it's a byte array initialized in the constant area
      val lit = Literal(Constant.String(paramName))(AnyType, arg.ident.span)
      val key = lit.encodedAs(AddrType)
      val value = Ident(argValueSym)(arg.rhs.span)
      val funSetParam = Ident(runtime.ParamSupport_setParam)(arg.span)
      val setParamCall = Apply(funSetParam, key :: value :: Nil)(IntType)
      val hashIndexSym = Symbol.createSymbol("hash_index_" + paramName, IntType, Flags.Synthetic, owner = ctx, pos = arg.rhs.pos)
      stats += Assign(Ident(hashIndexSym)(arg.ident.span), setParamCall)

      val funGetLastOverwrittenValue = Ident(runtime.ParamSupport_getLastOverwrittenValue)(arg.span)
      val getLastOverwrittenValueCall = Apply(funGetLastOverwrittenValue, Nil)(AnyType)
      val oldValueSym = Symbol.createSymbol("old_value_" + paramName, arg.rhs.tpe, Flags.Synthetic, owner = ctx, pos = arg.rhs.pos)
      stats += Assign(Ident(oldValueSym)(arg.ident.span), getLastOverwrittenValueCall.encodedAs(arg.rhs.tpe))

      (hashIndexSym, oldValueSym)

    // 3. val res = expr only if expr is not void
    val resSym = Symbol.createSymbol("res", expr.tpe, Flags.Synthetic, owner = ctx, pos = null)
    if expr.tpe.isVoidType then
      stats += this(expr)
    else
      stats += Assign(Ident(resSym)(expr.span), this(expr))

    // 4. restore(hashIndex, oldValueX)
    paramRefs.zip(restorePairSyms).foreach:
      case (paramRef, (hashIndexSym, oldValueSym)) =>
        val index = Ident(hashIndexSym)(paramRef.span)
        val value = Ident(oldValueSym)(paramRef.span)
        val restoreParam = Ident(runtime.ParamSupport_restoreParam)(paramRef.span)
        val restoreParamCall = Apply(restoreParam, index :: value :: Nil)(AnyType).dropValue

        stats += restoreParamCall

    // 5. res
    if !expr.tpe.isVoidType then
      stats += Ident(resSym)(expr.span)

    Block(stats.toList)(word.span)
