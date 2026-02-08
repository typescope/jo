package typing

import ast.Positions.*
import ast.{ Trees => Ast }

import sast.*
import sast.Types.*
import sast.Symbols.*

import reporting.Reporter

import scala.collection.mutable

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

  /** Check that extension methods which shadow base type members are declared
    * in the `override` clause, and that all override names actually shadow
    * a member of the base type.
    *
    * The check covers direct members, direct view members, and delegate view members.
    */
  def checkOverrides(methods: List[Symbol], baseType: Type, overrides: List[Ast.Ident], pos: SourcePosition)
      (using defn: Definitions, rp: Reporter)
  : Unit =
    val remaining = mutable.LinkedHashMap.empty[String, Ast.Ident]

    given Source = pos.source

    // Build map, report duplicate override names
    for id <- overrides do
      if remaining.contains(id.name) then
        Reporter.error(s"Duplicate override declaration .${id.name}", id.pos)
      else
        remaining(id.name) = id

    // Check each extension method for shadowing
    for sym <- methods do
      if hasMember(baseType, sym.name) then
        if remaining.remove(sym.name).isEmpty then
          Reporter.warn(
            s"Extension method .${sym.name} shadows a member of the base type. Use `override [.${sym.name}]` for explicit overriding",
            pos)

    // Report override names that don't shadow anything
    for (_, id) <- remaining do
      Reporter.warn(
        s"Override declaration .${id.name} does not shadow any member of the base type",
        id.pos)

  /** Check whether the base type has a member with the given name,
    * including direct members, direct view members, and delegate view members.
    */
  private def hasMember(baseType: Type, name: String)(using Definitions): Boolean =
    if baseType.hasTermMember(name) then return true

    baseType.approx match
      case classInfo: ClassInfo =>
        // Check direct views (interface types in the `view` clause)
        val hasDirectView = classInfo.directViews.exists(_.hasTermMember(name))
        if hasDirectView then return true

        // Check delegate views (fields marked as `view`)
        val hasDelegateView = baseType.delegateViews.exists(_.hasTermMember(name))
        if hasDelegateView then return true

        false

      case _ => false
