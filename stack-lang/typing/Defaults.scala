package typing

import ast.{ Trees => Ast }
import ast.Positions.*

import sast.*
import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import reporting.Reporter

import scala.collection.mutable

object Defaults:
  /** Eagerly validate post-parameter section shape (syntax-only):
    *  - defaults must form a trailing suffix (no non-default after a defaulted param)
    */
  def checkDefaultSuffix(postParams: List[Ast.Param])
      (using rp: Reporter, so: Source)
  : Unit =
    var seenDefault = false
    for param <- postParams do
      if param.default.isDefined then
        seenDefault = true
      else if seenDefault then
        Reporter.error(
          s"Parameter '${param.name}' must have a default value because a preceding parameter has one",
          param.span.toPos
        )

  /** Synthesize SAST words for default arguments missing from a call.
    *
    * @param procType   the proc type of the callee
    * @param numPostProvided  number of post-arguments actually provided at the call site
    * @param span       source span used for the synthesized nodes
    * @return list of synthesized default words for the missing trailing post-params
    */
  def synthesizePostDefaults(procType: ProcType, numPostProvided: Int, span: Span)
      (using defn: Definitions)
  : List[Word] =
    val numNeeded = procType.postParamCount - numPostProvided
    if numNeeded <= 0 then return Nil

    val defaultsNeeded = procType.defaults.takeRight(numNeeded)
    val paramTypesNeeded = procType.postParamTypes.takeRight(numNeeded)
    defaultsNeeded.zip(paramTypesNeeded).map:
      case (DefaultValue.Lit(const), tpe) => Literal(const)(tpe, span)
      case (DefaultValue.Ref(sym), _) =>
        if sym.tpe.isValueType then
          Ident(sym)(span)
        else
          // Parameterless, auto-free proc – call it
          Apply(Ident(sym)(span), Nil, Nil)(span)

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Type-check a single default value expression against the declared param type. */
  def transformDefault(default: Ast.Word, paramType: Type, namer: Namer)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : Option[Symbol | Constant] =
    if paramType.isVararg then
      Reporter.error("Vararg parameter cannot have a default value", param.span.toPos)
      return None

    default match
      case lit: Ast.IntLit =>
        val lit2 = NumericTyper.typeIntLit(lit)(using Inference.TargetType.Known(paramType), defn, rp, so)
        if checkConformsLit(lit2, paramType) then lit2.constant else None

      case lit: Ast.FloatLit =>
        val lit2 = NumericTyper.typeFloatLit(lit)(using defn, rp, so)
        if checkConformsLit(lit2, paramType) then lit2.constant else None

      case lit: Ast.BoolLit =>
        val lit2 = Literal(Constant.Bool(lit.value))(defn.BoolType, lit.span)
        if checkConformsLit(lit2, paramType) then lit2.constant else None

      case lit: Ast.CharLit =>
        val lit2 = NumericTyper.typeCharLit(lit)(using Inference.TargetType.Known(paramType), defn, rp, so)
        if checkConformsLit(lit2, paramType) then lit2.constant else None

      case lit: Ast.StringLit =>
        val lit2 = Literal(Constant.String(lit.value))(defn.StringType, lit.span)
        if checkConformsLit(lit2, paramType) then lit2.constant else None

      case ref: Ast.RefTree =>
        namer.resolveQualid(ref, SymbolKind.Term) match
          case Some(sym) =>
            val id = Ident(sym)(ref.span)
            if checkRefDefault(id, paramType) then sym else None

          case None => None   // resolveQualid already reported the error

      case _ =>
        Reporter.error("Default value must be a literal or a qualified identifier", default.span.toPos)
        None

  /** Verify that the literal type conforms to the expected parameter type. */
  private
  def checkConformsLit(lit: Literal, paramType: Type)
      (using defn: Definitions, rp: Reporter, so: Source)
  : Boolean =
    if !Subtyping.conforms(lit.tpe, paramType) then
      Reporter.error(
        s"Default value type ${lit.tpe.show} does not conform to parameter type ${paramType.show}",
        lit.span.toPos
      )
      false
    else
      true

  /** Verify that a symbol reference is a valid default: a value or a parameterless,
    * non-polymorphic, auto-free function whose result type conforms to the param type.
    */
  private
  def checkRefDefault(id: Ident, paramType: Type)
      (using defn: Definitions, rp: Reporter, so: Source)
  : Boolean =
    val sym = id.symbol
    val span = id.span

    if !sym.isTopLevel then
      Reporter.error("Default value must refer to a top-level definition", span.toPos)
      return false

    sym.info match
      case proc: ProcType =>
        if proc.tparams.nonEmpty then
          Reporter.error("Default value cannot refer to a polymorphic function", span.toPos)
          false

        else if proc.params.nonEmpty then
          Reporter.error("Default value cannot refer to a function with parameters", span.toPos)
          false

        else if proc.autos.nonEmpty then
          Reporter.error("Default value cannot refer to a function with auto parameters", span.toPos)
          false

        else
          val resType = proc.resultType
          if Subtyping.conforms(resType, paramType) then
            true
          else
            Reporter.error(
              s"Default value type ${resType.show} does not conform to parameter type ${paramType.show}",
              span.toPos
            )
            false

      case _ =>
        Reporter.error("Default value must be a value or a parameterless function", span.toPos)
        false
