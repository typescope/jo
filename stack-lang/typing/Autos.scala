package typing

import sast.*
import sast.Sast.*
import sast.Types.*
import sast.Symbols.*

import ast.Positions.*
import reporting.Reporter

class Autos(namer: Namer):
  def derive
      (procType: ProcType, span: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : List[Word] =
    for NamedInfo(name, autoInfo) <- procType.autos yield
      search(autoInfo, Vector.empty, sc, span)

  private def search(target: Type, trace: Vector[Symbol], sc: Scope, span: Span)(using Definitions, Reporter, Source): Word =
    val candidates = sc.getAutos.flatMap: sym =>
      val base = Ident(sym)(span)
      val tp = sym.info
      if tp.isProcType then
        var procType = tp.asProcType

        val fun =
          if procType.tparams.nonEmpty then
            val tapply = namer.instantiatePoly(procType, base)
            procType = tapply.tpe.asProcType
            tapply
          else
            base

        if Subtyping.conforms(procType.resultType, target) then
          (sym, fun) :: Nil

        else
          Nil

      else
        if Subtyping.conforms(tp, target) then (sym, base) :: Nil else Nil


    def history: String =
      if trace.isEmpty then ""
      else " Resolution trace: " + trace.map(_.name).mkString(" -> ")

    candidates match
      case Nil =>
        sc match
          case _: Scope.RootScope =>
            val tpText = target.show
            Reporter.error(s"No autos are found for the target type $tpText." + history, span.toPos)
            Block(Nil)(ErrorType, span)

          case Scope.NestedScope(outer, _, _) => search(target, trace, outer, span)

          case Scope.LocalPatternScope(outer, _, _) => search(target, trace, outer, span)

      case (sym, base) :: Nil if trace.contains(sym) =>
        val tpText = target.show
        val loop = (trace :+ sym).map(_.fullName).mkString(" -> ")
        Reporter.error(s"Divergence in resolving auto of the type $tpText: " + loop + ".", span.toPos)
        Block(Nil)(ErrorType, span)

      case (sym, base) :: Nil =>
        if base.tpe.isProcType then
          val procType = base.tpe.asProcType
          if procType.params.nonEmpty then
            Reporter.error(s"The auto ${sym.fullName} require non-auto params." + history, span.toPos)
            Block(Nil)(ErrorType, span)

          else if procType.autos.isEmpty then
            base.appliedTo()

          else
            val autos =
              for NamedInfo(name, autoInfo) <- procType.autos yield
                search(autoInfo, trace :+ sym, sc, base.span)

            Apply(base, Nil, autos)(procType.resultType, span)
        else
          base

      case _ =>
        val tpText = target.show
        val names  = candidates.map(_._1.fullName).mkString(", ")
        Reporter.error(s"Ambiguous autos, multiple candidates satisfy the target type $tpText: " + names + "." + history, span.toPos)
        Block(Nil)(ErrorType, span)
