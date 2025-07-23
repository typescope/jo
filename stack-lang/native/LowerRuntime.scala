package native

import sast.*
import sast.Sast.*
import sast.Symbols.*

import native.runtime.NativeRuntime

/** This phase lowers Array and String to native runtime calls
  *
  * This phase assumes the following support functions defined in
  * runtime/native/Core.stk:
  *
  *     fun Array_create(size: Int): Any =
  *     fun Array_size(arr: Any): Int = ...
  *     fun Array_apply(arr: Any, i: Int): Any = ...
  *     fun Array_set(arr: Any, i: Int, value: Any): void = ...
  *
  *     fun String_size(repr: StringRepr): Int = ...
  *     fun String_apply(repr: StringRepr, i: Int): Char = ...
  *     fun String_plus(lhs: String, rhs: String): StringRepr = ..
  *     fun String_substring(source: String, from: Int, len: Int): StringRepr = ...
  *
  * This phase should happen before ExplicitAlloc so that arrays are not treated
  * as objects.
  *
  * String literals are handled in the backend for flexibility: Some string
  * literals do not have the type String. For example, the context parameter
  * runtime expects raw byte string as input.
  */
class LowerRuntime(runtime: NativeRuntime)(using defn: Definitions) extends phases.Phase[Unit]:
  val contextObject = phases.Phase.DummyContext

  val Array_Array = defn.Array_Array

  val Predef_String = defn.Predef_String
  val StringType = defn.StringType

  val BoolType = defn.BoolType
  val IntType  = defn.IntType
  val UnitType = defn.UnitType

  val rewiring = Map(
    defn.Predef_abort      -> runtime.Core_abortImpl,
    defn.Predef_byteToChar -> runtime.Core_byteToChar,
    defn.Predef_byteToInt  -> runtime.Core_byteToInt,
    defn.Predef_charToByte -> runtime.Core_charToByte,
    defn.Predef_charToInt  -> runtime.Core_charToInt,
    defn.Predef_charToStr  -> runtime.Core_charToStr,
    defn.Predef_intToByte  -> runtime.Core_intToByte,
    defn.Predef_intToChar  -> runtime.Core_intToChar,
    defn.Predef_intToStr   -> runtime.Core_intToStr,
    defn.Array_create      -> runtime.Core_Array_create,
    defn.Array_get         -> runtime.Core_Array_get,
    defn.Array_set         -> runtime.Core_Array_set,
    defn.Array_size        -> runtime.Core_Array_size,
  )

  override def transformIdent(ref: Ident)(using ctx: Context): Word =
    val sym = ref.symbol
    if sym.isFunction then
      // global function call
      rewiring.get(sym.dealias) match
        case Some(subst) => Ident(subst)(ref.span)
        case _ => ref

    else
      ref

  override def transformApply(app: Apply)(using ctx: Context): Word =
    val Apply(fun, args, autos) = app
     val args2 = args.map(this.apply)
     val autos2 = autos.map(this.apply)

    fun.strip match
      case TypeApply(Ident(sym), tpt :: Nil) if sym.refers(runtime.Core_cast) =>
        assert(args2.size == 1, args2)
        Encoded(args2.head)(tpt.tpe)

      case _ =>
        Apply(this(fun), args2, autos2)(app.tpe)

  override def transformSelect(select: Select)(using ctx: Context): Word =
    val Select(qual, name) = select
    if qual.tpe.refers(Predef_String) then
      // After lambda lift, `qual` is stable thus can be thrown away
      assert(qual.isIdempotent, select.show)

      if name == "size" then
        Ident(runtime.Core_String_size)(select.span)

      else if name == "get" then
        Ident(runtime.Core_String_apply)(select.span)

      else if name == "substring" then
        Ident(runtime.Core_String_substring)(select.span)

      else if name == "+" then
        Ident(runtime.Core_String_plus)(select.span)

      else if name == "==" then
        Ident(runtime.Core_String_equals)(select.span)

      else
        throw new Exception("Unexpected method on String: " + name)

    else
      super.transformSelect(select)
