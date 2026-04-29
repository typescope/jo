package sast

import Types.*
import Symbols.*
import Denotations.*

import ast.Positions.Span

import common.Debug

import scala.collection.mutable

/** Operations on types */
object TypeOps:
  /** Substitute type symbols with the supplied types.
    *
    * This method is used in type checking definitions with type parameters.
    */
  def substSymbols(tpe: Type, tparams: List[Symbol], targs: List[Type])(using defn: Definitions): Type =
    Debug.trace(s"substSymbols(${tpe.show}), targs = " + targs, (_: Type).show, enable = false):
      defn.cache.substitute(tpe, targs):
        val subst = tparams.zip(targs).toMap
        substSymbols(tpe, subst)

  def substSymbols(tpe: Type, subst: Map[Symbol, Type])(using Definitions): Type =
    val typeMap = new TypeOps.SymbolsTypeMap
    typeMap(tpe)(using subst)

  /** Rebase a class/interface member type against a prefix
    *
    * Type parameters of the class/interface are substituted
    */
  def rebaseMember(memberType: Type, prefix: Type)(using Definitions): Type =
    // compute the type with respect to the instantiated targs
    prefix.widen.dealias match
      case AppliedType(cls, targs) =>
        TypeOps.substSymbols(memberType, cls.classInfo.tparams, targs)

      case _ =>
        memberType

  /** Approximate top-level type aliases, applied types and type parameters
    *
    * The difference with `dealias` is that this method widens term references
    * while `dealias` does not.
    */
  def approx(tp: Type)(using Definitions): Type = Debug.trace(s"${tp.show}.approx", enable = false):
    tp match
      case ref @ StaticRef(sym) =>
        if sym.isGroundType then ref
        else if sym.info.isType then approx(sym.tpe) else ref

      case ref: MemberRef => approx(ref.info)

      case DuckType(baseType) =>
        approx(baseType)

      case ExtensionType(baseType) =>
        approx(baseType)

      case AnnotType(base, _) =>
        approx(base)

      case tvar: TypeVar =>
        if !tvar.isInstantiated then
          tvar
        else
          approx(tvar.instantiated)

      case AppliedType(tctor, targs) =>
        if tctor.isGroundType then tp
        else
          tctor.info match
            case toi: TypeOperatorInfo =>
              approx(toi.instantiate(targs))

            case _: ClassInfo => tp

            case tp =>
              throw new Exception("Type constructor have type " + tp)

      case constType: ConstantType => approx(constType.underlying)

      case tp => tp

  /** Widen a term reference or constant type to its underlying type */
  def widen(tp: Type)(using Definitions): Type =
    tp match
      case refType: RefType if refType.symbol.isTerm || refType.symbol.isPattern => refType.info
      case constType: ConstantType => constType.underlying
      case _ => tp

  /** Check whether a type definition contains aliasing cycles */
  def hasCyclesInType(symbol: Symbol, info: Type)(using Definitions): Boolean =
    // detect cycles in symbol definitions, e.g., type A = A
    val encountered = new mutable.ArrayBuffer[Symbol]
    encountered += symbol
    var hasCycle = false

    def recur(tp: Type): Unit = Debug.trace(s"$tp.hascycles", enable = false):
      tp match
        case StaticRef(sym) =>
          if sym.isType then
            if encountered.contains(sym) then
              hasCycle = true
            else
              if !sym.isGroundType then
                encountered += sym
                recur(sym.tpe)
            end if

        case DuckType(base) =>
          recur(base)

        case ExtensionType(base) =>
          recur(base)

        case AnnotType(base, _) =>
          recur(base)

        case AppliedType(tctor, targs) =>
          if encountered.contains(tctor) then
            hasCycle = true
          else
            tctor.info match
              case toi: TypeOperatorInfo =>
                recur(toi.instantiate(targs))

              case _: ClassInfo =>

              case tp =>
                throw new Exception(s"Type constructor $tctor have type " + tp)
            end match

        case tp =>
    end recur
    recur(info)
    hasCycle

  /** Normalize the type
    *
    * - Strip instantiated tvars from the type
    *
    * It is used in performance optimization thus it is best effort and needs to
    * be fast.
    */
  def normalize(tp: Type)(using Definitions): Type =
    tp match
      case tvar: TypeVar =>
        if tvar.isInstantiated then tvar.instantiated else tvar

      case AppliedType(tctor, args) if args.exists(_.is[TypeVar]) =>
        AppliedType(tctor, args.map(normalize))

      case _ => tp

  /** Transitively eliminate top-level type aliases and applied types without
    * any approximation.
    *
    * Dealiasing only ensures that the two types are equivalent in terms of
    * subtyping, but not for member selection!
    *
    * Duck types and extension types are dealiased to their base types.
    *
    * In particular,
    *
    * - type parameters are not reduced to their bounds and
    * - no widening for term references and constant types
    */
  def dealias(tp: Type)(using Definitions): Type =
    def recur(tp: Type): Type = Debug.trace(s"$tp.dealias", enable = false):
      tp match
        case tref @ StaticRef(sym) =>
          if sym.isGroundType || !sym.isType then
            tref

          else
            sym.info match
              case tp: Type => recur(tp)
              case _ => tref

        case DuckType(baseType) => recur(baseType)

        case ExtensionType(base) =>
          recur(base)

        case AnnotType(base, _) => recur(base)

        case tvar: TypeVar =>
          if !tvar.isInstantiated then
            tvar
          else
            recur(tvar.instantiated)

        case app @ AppliedType(tctor, targs) =>
          tctor.info match
            case toi: TypeOperatorInfo =>
              if tctor.is(Flags.Defer) then app
              else recur(toi.instantiate(targs))

            case _: ClassInfo => app

            case tp =>
              throw new Exception("Type constructor have type " + tp)

        case tp => tp
    end recur
    recur(tp)
  end dealias

  /** A grounded type cannot be simplied further at the top-level with dealias
    *
    * The following types are not grounded:
    *
    * - type aliases
    * - instaniated type variables
    */
  def isGrounded(tp: Type): Boolean = Debug.trace(s"Is grouned ${tp}", enable = false):
    tp match
      case StaticRef(sym) =>
        (!sym.isType && !sym.isAlias) || sym.isGroundType

      case AppliedType(sym, _) => sym.isGroundType

      case tvar: TypeVar => !tvar.isInstantiated

      case _: DuckType | _: ExtensionType | _: AnnotType => false

      case _ => true

  /** If the procType is an extension method type, instantiate the receiver; otherwise return itself */
  def instantiateExtensionReceiver(procType: ProcType, receiverType: Type)(using Definitions): ProcType =
    if procType.preParamCount == 0 then
      procType

    else if procType.preTypeParamCount > 0 then
      val solver = new UnificationSolver
      given TypeVars = solver
      val tvars = procType.preTparams.map(tparam => TypeVar(tparam.name, null))
      val procType2 = procType.instantiatePreTypeParams(tvars)
      val preParamType = procType2.preParamTypes.head
      assert(
        Subtyping.conforms(receiverType, preParamType) && tvars.forall(solver.isInstantiated),
        s"extension header type params not fully inferred: ${procType.preTparams.map(_.name)}"
      )
      procType2.postProcType

    else
      procType.postProcType

  /**
    * Warning: If impredicativity is allowed for type parameters, we must
    * perform capture avoidance.
    *
    * Once we enable first-class higher-kinded types, we can have a type:
    *
    *     type C[F: * => *] = [A] => F[A]
    *
    * Now C[C[List]] after dealiasing can have the same symbol A refer to
    * different bindings.
    */
  class SymbolsTypeMap(using Definitions) extends TypeMap:
    type Context = Map[Symbol, Type]

    def apply(tp: Type)(using ctx: Context): Type = Debug.trace(s"map ${tp}", (_: Type).show, enable = false):
      tp match
        case StaticRef(sym) =>
          ctx.getOrElse(sym, tp)

        case _ =>
          recur(tp)

  /** Replace all type parameters with fresh type vars */
  class InstantiateTypeParam(span: Span)(using Definitions, TypeVars) extends TypeMap:
    type Context = Unit

    def apply(tp: Type)(using ctx: Context): Type =
      tp match
        case StaticRef(sym) if sym.isTypeParameter =>
          TypeVar(sym.name, span)

        case _ =>
          recur(tp)

  class FullyInstantiatedChecker extends TypeAccumulator[Boolean](true):
    type Context = Unit

    def combine(acc: Boolean, op: => Boolean): Boolean = acc && op

    def apply(tp: Type)(using Context): Boolean =
      tp match
        case tvar: TypeVar =>
          // We should not need the recursion --- but it can detect broken invariants
          if tvar.isInstantiated then this(tvar.instantiated)
          else false

        case _ =>
          recur(tp)

  class UninstantiatedCensor extends TypeAccumulator[Set[TypeVar]](Set.empty):
    type Context = Unit

    def combine(acc: Set[TypeVar], op: => Set[TypeVar]): Set[TypeVar] = acc ++ op

    def apply(tp: Type)(using Context): Set[TypeVar] =
      tp match
        case tvar: TypeVar =>
          // We should not need the recursion --- but it can detect broken invariants
          if tvar.isInstantiated then this(tvar.instantiated)
          else Set(tvar)

        case _ =>
          recur(tp)
