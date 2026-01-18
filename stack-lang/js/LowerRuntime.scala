package js

import sast.*
import sast.Trees.*

/** Lower String to JS runtime calls.
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

        Ident(runtime.StringOps.termMember(name))(fun.span).appliedTo(argsAll*)

      case _ =>
        super.transformApply(apply)
