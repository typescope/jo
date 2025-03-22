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
class LowerContextParams(runtime: NativeRuntime) extends phases.Phase[Symbol]:
  val contextObject = phases.Phase.OwnerContext

  val BoolType = Definitions.instance.BoolType
  val IntType = Definitions.instance.IntType
  val AddrType = TypeRef(runtime.Core_Addr)

  override def transformIdent(word: Ident)(using ctx: Context): Word =
    word match
      case Ident(sym) if sym.isAllOf(Flags.Context | Flags.Param) =>
        // Use AnyType instead String to avoid creating String and make sure its address is static
        // At runtime, it's a byte array initialized in the constant area
        val paramName = sym.fullName
        val key = Encoded(StringLit(paramName)(AnyType, word.span))(AddrType)

        if sym.is(Flags.Default) then
          val getParamIndexFun = Ident(runtime.ParamSupport_getParamIndex)(word.span)
          val getParamIndexCall = Apply(getParamIndexFun, key :: Nil)(IntType, word.span)

          val indexSym =
            given Source = ctx.sourcePos.source
            new Symbol("index_" + paramName, IntType, Flags.Val, owner = ctx, sourcePos = word.pos)

          val indexIdent = Ident(indexSym)(word.span)
          val indexAssign = Assign(indexIdent, getParamIndexCall)(word.span)

          val readValueAtFun = Ident(runtime.ParamSupport_readValueAt)(word.span)
          val readValueAtCall = Encoded(Apply(readValueAtFun, indexIdent :: Nil)(AnyType, word.span))(word.tpe)

          val lessThanFun = Ident(Definitions.instance.Predef_lt)(word.span)
          val zero = IntLit(0)(IntType, word.span)
          val cond = Apply(lessThanFun, indexIdent :: zero :: Nil)(BoolType, word.span)

          val defaultFun = Ident(sym.defaultFunction)(word.span)
          val defaultCall = Apply(defaultFun, args = Nil)(word.tpe, word.span)

          val ifExpr = If(cond, defaultCall, readValueAtCall)(word.tpe, word.span)

          Block(indexAssign :: ifExpr  :: Nil)(word.tpe, word.span)

        else
          // The static analysis ensures that the value is available
          val getParamFun = Ident(runtime.ParamSupport_getParam)(word.span)
          Encoded(Apply(getParamFun, key :: Nil)(AnyType, word.span))(word.tpe)

      case _ =>
        word

  override def transformWith(word: With)(using ctx: Context): Word =
    val With(expr, args, _) = word
    given Source = ctx.sourcePos.source

    val paramRefs = args.map(_.paramRef)

    val stats = new mutable.ArrayBuffer[Word]

    // 1. args are evaluated with the outer context
    val argValueSyms = args.map: arg =>
      val paramName = arg.paramRef.symbol.fullName
      val argValueSym = new Symbol("arg_" + paramName, arg.rhs.tpe, Flags.Val, owner = ctx, sourcePos = arg.rhs.pos)
      stats += Assign(Ident(argValueSym)(arg.paramRef.span), this(arg.rhs))(arg.rhs.span)
      argValueSym

    // 2. val hashIndex = setParam("x", v)
    //    val oldValueX = getLastOverwrittenValue()
    //    (hashIndex, oldX)
    val restorePairSyms = args.zip(argValueSyms).map: (arg, argValueSym) =>
      val paramName = arg.paramRef.symbol.fullName
      // Use AnyType instead String to avoid creating String and make sure its address is static
      // At runtime, it's a byte array initialized in the constant area
      val key = Encoded(StringLit(paramName)(AnyType, arg.paramRef.span))(AddrType)
      val value = Ident(argValueSym)(arg.rhs.span)
      val funSetParam = Ident(runtime.ParamSupport_setParam)(arg.span)
      val setParamCall = Apply(funSetParam, key :: value :: Nil)(IntType, arg.span)
      val hashIndexSym = new Symbol("hash_index_" + paramName, IntType, Flags.Val, owner = ctx, sourcePos = arg.rhs.pos)
      stats += Assign(Ident(hashIndexSym)(arg.paramRef.span), setParamCall)(arg.span)

      val funGetLastOverwrittenValue = Ident(runtime.ParamSupport_getLastOverwrittenValue)(arg.span)
      val getLastOverwrittenValueCall = Apply(funGetLastOverwrittenValue, Nil)(AnyType, arg.paramRef.span)
      val oldValueSym = new Symbol("old_value_" + paramName, arg.rhs.tpe, Flags.Val, owner = ctx, sourcePos = arg.rhs.pos)
      stats += Assign(Ident(oldValueSym)(arg.paramRef.span), getLastOverwrittenValueCall)(arg.span)

      (hashIndexSym, oldValueSym)

    // 3. val res = expr only if expr is not void
    val resSym = new Symbol("res", expr.tpe, Flags.Val, owner = ctx, sourcePos = null)
    if expr.tpe.isVoidType then
      stats += this(expr)
    else
      stats += Assign(Ident(resSym)(expr.span), this(expr))(expr.span)

    // 4. restore(hashIndex, oldValueX)
    paramRefs.zip(restorePairSyms).foreach:
      case (paramRef, (hashIndexSym, oldValueSym)) =>
        val index = Ident(hashIndexSym)(paramRef.span)
        val value = Ident(oldValueSym)(paramRef.span)
        val restoreParam = Ident(runtime.ParamSupport_restoreParam)(paramRef.span)
        val restoreParamCall = Apply(restoreParam, index :: value :: Nil)(AnyType, paramRef.span).dropValue

        stats += restoreParamCall

    // 5. res
    if !expr.tpe.isVoidType then
      stats += Ident(resSym)(expr.span)

    Block(stats.toList)(expr.tpe, word.span)
