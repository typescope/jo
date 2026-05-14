package phases

import sast.*
import sast.Trees.*
import sast.Symbols.Symbol
import sast.Symbols.*
import sast.Types.*
import sast.Flags

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

  private val liftedSymMap = mutable.Map.empty[Symbol, Symbol]

  private def isPrimitiveClassSym(sym: Symbol): Boolean =
    sym == defn.Int_type || sym == defn.Byte_type || sym == defn.Char_type ||
    sym == defn.Float_type || sym == defn.Bool_type || sym == defn.String_type

  private def isPrimitiveType(tpe: Type): Boolean =
    tpe.isSubtype(defn.IntType) || tpe.isSubtype(defn.ByteType) ||
    tpe.isSubtype(defn.CharType) || tpe.isSubtype(defn.FloatType) ||
    tpe.isSubtype(defn.BoolType) || tpe.isSubtype(defn.StringType)

  // A method is intrinsic if it is annotated with @intrinsic.
  private def isIntrinsic(fdef: FunDef): Boolean =
    fdef.symbol.hasAnnotation(defn.intrinsic)

  private def createLiftedSymbol(classSym: Symbol, fdef: FunDef): Symbol =
    val methodSym = fdef.symbol
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

  override def transform(units: List[FileUnit]): List[FileUnit] =
    // First pass: collect liftable (non-intrinsic) methods from primitive class defs
    for unit <- units do
      for d <- unit.defs do
        d match
          case cdef: ClassDef if isPrimitiveClassSym(cdef.symbol) =>
            for fdef <- cdef.funs if !isIntrinsic(fdef) do
              liftedSymMap(fdef.symbol) = createLiftedSymbol(cdef.symbol, fdef)
          case _ =>

    given ctx: Context = new Phase.Context
    for unit <- units yield
      Phase.source.set(unit.source)
      Phase.owner.set(unit.owner)
      transformPrimitiveFileUnit(unit)

  private def transformPrimitiveFileUnit(unit: FileUnit)(using Context): FileUnit =
    val newDefs = mutable.ArrayBuffer.empty[Def]
    for d <- unit.defs do
      d match
        case cdef: ClassDef if isPrimitiveClassSym(cdef.symbol) =>
          val intrinsicFuns = cdef.funs.filter(f => isIntrinsic(f))
          val liftableFuns  = cdef.funs.filter(f => !isIntrinsic(f))

          newDefs += cdef.copy(funs = intrinsicFuns)(cdef.annots, cdef.span)

          val self = cdef.self
          for fdef <- liftableFuns do
            val liftedSym = liftedSymMap(fdef.symbol)
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
      case Select(qual, _) if isPrimitiveType(qual.tpe) =>
        fun.tpe match
          case MemberRef(_, methodSym) =>
            liftedSymMap.get(methodSym) match
              case Some(liftedSym) =>
                val qual2  = this.transform(qual)
                val args2  = args.map(this.transform)
                val autos2 = autos.map(this.transform)
                Apply(Ident(liftedSym)(fun.span), qual2 :: args2, autos2)(apply.span)
              case None =>
                super.transformApply(apply)
          case _ =>
            super.transformApply(apply)
      case _ =>
        super.transformApply(apply)
