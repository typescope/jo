import Types.*

import scala.collection.mutable

/** Type inference logic */
object Inference:
  enum TargetType:
    case Unknown
    case ValueType
    case ProperType // value type or void
    case Member(name: String)
    case Known(tpe: Type)

    def knownType: Option[Type] =
      this match
        case Known(tpe) => Some(tpe)
        case _ => None

  enum SubtypingResult:
    case Success

    case Fail

    /** A conditional result with the given subtyping obligations.
      *
      * If the obligations are satisfied, execute the associated action once.
      */
    case Conditional(obligations: List[Subtyping.Task], action: () => Unit)

  trait InferEngine:
    def newTypeVars(tparams: List[NamedInfo[TypeBound]]): List[TypeVar]

    /** Dealias the type without approximation.
      *
      * The method should not recursively call `TypeOps.dealias`.
      */
    def dealias(tvar: TypeVar): Type

    /** Approximate the type of the tvar to its bounds
      *
      * The method shoud not recursively call `TypeOps.approx`.
      */
    def approx(tvar: TypeVar, isUp: Boolean): Type

    def isSubtype(tvar: TypeVar, tp: Type): SubtypingResult

    def isSuptype(tvar: TypeVar, tp: Type): SubtypingResult

  def boundCheckTasks(tp: Type, bound: TypeBound): List[Subtyping.Task] =
    Subtyping.Task(tp, bound.hi) :: Subtyping.Task(bound.lo, tp) :: Nil

  class UnificationSolver extends InferEngine:
    private val instantiations: mutable.Map[TypeVar, Type] = mutable.Map.empty
    private val bounds: mutable.Map[TypeVar, TypeBound] = mutable.Map.empty

    // TODO: ensure instantiation of unconstrained type variables

    def newTypeVars(tparams: List[NamedInfo[TypeBound]]): List[TypeVar] =
      val tvars = for tparam <- tparams yield TypeVar(tparam.name, this)

      for (tvar, tparam) <- tvars.zip(tparams) do
        bounds(tvar) = TypeOps.substTypeParams(tparam.info, tvars).as[TypeBound]

      tvars

    def dealias(tvar: TypeVar): Type =
      instantiations.get(tvar) match
        case Some(inst) => inst
        case None => tvar

    def approx(tvar: TypeVar, isUp: Boolean): Type =
      instantiations.get(tvar) match
        case Some(inst) => TypeOps.approx(inst, isUp)
        case None =>
          val bound = bounds(tvar)
          if isUp then bound.hi else bound.lo

    def isSubtype(tvar: TypeVar, tp: Type): SubtypingResult =
      instantiations.get(tvar) match
        case Some(inst) =>
          SubtypingResult.Conditional(
            Subtyping.Task(inst, tp) :: Nil,
            action = () => ()
          )

        case None =>
          if tvar == tp then
            SubtypingResult.Success
          else
            val tasks = boundCheckTasks(tp, bounds(tvar))
            val action = () => instantiations(tvar) = tp
            SubtypingResult.Conditional(
              tasks,
              action
            )


    def isSuptype(tvar: TypeVar, tp: Type): SubtypingResult =
      instantiations.get(tvar) match
        case Some(inst) =>
          SubtypingResult.Conditional(
            Subtyping.Task(tp, inst) :: Nil,
            action = () => ()
          )

        case None =>
          if tvar == tp then
            SubtypingResult.Success
          else
            val tasks = boundCheckTasks(tp, bounds(tvar))
            val action = () => instantiations(tvar) = tp
            SubtypingResult.Conditional(
              tasks,
              action
            )
