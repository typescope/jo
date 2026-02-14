package typing

import sast.*
import sast.Types.*
import sast.Symbols.Symbol

import reporting.Reporter
import ast.Positions.Source

/** Type inference logic */
object Inference:

  /** Target type for typing a term */
  enum TargetType:
    case Unknown
    case ValueType
    case VoidType
    case TypeApply
    case ExprItem
    case Call

    /** a term member or container member */
    case Member(name: String)

    /** a fully instantiated type */
    case Known(tpe: Type)

    /** A partially known lambda type for inferring lambda parameter types */
    case LambdaType(params: List[Type], resultType: TargetType, receives: List[Symbol])

    def knownType: Option[Type] =
      this match
        case Known(tpe) => Some(tpe)
        case _ => None

    def show(using Definitions): String =
      this match
        case Known(tpe: Type) => "Known(" + tpe.show + ")"
        case _ => this.toString()

  /** Create a partially known lambda expected type
    *
    * If the type is not a lambda type with known parameter types, return ValueType.
    *
    * It is possible to handle the case only part of the parameter types are
    * known. However, it is unclear such improvement is useful in practice.
    *
    * Reference
    *
    * [1] Colored local type inference, Martin Odersky et al, 2001
    */
  def partiallyKnown(expectType: Type)(using defn: Definitions): TargetType =
    if expectType.isLambdaType then
      val lambdaType = expectType.asLambdaType
      val paramTypesKnown = lambdaType.paramTypes.forall(_.isFullyInstantiated)
      if paramTypesKnown then
        val resultTarget = partiallyKnown(lambdaType.resultType)
        TargetType.LambdaType(lambdaType.paramTypes, resultTarget, lambdaType.receives)

      else
        TargetType.ValueType

    else
      TargetType.ValueType

  /** Conditionally apply context instantiation.
    *
    * Context instantiation constrains the result type using the expected type
    * from the outer context. This helps with type inference but conflicts with
    * parameter adaptation, since both features want to use the expected type
    * information.
    *
    * This method only applies context instantiation when:
    *
    * - There's no adapter function at the call site
    *
    * @param resultType The type to constrain
    * @param targetType The target/expected type context
    */
  def conditionalInstantiate(resultType: Type, targetType: TargetType)(using Definitions): Unit =
    targetType match
      case TargetType.Known(expectedType) if expectedType.adapters.isEmpty =>
        assert(expectedType.isFullyInstantiated, "not fully instantiated: " + expectedType.show)
        // No adapter at call site - safe to apply context instantiation
        Subtyping.conforms(resultType, expectedType)

      case _ =>
        // No known target type, nothing to do

  /** The common result type of two different types.
    *
    * This method is used to compute the result type of if- and match-
    * expressions.
    *
    * The logic is different from computing join in the subtype lattice:
    *
    * - ErrorType always dominates
    * - VoidType dominates anything else
    * - Reference to terms are widened
    *
    * Also, do not infer Any as common type, which is useless.
    */
  def commonResultType(tp1: Type, tp2: Type)
      (using defn: Definitions, tt: TargetType): Option[Type] =

    val tp1Widen = tp1.widenTermRef
    val tp2Widen = tp2.widenTermRef
    if tp1.isError || tp2.isError then Some(ErrorType)
    else if tp1.isVoidType || tp2.isVoidType then Some(VoidType)
    else if Subtyping.conforms(tp1, tp2Widen) then Some(tp2Widen)
    else if Subtyping.conforms(tp2, tp1Widen) then Some(tp1Widen)
    else
      tt match
        case TargetType.Known(tp) =>
          if Subtyping.conforms(tp1, tp) && Subtyping.conforms(tp2, tp) then
            Some(tp)
          else
            None

        case _ =>
          None

  /** Create a fresh context for type inference
    *
    * Only call this method if it is certain that it is impossible for type vars
    * of new the isolate to interact with uninitialized type vars of existing
    * isolates.
    */
  def freshIsolate[T](op: TypeVars ?=> T)(using Source, Reporter, Definitions): T =
    given tvars: TypeVars = new UnificationSolver
    val res = op
    Checker.checkInstantiated(tvars)
    res
