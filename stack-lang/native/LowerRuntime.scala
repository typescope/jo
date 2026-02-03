package native

import sast.*
import sast.Trees.*

import phases.Phase
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
class LowerRuntime(runtime: NativeRuntime)(using defn: Definitions) extends Phase:
  val StringType = defn.StringType

  override def transformApply(apply: Apply)(using ctx: Context): Word =
    val Apply(fun, args, autos) = apply

    fun match
      case Select(qual, name) if qual.tpe.isSubtype(StringType) =>
        assert(autos.isEmpty, "No autos expected for String, found = " + autos)

        val qual2 = transform(qual)
        val args2 = for arg <- args yield transform(arg)
        val argsAll = qual2 :: args2

        Ident(runtime.Core_StringOps.termMember(name))(fun.span).appliedTo(argsAll*)

      case TypeApply(Ident(sym), tpt :: Nil) if sym == runtime.Core_cast =>
        assert(autos.isEmpty, "No autos expected, found = " + autos)
        assert(args.size == 1, args)
        Encoded(transform(args.head))(tpt.tpe)

      case _ =>
        super.transformApply(apply)
