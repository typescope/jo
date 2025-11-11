package typing

import ast.{ Trees => Ast }
import ast.Positions.*

import sast.*
import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import reporting.Diagnostics
import reporting.Reporter
import typing.Inference.TargetType

import scala.collection.mutable

object Adapters:
  class ShadowedAdapter(adapterParamType: Type, earlierAdapter: Symbol, earlierAdapterPos: SourcePosition, currentAdapterPos: SourcePosition)
      (using defn: Definitions)
  extends Diagnostics.DoublePositionedReport:
    val kind = Diagnostics.Kind.Error

    val pos1 = currentAdapterPos
    val pos2 = earlierAdapterPos

    val message1 =
      s"Adapter is shadowed: parameter type ${adapterParamType.show} conforms to an earlier adapter's parameter type"

    val message2 =
      val paramType = earlierAdapter.info.asProcType.params.head.info
      s"Earlier adapter ${earlierAdapter.name} with parameter type ${paramType.show} is defined here"

  def check(adapters: List[Ast.RefTree], paramType: Type, namer: Namer)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : List[Ident] =
    val valid = new mutable.ArrayBuffer[Ident]

    for adapter <- adapters do
      val adapterRef =
        given TargetType = TargetType.Unknown
        namer.transform(adapter)

      adapterRef.tpe match
        case StaticRef(sym) if sym.is(Flags.Fun) =>
          val procType = sym.info.asProcType

          // Check: must have exactly one parameter
          if procType.params.size != 1 then
            Reporter.error(s"Adapter must take exactly one parameter, found ${procType.params.size} parameters", adapter.pos)

          // Check: must have no auto parameters
          else if procType.autos.nonEmpty then
            Reporter.error("Adapter cannot have auto parameters", adapter.pos)

          // Check: must have no type parameters
          else if procType.tparams.nonEmpty then
            Reporter.error("Adapter cannot have type parameters", adapter.pos)

          // Check: return type must conform to parameter type
          else if !Subtyping.conforms(procType.resultType, paramType) then
            Reporter.error(s"Adapter return type ${procType.resultType.show} does not conform to parameter type ${paramType.show}", adapter.pos)

          else
            // Check: no shadowed adapters - adapter parameter type must not conform to any earlier adapter's parameter type
            val adapterParamType = procType.params.head.info
            val shadowing = valid.find: earlierAdapter =>
              val earlierProcType = earlierAdapter.tpe.asProcType
              val earlierParamType = earlierProcType.params.head.info
              Subtyping.conforms(adapterParamType, earlierParamType)

            shadowing match
              case Some(earlierAdapter) =>
                rp.report(ShadowedAdapter(adapterParamType, earlierAdapter.symbol, earlierAdapter.pos, adapter.pos))

              case None =>
                valid += Ident(sym)(adapter.span)

        case tp =>
          Reporter.error("A reference to function expected, found = " + tp.show, adapter.pos)
    end for
    valid.toList
