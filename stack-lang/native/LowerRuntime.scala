package native

import sast.*
import sast.Trees.*

import native.runtime.NativeRuntime

/** This phase lowers Array and String to native runtime calls
  *
  * This phase assumes the following support functions defined in
  * runtime/native/Core.jo:
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

  val StringType = defn.StringType

  val BoolType = defn.BoolType
  val IntType  = defn.IntType
  val UnitType = defn.UnitType


  override def transformApply(apply: Apply)(using ctx: Context): Word =
    val Apply(fun, args, autos) = apply

    fun match
      case Select(qual, name) if qual.tpe.isSubtype(StringType) =>
        assert(autos.isEmpty, "No autos expected for String, found = " + autos)

        val qual2 = transform(qual)
        val args2 = for arg <- args yield transform(arg)
        val argsAll = qual2 :: args2

        if name == "size" then
          Ident(runtime.Core_String_size)(fun.span).appliedTo(argsAll*)

        else if name == "get" then
          Ident(runtime.Core_String_apply)(fun.span).appliedTo(argsAll*)

        else if name == "substring" then
          Ident(runtime.Core_String_substring)(fun.span).appliedTo(argsAll*)

        else if name == "+" then
          Ident(runtime.Core_String_plus)(fun.span).appliedTo(argsAll*)

        else if name == "==" then
          Ident(runtime.Core_String_equals)(fun.span).appliedTo(argsAll*)

        else

          throw new Exception("Unexpected method on String: " + name)

      case TypeApply(Ident(sym), tpt :: Nil) if sym == runtime.Core_cast =>
        assert(autos.isEmpty, "No autos expected, found = " + autos)
        assert(args.size == 1, args)
        Encoded(transform(args.head))(tpt.tpe)

      case _ =>
        super.transformApply(apply)
