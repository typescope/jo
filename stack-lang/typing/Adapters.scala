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

  def check(adapters: List[Ast.ParamAdapter], rawParamType: Type, namer: Namer)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : List[Trees.ParamAdapter] =
    // ..T ==> T
    val paramType = rawParamType.stripVarargs

    val valid = new mutable.ArrayBuffer[Trees.ParamAdapter]

    for adapter <- adapters do
      adapter match
        case Ast.ParamAdapter.Function(ref) =>
          val adapterRef =
            given TargetType = TargetType.Unknown
            namer.transform(ref)

          adapterRef.tpe match
            case StaticRef(sym) if sym.is(Flags.Fun) =>
              val procType = sym.info.asProcType

              // Check: must have exactly one parameter
              if procType.params.size != 1 then
                Reporter.error(s"Adapter must take exactly one parameter, found ${procType.params.size} parameters", ref.pos)

              // Check: must have no auto parameters
              else if procType.autos.nonEmpty then
                Reporter.error("Adapter cannot have auto parameters", ref.pos)

              // Check: must have no type parameters
              else if procType.tparams.nonEmpty then
                Reporter.error("Adapter cannot have type parameters", ref.pos)

              // Check: return type must conform to parameter type
              else if !Subtyping.conforms(procType.resultType, paramType) then
                Reporter.error(s"Adapter return type ${procType.resultType.show} does not conform to parameter type ${paramType.show}", ref.pos)

              else
                // Check: no shadowed adapters - adapter parameter type must not conform to any earlier adapter's parameter type
                val adapterParamType = procType.params.head.info
                val shadowing = valid.find: earlierAdapter =>
                  earlierAdapter match
                    case ParamAdapter.Member(memberName) =>
                      // Earlier adapter is a member adapter
                      // Check if this function adapter is shadowed: does the function's argument type have the member?
                      adapterParamType.getTermMember(memberName) match
                        case Some(memberType) =>
                          // The type has the member - check if it returns the right type
                          // For parameterless methods (ProcType with no params), extract the result type
                          val effectiveType = memberType match
                            case procType: ProcType if procType.params.isEmpty && procType.autos.isEmpty =>
                              procType.resultType
                            case tp => tp
                          Subtyping.conforms(effectiveType, paramType)
                        case None =>
                          // The type doesn't have the member - not shadowed
                          false
                    case ParamAdapter.Function(earlierSym) =>
                      // Earlier adapter is a function adapter
                      val earlierProcType = earlierSym.info.asProcType
                      val earlierParamType = earlierProcType.params.head.info
                      Subtyping.conforms(adapterParamType, earlierParamType)

                shadowing match
                  case Some(earlierAdapter) =>
                    earlierAdapter match
                      case func @ ParamAdapter.Function(earlierSym) =>
                        rp.report(ShadowedAdapter(adapterParamType, earlierSym, func.span.toPos, ref.pos))
                      case member @ ParamAdapter.Member(memberName) =>
                        Reporter.error(s"Adapter is shadowed by earlier member adapter .$memberName", ref.pos)

                  case None =>
                    valid += ParamAdapter.Function(sym)(ref.span)

            case tp =>
              if !tp.isError then
                Reporter.error("A reference to function expected, found = " + tp.show, ref.pos)

        case Ast.ParamAdapter.Member(memberName) =>
          // Check: no shadowed member adapters
          val shadowing = valid.find: earlierAdapter =>
            earlierAdapter match
              case ParamAdapter.Member(name) =>
                // Earlier adapter is a member adapter with the same name
                name == memberName
              case _ =>
                // Earlier adapter is a function adapter
                // Function adapters don't shadow member adapters (open vs closed type sets)
                false

          shadowing match
            case Some(earlierAdapter) =>
              // Report shadowing error (simplified - use adapter.span instead of detailed position)
              Reporter.error(s"Member adapter .$memberName is shadowed by earlier member adapter", adapter.span.toPos)

            case None =>
              valid += ParamAdapter.Member(memberName)(adapter.span)

    end for
    valid.toList
