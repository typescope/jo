package js

import sast.*
import sast.Trees.*
import sast.Symbols.*

/** Lower String and Array to JS runtime calls.
  *
  */
class LowerRuntime(runtime: JSRuntime)(using defn: Definitions) extends phases.Phase[Unit]:
  val contextObject = phases.Phase.DummyContext

  val StringType = defn.StringType
  val BoolType = defn.BoolType
  val IntType = defn.IntType

  override def transformTypeApply(tapp: TypeApply)(using ctx: Context): Word =
    tapp match
      case TypeApply(ref @ Ident(sym), tpt :: Nil) =>
        if sym == defn.Array_create then
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

  override def transformSelect(select: Select)(using ctx: Context): Word =
    val Select(qual, name) = select

    if qual.tpe.isSubtype(StringType) then
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
