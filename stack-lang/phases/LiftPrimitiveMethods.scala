package phases

import sast.*
import sast.Trees.*
import sast.Symbols.Symbol
import sast.Symbols.*
import sast.Types.*
import sast.Flags

import Phase.PhaseKey

import scala.collection.mutable

/** This phase handles several aspects of primitives
  *
  * 1. Lifts non-intrinsic methods of primitive classes (Int, Byte, Char, Float,
  * Bool, String) to top-level functions and rewrites all call sites.
  *
  * 2. Rewire methods on Array to backend support methods
  *
  * This runs as a common frontend phase so that all backends see the lifted functions
  * instead of Select calls on primitive types for those methods.
  *
  * Primitive classes are identified by the `@intrinsic` class annotation.
  * Intrinsic methods (those also annotated with `@intrinsic`) are left in place —
  * backends handle them via their own primitive dispatch.
  */
class LiftPrimitiveMethods(arrayOpsSection: String)(using defn: Definitions) extends Phase:
  private val ArrayOps = defn.resolveContainer(arrayOpsSection)

  private val liftedSymMapKey: PhaseKey[mutable.Map[Symbol, Symbol]] = new PhaseKey("liftedSymMap")

  override def initContext()(using Context): Unit =
    liftedSymMapKey.set(mutable.Map.empty)

  def getLiftedSymbol(methodSym: Symbol)(using Context): Symbol =
    val map = liftedSymMapKey.value
    map.get(methodSym) match
      case Some(sym) => sym
      case None =>
        val classSym = methodSym.owner
        val oldProcType = methodSym.tpe.asProcType
        val classInfo = classSym.classInfo
        val thisInfo = classInfo.self.tpe

        assert(classInfo.tparams.size < 2, "Expect class to have at most 1 type parameter: " + classSym)
        assert(oldProcType.tparams.isEmpty, "Expect method to have no type parameter: " + methodSym)

        val funType = ProcType(
          tparams = classInfo.tparams,
          params = NamedInfo("this", thisInfo) :: oldProcType.params,
          autos = oldProcType.autos,
          candidates = oldProcType.candidates,
          resultType = oldProcType.resultType,
          receivesInfo = oldProcType.receivesInfo,
          preParamCount = 1,
          preTypeParamCount = classInfo.tparams.size
        )(oldProcType.defaultsLazy)

        val liftedSym = TermSymbol.create(
          classSym.name + "$" + methodSym.name,
          funType,
          Flags.Fun | Flags.Synthetic,
          Visibility.Default,
          classSym.owner,
          methodSym.sourcePos
        )

        map(methodSym) = liftedSym
        liftedSym

  override def transformFileUnit(unit: FileUnit)(using Context): FileUnit =
    val newDefs = mutable.ArrayBuffer.empty[Def]
    for d <- unit.defs do
      d match
        case cdef: ClassDef if cdef.symbol.hasAnnotation(defn.intrinsic) =>
          val intrinsicFuns = cdef.funs.filter(f => f.symbol.hasAnnotation(defn.intrinsic))
          val liftableFuns  = cdef.funs.filter(f => !f.symbol.hasAnnotation(defn.intrinsic))

          newDefs += cdef.copy(funs = intrinsicFuns)(cdef.annots, cdef.span)

          val self = cdef.self
          for fdef <- liftableFuns do
            val liftedSym = getLiftedSymbol(fdef.symbol)
            Phase.owner.set(liftedSym)
            val body2 = this.transform(fdef.body)
            newDefs += FunDef(
              liftedSym, fdef.tparams,
              self :: fdef.params,
              fdef.autos, fdef.candidates,
              fdef.resultType, fdef.effectPolicy,
              body2
            )(fdef.annots, fdef.span)

        case other =>
          newDefs += transformDef(other)

    FileUnit(unit.owner, unit.imports, newDefs.toList, unit.source)

  private def getTypeArgument(tpe: Type, cls: Symbol): Type =
    tpe.approx match
      case appType @ AppliedType(`cls`, tps) =>
        assert(cls == defn.Array_class && tps.size == 1, "Unexpected receiver type " + appType.show)
        tps.head

      case tp =>
        throw new Exception("Unexpected receiver type " + tp.show)

  override def transformApply(apply: Apply)(using ctx: Context): Word =
    val Apply(fun, args, autos) = apply

    fun match
      case Select(qual, name) =>
        // None-of the methods for primitives we are interested are polymorphic,
        // thus Select node covers all of them.

        val methodSym = fun.tpe.as[MemberRef].symbol

        if methodSym.owner == defn.Array_class && methodSym.hasAnnotation(defn.intrinsic) then
          val qual2 = transform(qual)
          val args2 = for arg <- args yield transform(arg)
          val argsAll = qual2 :: args2

          val elemType = getTypeArgument(qual.tpe, methodSym.owner)
          Ident(ArrayOps.termMember(name))(fun.span).appliedToTypes(elemType).appliedTo(argsAll*)
        else

          val needRewrite =
            methodSym.owner.hasAnnotation(defn.intrinsic) && !methodSym.hasAnnotation(defn.intrinsic)

          if needRewrite then
            val liftedSym = getLiftedSymbol(methodSym)
            val qual2  = this.transform(qual)
            val args2  = args.map(this.transform)
            val autos2 = autos.map(this.transform)
            val funRef = Ident(liftedSym)(fun.span)

            val fun2 =
              if liftedSym.tpe.isPolyType then
                // See the invariant in getLiftedSymbol: only class may have one
                // type parameter, method may not have type parameters.
                funRef.appliedToTypes(getTypeArgument(qual2.tpe, methodSym.owner))
              else
                funRef

            Apply(fun2, qual2 :: args2, autos2)(apply.span)

          else
            super.transformApply(apply)

      case _ =>
        super.transformApply(apply)
