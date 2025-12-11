package typing

import ast.{ Trees => Ast }
import ast.Positions.*

import sast.*
import sast.Types.*
import sast.Symbols.*

import reporting.Diagnostics
import reporting.Reporter

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
  : List[ParamAdapter] =
    // ..T ==> T
    val paramType = rawParamType.stripVarargs

    val valid = new mutable.ArrayBuffer[(ParamAdapter, Ast.ParamAdapter)]

    for adapter <- adapters do
      adapter match
        case Ast.ParamAdapter.Function(ref) =>
          checkFunctionAdapter(ref, paramType, namer) match
            case Some(typedAdapter) => valid += typedAdapter -> adapter
            case None =>

        case Ast.ParamAdapter.Member(memberName) =>
          valid += ParamAdapter.Member(memberName) -> adapter
      end match
    end for

    val adaptersWithAst = valid.toList

    // The check must be delayed after all symbols are forced to avoid cycles
    validateAdapters(adaptersWithAst, paramType)

  def checkFunctionAdapter(ref: Ast.RefTree, paramType: Type, namer: Namer)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : Option[ParamAdapter] =

    namer.resolveQualid(ref, Universe.Term).flatMap: sym =>
      if !sym.is(Flags.Fun) then
        Reporter.error("A reference to function expected, found = " + sym, ref.pos)
        None
      else
        Some(ParamAdapter.Function(sym))

  def validateAdapters(adapters: List[(ParamAdapter, Ast.ParamAdapter)], paramType: Type)
      (using defn: Definitions, rp: Reporter, so: Source)
  : List[ParamAdapter] =

    val valid = new mutable.ArrayBuffer[(ParamAdapter, Ast.ParamAdapter)]

    adapters.foreach {
      case (adapter @ ParamAdapter.Function(sym), astAdapter) =>
        val procType = sym.info.asProcType

        // Check: must have exactly one parameter
        if procType.params.size != 1 then
          Reporter.error(s"Function adapter must take exactly one parameter, found ${procType.params.size} parameters", astAdapter.pos)

        // Check: must have no auto parameters
        else if procType.autos.nonEmpty then
          Reporter.error("Function adapter cannot have auto parameters", astAdapter.pos)

        // Check: must have no type parameters
        else if procType.tparams.nonEmpty then
          Reporter.error("Adapter cannot have type parameters", astAdapter.pos)

        // Check: return type must conform to parameter type
        else if !Subtyping.conforms(procType.resultType, paramType) then
          Reporter.error(s"Adapter return type ${procType.resultType.show} does not conform to parameter type ${paramType.show}", astAdapter.pos)

        else
          // Check: no shadowed adapters - adapter parameter type must not conform to any earlier adapter's parameter type
          val adapterParamType = procType.params.head.info
          val shadowing = valid.find {
            case (ParamAdapter.Member(memberName), _) =>
              // Earlier adapter is a member adapter
              // Check if this function adapter is shadowed: does the function's argument type have the member?
              adapterParamType.getTermMember(memberName) match
                case Some(memberType) =>
                  // The type has the member - check if it returns the right type
                  // For parameterless methods (ProcType with no regular params), extract the result type
                  // Member adapters can have auto parameters
                  val effectiveType = memberType.effectiveResultType
                  Subtyping.conforms(effectiveType, paramType)

                case None =>
                  // The type doesn't have the member - not shadowed
                  false
              end match

            case (ParamAdapter.Function(earlierSym), _) =>
              // Earlier adapter is a function adapter
              val earlierProcType = earlierSym.info.asProcType
              val earlierParamType = earlierProcType.params.head.info
              Subtyping.conforms(adapterParamType, earlierParamType)
          }

          shadowing match
            case Some((earlierAdapter, earlierAst)) =>
              earlierAdapter match
                case ParamAdapter.Function(earlierSym) =>
                  rp.report(ShadowedAdapter(adapterParamType, earlierSym, earlierAst.pos, astAdapter.pos))

                case ParamAdapter.Member(memberName) =>
                  Reporter.error(s"Adapter is shadowed by earlier member adapter .$memberName", astAdapter.pos)

            case None =>
              valid += adapter -> astAdapter

      case (adapter @ ParamAdapter.Member(name), astAdapter) =>
        // Check: no shadowed member adapters
        // Function adapters don't shadow member adapters (open vs closed type sets)
        val shadowing = valid.find:
          case (_: ParamAdapter.Function, _) => false
          case (ParamAdapter.Member(member), _) => member == name

        shadowing match
          case Some(earlierAdapter) =>
            // Report shadowing error (simplified - use adapter.span instead of detailed position)
            Reporter.error(s"Member adapter .$name is shadowed by earlier member adapter", astAdapter.pos)

          case None =>
            valid += adapter -> astAdapter
    }
    valid.toList.map(_._1)
