package typing

import ast.Positions.*

import sast.*
import sast.Types.*
import sast.Symbols.*

import reporting.Reporter

object Extensions:
  /** Validate extension methods attached to a base type.
    *
    * Each method must be an extension method (preParamCount > 0) and
    * its pre-parameter type must be compatible with the base type.
    *
    * This check is invoked lazily via Checks.add to avoid cycles.
    */
  def check(methods: List[Symbol], baseType: Type, pos: SourcePosition)
      (using defn: Definitions, rp: Reporter)
  : List[Symbol] =
    methods.filter: sym =>
      checkMethod(sym, baseType, pos)

  private def checkMethod(sym: Symbol, baseType: Type, pos: SourcePosition)
      (using defn: Definitions, rp: Reporter)
  : Boolean =
    val info = sym.info

    val procType = info match
      case tl: TypeLambda => tl.body.asProcType
      case pt: ProcType => pt
      case _ =>
        Reporter.error(s"Extension method ${sym.name} has unexpected type", pos)
        return false

    if procType.preParamCount == 0 then
      Reporter.error(s"Method ${sym.name} is not an extension method", pos)
      return false

    if procType.preParamCount > 1 then
      Reporter.error(s"Extension method ${sym.name} must have exactly one pre-parameter", pos)
      return false

    // Check that the base type conforms to the method's pre-parameter type.
    // For polymorphic extensions, instantiate type parameters with fresh type
    // vars so that unification can determine them from the base type.
    val preParamType = procType.preParamTypes.head
    given tvars: TypeVars = new UnificationSolver
    val map = new TypeOps.InstantiateTypeParam(pos.span)
    val preParamTypeFlex = map(preParamType)(using ())
    if !Subtyping.conforms(baseType, preParamTypeFlex) then
      Reporter.error(
        s"Base type ${baseType.show} does not conform to parameter type ${preParamType.show} of extension method ${sym.name}",
        pos)
      false
    else
      true
