package js

import sast.*
import sast.Trees.*

/** Lower String and Array to JS runtime calls.
  *
  */
class LowerRuntime(runtime: JSRuntime)(using defn: Definitions) extends phases.Phase[Unit]:
  val contextObject = phases.Phase.DummyContext

  val StringType = defn.StringType
  val BoolType = defn.BoolType
  val IntType = defn.IntType

  override def transformApply(apply: Apply)(using ctx: Context): Word =
    val Apply(fun, args, autos) = apply

    fun match
      case Select(qual, name) if qual.tpe.isSubtype(StringType) =>
        assert(autos.isEmpty, "No autos expected for String, found = " + autos)

        val qual2 = transform(qual)
        val args2 = for arg <- args yield transform(arg)
        val argsAll = qual2 :: args2

        if name == "size" then
          Ident(runtime.JS_String_size)(fun.span).appliedTo(argsAll*)

        else if name == "get" then
          Ident(runtime.JS_String_apply)(fun.span).appliedTo(argsAll*)

        else if name == "substring" then
          // 'substring' semantics change, need rewire
          Ident(runtime.JS_String_substring)(fun.span).appliedTo(argsAll*)

        else if name == "+" then
          // '+' is supported directly by JavaScript, but backend will rewrite `+` to `_plus_`
          Ident(runtime.JS_String_plus)(fun.span).appliedTo(argsAll*)

        else if name == "==" then
          Ident(runtime.JS_String_equals)(fun.span).appliedTo(argsAll*)

        else
          throw new Exception("Unexpected method on String: " + name)

      case Select(qual, name) if name == "+" =>
        // println("qual.tpe = " + qual.tpe.show)
        // println("qual.tpe.isSubtype(StringType) = " + qual.tpe.isSubtype(StringType))
        super.transformApply(apply)

      case _ =>
        super.transformApply(apply)
