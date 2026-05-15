package typing

import ast.Positions.*

import sast.*
import sast.Types.*
import sast.Symbols.*

import reporting.Reporter

object Extensions:
  /** Validate a single extension method against a base type and check shadowing.
    *
    * `isOverride` — true if the method is declared as intentionally shadowing:
    *   `!` in a user-written `:+` list, or `@shadow` on the method definition.
    * `fromAnnotation` — true when the marker comes from `@shadow` on the method
    *   definition (generated from an `extension` def); false when it comes from
    *   an explicit `!` in a user-written `:+` type expression.
    *
    * Emits warnings for unmatched shadowing/override intent.  Returns false on
    * hard errors (wrong method shape or non-conforming base type).
    */
  def checkMethod(sym: Symbol, baseType: Type, isOverride: Boolean, fromAnnotation: Boolean, pos: SourcePosition)
      (using defn: Definitions, rp: Reporter)
  : Boolean =
    val procType = sym.info match
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
    // For polymorphic extensions, instantiate only extension-header type
    // parameters with fresh vars; method-level type parameters are not inferred
    // at extension attachment sites.
    val preParamType = procType.preParamTypes.head
    given tvars: TypeVars = new UnificationSolver
    val preParamTypeFlex =
      if procType.preTypeParamCount == 0 then preParamType
      else
        val targs = procType.preTparams.map(tparam => TypeVar(tparam.name, pos.span))
        TypeOps.substSymbols(preParamType, procType.preTparams, targs)

    if !Subtyping.conforms(baseType, preParamTypeFlex) then
      Reporter.error(
        s"Base type ${baseType.show} does not conform to parameter type ${preParamType.show} of extension method ${sym.name}",
        pos)
      return false

    if !tvars.typeVars.forall(tvars.isInstantiated) then
      Reporter.error(
        s"Extension method ${sym.name} has type parameters that cannot be inferred from base type",
        pos)
      return false

    // Shadow / override consistency check
    val shadows = hasMember(baseType, sym.name)

    if shadows && !isOverride then
      if fromAnnotation then
        Reporter.warn(
          s"Extension method .${sym.name} shadows a member of the base type. Add `@shadow` to mark the override",
          pos)
      else
        Reporter.warn(
          s"Extension method .${sym.name} shadows a member of the base type. Use `${sym.name}!` to mark the override",
          pos)

    else if !shadows && isOverride then
      if fromAnnotation then
        Reporter.warn(
          s"`@shadow` on .${sym.name} is unused: no member of that name exists in the base type",
          pos)
      else
        Reporter.warn(
          s"Override marker `!` on .${sym.name} is unused: no member of that name exists in the base type",
          pos)

    true

  /** Check whether the base type has a member with the given name,
    * including direct members, direct view members, and delegate view members.
    */
  private def hasMember(baseType: Type, name: String)(using Definitions): Boolean =
    baseType.hasTermMember(name)
      || baseType.directViews.exists(_.hasTermMember(name))
      || baseType.delegateViews.exists(_.hasTermMember(name))
