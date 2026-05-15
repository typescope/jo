package phases

import sast.*
import sast.Trees.*
import sast.Symbols.Symbol
import sast.Symbols.*
import sast.Types.*
import sast.Flags

import Phase.PhaseKey

import scala.collection.mutable

/** Lifts non-intrinsic methods of primitive classes (Int, Byte, Char, Float, Bool, String)
  * to top-level functions and rewrites all call sites.
  *
  * This runs as a common frontend phase so that all backends see the lifted functions
  * instead of Select calls on primitive types for those methods.
  *
  * Primitive classes are identified by the `@intrinsic` class annotation.
  * Intrinsic methods (those also annotated with `@intrinsic`) are left in place —
  * backends handle them via their own primitive dispatch.
  */
class LiftPrimitiveMethods(using defn: Definitions) extends Phase:
  private val liftedSymMapKey: PhaseKey[mutable.Map[Symbol, Symbol]] = new PhaseKey("liftedSymMap")

  override def initContext()(using Context): Unit =
    liftedSymMapKey.set(mutable.Map.empty)

  def getLiftedSymbol(methodSym: Symbol)(using Context): Symbol =
    liftedSymMapKey.value.get(methodSym) match
      case Some(sym) => sym
      case None =>
        val classSym = methodSym.owner
        val oldProcType = methodSym.tpe.asProcType
        val thisInfo = classSym.classInfo.self.tpe
        val funType = oldProcType.prepend(NamedInfo("this", thisInfo) :: Nil)
        TermSymbol.create(
          classSym.name + "$" + methodSym.name,
          funType,
          Flags.Fun | Flags.Synthetic,
          Visibility.Default,
          classSym.owner,
          methodSym.sourcePos
        )

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

  override def transformApply(apply: Apply)(using ctx: Context): Word =
    val Apply(fun, args, autos) = apply

    fun match
      case Select(qual, _) =>
        val methodSym = fun.tpe.as[MemberRef].symbol

        val needRewrite =
          methodSym.owner.hasAnnotation(defn.intrinsic) && !methodSym.hasAnnotation(defn.intrinsic)

        if needRewrite then
          val liftedSym = getLiftedSymbol(methodSym)
          val qual2  = this.transform(qual)
          val args2  = args.map(this.transform)
          val autos2 = autos.map(this.transform)
          Apply(Ident(liftedSym)(fun.span), qual2 :: args2, autos2)(apply.span)

        else
          super.transformApply(apply)

      case _ =>
        super.transformApply(apply)
