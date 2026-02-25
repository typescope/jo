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
  def validatePostDefaultShape(postParams: List[Ast.Param])
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

  /** Lazily type-check default values for post-parameters that carry defaults.
    * Returns a list of DefaultValues corresponding to the trailing defaulted params.
    * Returns Nil if any error is encountered.
    */
  def checkPostDefaults(
      postParams: List[Ast.Param],
      postParamSyms: List[Symbol],
      namer: Namer
  )(using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : List[DefaultValue] =
    val results = new mutable.ArrayBuffer[DefaultValue]
    var hasError = false

    for (param, sym) <- postParams.zip(postParamSyms) do
      param.default match
        case None => // no default for this param
        case Some(default) =>
          val paramType = sym.info
          if paramType.isVararg then
            Reporter.error("Vararg parameter cannot have a default value", param.span.toPos)
            hasError = true
          else
            checkDefaultValue(default, paramType, namer) match
              case Some(dv) => results += dv
              case None     => hasError = true

    if hasError then Nil else results.toList

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
        if sym.info.isValueType then
          Ident(sym)(span)
        else
          // Parameterless, auto-free proc – call it
          Apply(Ident(sym)(span), Nil, Nil)(span)

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Type-check a single default value expression against the declared param type. */
  private def checkDefaultValue(
      default: Ast.Word,
      paramType: Type,
      namer: Namer
  )(using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : Option[DefaultValue] =
    default match
      case lit: Ast.IntLit =>
        val sasLit = NumericTyper.typeIntLit(lit)(using Inference.TargetType.Known(paramType), defn, rp, so)
        checkConformsLit(sasLit, paramType)

      case lit: Ast.FloatLit =>
        val sasLit = NumericTyper.typeFloatLit(lit)(using defn, rp, so)
        checkConformsLit(sasLit, paramType)

      case lit: Ast.BoolLit =>
        val sasLit = Literal(Constant.Bool(lit.value))(defn.BoolType, lit.span)
        checkConformsLit(sasLit, paramType)

      case lit: Ast.CharLit =>
        val sasLit = NumericTyper.typeCharLit(lit)(using Inference.TargetType.Known(paramType), defn, rp, so)
        checkConformsLit(sasLit, paramType)

      case lit: Ast.StringLit =>
        val sasLit = Literal(Constant.String(lit.value))(defn.StringType, lit.span)
        checkConformsLit(sasLit, paramType)

      case ref: Ast.RefTree =>
        namer.resolveQualid(ref, Universe.Term) match
          case Some(sym) => checkRefDefault(sym, paramType, ref.span)
          case None      => None   // resolveQualid already reported the error

      case _ =>
        Reporter.error("Default value must be a literal or a qualified identifier", default.span.toPos)
        None

  /** Verify that the literal type conforms to the expected parameter type. */
  private def checkConformsLit(lit: Literal, paramType: Type)
      (using defn: Definitions, rp: Reporter, so: Source)
  : Option[DefaultValue] =
    if !Subtyping.conforms(lit.tpe, paramType) then
      Reporter.error(
        s"Default value type ${lit.tpe.show} does not conform to parameter type ${paramType.show}",
        lit.span.toPos
      )
      None
    else
      Some(DefaultValue.Lit(lit.constant))

  /** Verify that a symbol reference is a valid default: a value or a parameterless,
    * non-polymorphic, auto-free function whose result type conforms to the param type.
    */
  private def checkRefDefault(sym: Symbol, paramType: Type, span: Span)
      (using defn: Definitions, rp: Reporter, so: Source)
  : Option[DefaultValue] =
    if !sym.isTopLevel then
      Reporter.error("Default value must refer to a top-level definition", span.toPos)
      return None

    val resultTypeOpt: Option[Type] =
      if sym.info.isValueType then
        Some(sym.info)
      else
        sym.info match
          case proc: ProcType =>
            if proc.tparams.nonEmpty then
              Reporter.error("Default value cannot refer to a polymorphic function", span.toPos)
              None
            else if proc.params.nonEmpty then
              Reporter.error("Default value cannot refer to a function with parameters", span.toPos)
              None
            else if proc.autos.nonEmpty then
              Reporter.error("Default value cannot refer to a function with auto parameters", span.toPos)
              None
            else
              Some(proc.resultType)
          case _ =>
            Reporter.error("Default value must be a value or a parameterless function", span.toPos)
            None

    resultTypeOpt.flatMap: resType =>
      if !Subtyping.conforms(resType, paramType) then
        Reporter.error(
          s"Default value type ${resType.show} does not conform to parameter type ${paramType.show}",
          span.toPos
        )
        None
      else
        Some(DefaultValue.Ref(sym))
