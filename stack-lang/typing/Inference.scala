package typing

import sast.TypeOps
import sast.Adaptation.{ Adapter, NoAdapter }
import sast.Types.*
import sast.Subtyping
import sast.Definitions

import reporting.Reporter
import ast.Positions.Source

/** Type inference logic */
object Inference:
  enum TargetType:
    case Unknown
    case ValueType
    case VoidType
    case TypeApply
    case Fun(args: Int)
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
    * @param originalType The original function type before type parameter instantiation
    */
  def conditionalInstantiate(resultType: Type, targetType: TargetType, originalType: ProcType)(using Definitions): Unit =
    targetType match
      case TargetType.Known(expectedType, NoAdapter) if originalType.isPolyType =>
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

  def freshInferContext[T](op: TypeVars ?=> T)(using Source, Reporter): T =
    given tvars: TypeVars = new UnificationSolver
    val res = op
    checker.checkInstantiated(tvars)
    res

  class UnificationSolver extends TypeVars:
    private var tvars = new mutable.ArrayBuffer[TypeVar]
    private var instantiations: Map[TypeVar, Type] = Map.empty

    private def instantiate(tvar: TypeVar, tp: Type)(using Definitions) =
      assert(!instantiations.contains(tvar), "double instantiation: " + tvar)
      // println("Instantiating " + tvar + " to " + tp)
      // println("tvar.hashCode = " + System.identityHashCode(tvar))
      // println("tp.hashCode = " + System.identityHashCode(tp))

      // We do not
      //
      // - substitute occurrence in existing substitutions
      // - check that tvar does not occur in tp
      //
      // They are handled by subtype checking implicitly.
      if TypeOps.dealias(tp) != tvar then
        instantiations = instantiations.updated(tvar, tp)

    private def constrain(tvar: TypeVar, tp: Type, tvarLeft: Boolean)(using Definitions): List[Subtyping.Task] =
      instantiations.get(tvar) match
        case Some(inst) =>
          if tvarLeft then Subtyping.Task(inst, tp) :: Nil
          else Subtyping.Task(tp, inst) :: Nil

        case None =>
          assert(tvar != tp)
          instantiate(tvar, tp)
          Nil

    def add(tvar: TypeVar): Unit =
      tvars += tvar

    def typeVars: List[TypeVar] = tvars.toList

    def isInstantiated(tvar: TypeVar): Boolean =
      instantiations.get(tvar).nonEmpty

    def instantiated(tvar: TypeVar): Type =
      instantiations.get(tvar) match
        case Some(inst) => inst
        case None => throw new Exception("Not instantiated: " + tvar)

    def approx(tvar: TypeVar, isUp: Boolean): Type =
      instantiations.get(tvar) match
        case Some(inst) => inst

        case None =>
          tvar

    def isSubtype(tvar: TypeVar, tp: Type)(using Definitions): List[Subtyping.Task] =
      constrain(tvar, tp, tvarLeft = true)

    def isSuptype(tvar: TypeVar, tp: Type)(using Definitions): List[Subtyping.Task] =
      constrain(tvar, tp, tvarLeft = false)

    def test[T](op: => T): T =
      val stateBefore = instantiations
      val res = op
      instantiations = stateBefore
      res
