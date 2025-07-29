package js

import sast.*
import sast.Trees.*
import sast.Symbols.*

/** Lower String and Array to JS runtime calls.
  *
  */
class LowerRuntime(runtime: JSRuntime)(using defn: Definitions) extends phases.Phase[Unit]:
  val contextObject = phases.Phase.DummyContext

  val Predef_String = defn.Predef_String
  val StringType = defn.StringType
  val BoolType = defn.BoolType
  val IntType = defn.IntType

  val rewiring = Map(
    defn.Predef_abort      -> runtime.JS_abort,
    defn.Predef_byteToChar -> runtime.JS_byteToChar,
    defn.Predef_byteToInt  -> runtime.JS_byteToInt,
    defn.Predef_charToByte -> runtime.JS_charToByte,
    defn.Predef_charToInt  -> runtime.JS_charToInt,
    defn.Predef_charToStr  -> runtime.JS_charToStr,
    defn.Predef_intToByte  -> runtime.JS_intToByte,
    defn.Predef_intToChar  -> runtime.JS_intToChar,
    defn.Predef_intToStr   -> runtime.JS_intToStr,
    defn.Array_get         -> runtime.JS_Array_get,
    defn.Array_set         -> runtime.JS_Array_set,
    defn.Array_size        -> runtime.JS_Array_size,
  )

  override def transformTypeApply(tapp: TypeApply)(using ctx: Context): Word =
    tapp match
      case TypeApply(ref @ Ident(sym), tpt :: Nil) =>
        if sym.refers(defn.Array_create) then
          if Subtyping.conforms(tpt.tpe, IntType) then
            Ident(runtime.JS_Array_createInt)(tapp.span)

          else if Subtyping.conforms(tpt.tpe, BoolType) then
            Ident(runtime.JS_Array_createBool)(tapp.span)

          else
            Ident(runtime.JS_Array_createObject)(tapp.span).appliedToTypeTrees(tpt)

        else
          super.transformTypeApply(tapp)

      case _ =>
        super.transformTypeApply(tapp)

  override def transformIdent(ref: Ident)(using ctx: Context): Word =
    val sym = ref.symbol
    if sym.isFunction then
      // global function call
      rewiring.get(sym.dealias) match
        case Some(subst) => Ident(subst)(ref.span)
        case _ => ref

    else
      ref

  override def transformSelect(select: Select)(using ctx: Context): Word =
    val Select(qual, name) = select
    if qual.tpe.refers(Predef_String) then
      // After lambda lift, `qual` is stable thus can be thrown away
      assert(qual.isIdempotent, select.show)

      if name == "size" then
        Ident(runtime.JS_String_size)(select.span)

      else if name == "get" then
        Ident(runtime.JS_String_apply)(select.span)

      else if name == "substring" then
        // 'substring' semantics change, need rewire
        Ident(runtime.JS_String_substring)(select.span)

      else if name == "+" then
        // '+' is supported directly by JavaScript, but backend will rewrite `+` to `_plus_`
        Ident(runtime.JS_String_plus)(select.span)

      else if name == "==" then
        Ident(runtime.JS_String_equals)(select.span)

      else
        throw new Exception("Unexpected method on String: " + name)

    else
      super.transformSelect(select)
