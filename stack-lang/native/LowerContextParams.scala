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
  *     fun restoreParam(index: Int, value: Any): void = ...
  *     fun newPage(): Any = ...
  *     fun restorePage(old: Any): void = ...
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
class LowerContextParams(runtime: NativeRuntime) extends phases.Phase:

  val BoolType = Definitions.instance.BoolType
  val IntType = Definitions.instance.IntType

  class FunContext(val funSymbol: Symbol)
  type Context = FunContext
  def createContext(fdef: FunDef) = FunContext(fdef.symbol)

  override def transformIdent(word: Ident)(using ctx: Context): Word =
    word match
      case Ident(sym) if sym.isAllOf(Flags.Context | Flags.Param) =>
        // Use AnyType instead String to avoid creating String and make sure its address is static
        // At runtime, it's a byte array initialized in the constant area
        val arg = StringLit(sym.fullName)(AnyType, word.span)
        val fun = Ident(runtime.ParamSupport_getParam)(word.span)
        val app = Apply(fun, arg :: Nil)(word.tpe, word.span)
        app

      case _ =>
        word

  override def transformDefaultParam(word: DefaultParam)(using ctx: Context): Word =
    val DefaultParam(paramRef, default) = word
      val paramName = paramRef.symbol.fullName
      // Use AnyType instead String to avoid creating String and make sure its address is static
      // At runtime, it's a byte array initialized in the constant area
      val key = StringLit(paramName)(AnyType, paramRef.span)
      val funGetParamIndex = Ident(runtime.ParamSupport_getParamIndex)(paramRef.span)
      val getParamIndexCall = Apply(funGetParamIndex, key :: Nil)(IntType, paramRef.span)

      val indexSym = new Symbol("index_" + paramName, IntType, Flags.Val, owner = ctx.funSymbol, sourcePos = null)
      val indexAssign = Assign(Ident(indexSym)(paramRef.span), getParamIndexCall)(paramRef.span)

      val indexIdent = Ident(indexSym)(paramRef.span)

      val funReadValueAt = Ident(runtime.ParamSupport_readValueAt)(paramRef.span)
      val readValueAtCall = Apply(funReadValueAt, indexIdent :: Nil)(word.tpe, paramRef.span)

      val funLessThan = Ident(Definitions.instance.Predef_lt)(paramRef.span)
      val zero = Literal(Constant.Int(0))(IntType, paramRef.span)
      val cond = Apply(funLessThan, indexIdent :: zero :: Nil)(BoolType, paramRef.span)
      val ifExpr = If(cond, default, readValueAtCall)(word.tpe, word.span)

      Block(indexAssign :: ifExpr  :: Nil)(word.tpe, word.span)

  override def transformWith(word: With)(using ctx: Context): Word =
    val With(expr, args, only) = word
    given Source = ctx.funSymbol.sourcePos.source

    val paramRefs = args.map(_.paramRef)

    val stats = new mutable.ArrayBuffer[Word]

    // 1. args are evaluated with the outer context
    val argValueSyms = args.map: arg =>
      val paramName = arg.paramRef.symbol.fullName
      val argValueSym = new Symbol("arg_" + paramName, arg.rhs.tpe, Flags.Val, owner = ctx.funSymbol, sourcePos = arg.rhs.pos)
      stats += Assign(Ident(argValueSym)(arg.paramRef.span), this(arg.rhs))(arg.rhs.span)
      argValueSym

    if only then
      // 2. newPage
      val oldPageSym = new Symbol("oldPage", AnyType, Flags.Val, owner = ctx.funSymbol, sourcePos = word.pos)

      val funNewPage = Ident(runtime.ParamSupport_newPage)(word.span)
      val newPageCall = Apply(funNewPage, args = Nil)(AnyType, word.span)
      stats += Assign(Ident(oldPageSym)(word.span), newPageCall)(word.span)

      // 3. setParam("x", v)
      args.zip(argValueSyms).foreach: (arg, argValueSym) =>
        val paramName = arg.paramRef.symbol.fullName
        // Use AnyType instead String to avoid creating String and make sure its address is static
        // At runtime, it's a byte array initialized in the constant area
        val key = StringLit(paramName)(AnyType, arg.paramRef.span)
        val value = Ident(argValueSym)(arg.rhs.span)
        val funSetParam = Ident(runtime.ParamSupport_setParam)(arg.span)
        val setParamCall = Apply(funSetParam, key :: value :: Nil)(AnyType, arg.span)
        stats += dropValue(setParamCall)

      // 4. val res = expr only if expr is not void
      val resSym = new Symbol("res", expr.tpe, Flags.Val, owner = ctx.funSymbol, sourcePos = expr.pos)
      if expr.tpe.isVoidType then
        stats += this(expr)
      else
        stats += Assign(Ident(resSym)(expr.span), this(expr))(expr.span)

      // 5. restore page
      val funRestorePage = Ident(runtime.ParamSupport_restorePage)(word.span)
      val oldPageIdent = Ident(oldPageSym)(word.span)
      val restorePageCall = Apply(funRestorePage, oldPageIdent :: Nil)(VoidType, word.span)
      stats += restorePageCall

      // 6. res
      if !expr.tpe.isVoidType then
        stats += Ident(resSym)(expr.span)

      Block(stats.toList)(expr.tpe, word.span)

    else

      // 2. val hashIndex = setParam("x", v)
      //    val oldValueX = getLastOverwrittenValue()
      //    (hashIndex, oldX)
      val restorePairSyms = args.zip(argValueSyms).map: (arg, argValueSym) =>
        val paramName = arg.paramRef.symbol.fullName
        // Use AnyType instead String to avoid creating String and make sure its address is static
        // At runtime, it's a byte array initialized in the constant area
        val key = StringLit(paramName)(AnyType, arg.paramRef.span)
        val value = Ident(argValueSym)(arg.rhs.span)
        val funSetParam = Ident(runtime.ParamSupport_setParam)(arg.span)
        val setParamCall = Apply(funSetParam, key :: value :: Nil)(IntType, arg.span)
        val hashIndexSym = new Symbol("hash_index_" + paramName, IntType, Flags.Val, owner = ctx.funSymbol, sourcePos = arg.rhs.pos)
        stats += Assign(Ident(hashIndexSym)(arg.paramRef.span), setParamCall)(arg.span)

        val funGetLastOverwrittenValue = Ident(runtime.ParamSupport_getLastOverwrittenValue)(arg.span)
        val getLastOverwrittenValueCall = Apply(funGetLastOverwrittenValue, Nil)(AnyType, arg.paramRef.span)
        val oldValueSym = new Symbol("old_value_" + paramName, arg.rhs.tpe, Flags.Val, owner = ctx.funSymbol, sourcePos = arg.rhs.pos)
        stats += Assign(Ident(oldValueSym)(arg.paramRef.span), getLastOverwrittenValueCall)(arg.span)

        (hashIndexSym, oldValueSym)

      // 3. val res = expr only if expr is not void
      val resSym = new Symbol("res", expr.tpe, Flags.Val, owner = ctx.funSymbol, sourcePos = null)
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
          val restoreParamCall = Apply(restoreParam, index :: value :: Nil)(AnyType, paramRef.span)

          stats += restoreParamCall

      // 5. res
      if !expr.tpe.isVoidType then
        stats += Ident(resSym)(expr.span)

      Block(stats.toList)(expr.tpe, word.span)
