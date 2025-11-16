package typing

import sast.*
import sast.Adaptation.{ Adapter, NoAdapter }
import sast.Types.*

import reporting.Reporter
import ast.Positions.Source

/** Type inference logic */
object Inference:
  enum TargetType:
    case Unknown
    case ValueType
    case VoidType
    case TypeApply
    case ExprItem
    case Call
    case TermMember(name: String)
    case TypeMember(name: String)
    case Known(tpe: Type, adapter: Adapter = NoAdapter)

    def knownType: Option[Type] =
      this match
        case Known(tpe, _) => Some(tpe)
        case _ => None

    def show(using Definitions): String =
      this match
        case Known(tpe: Type, _) => "Known(" + tpe.show + ")"
        case _ => this.toString()

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
    * - The original function type was polymorphic (has type parameters to infer)
    *
    * For monomorphic functions, context instantiation serves no purpose (no type
    * parameters to infer), so we skip it to allow parameter adaptation to work.
    * Even though the monomorphic function might contain uninitialized type
    * parameters, it is safe to prefer inner constraints.
    *
    * @param resultType The type to constrain
    * @param targetType The target/expected type context
    */
  def conditionalInstantiate(resultType: Type, targetType: TargetType, isPolyFun: Boolean)(using Definitions): Unit =
    if isPolyFun then
      targetType match
        case TargetType.Known(expectedType, NoAdapter) =>
          assert(expectedType.isFullyInstantiated, "not fully instantiated: " + expectedType.show)
          // No adapter at call site and function is polymorphic
          // Safe to apply context instantiation to help infer type parameters
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
        case TargetType.Known(tp, _) =>
          if Subtyping.conforms(tp1, tp) && Subtyping.conforms(tp2, tp) then
            Some(tp)
          else
            None

        case _ =>
          if tp1.isTagType && tp2.isTagType then
            Some(UnionType(tp1 :: tp2 :: Nil))

          else
            None

  /** Create a fresh context for type inference
    *
    * Only call this method if it is certain that it is impossible for type vars
    * of new the isolate to interact with uninitialized type vars of existing
    * isolates.
    */
  def freshIsolate[T](op: TypeVars ?=> T)(using Source, Reporter): T =
    given tvars: TypeVars = new UnificationSolver
    val res = op
    Checker.checkInstantiated(tvars)
    res
