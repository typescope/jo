package typing

import sast.*
import sast.Trees.*
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
      search(autoInfo, Vector.empty, sc, sc, span)

  private def search(target: Type, trace: Vector[Symbol], origin: Scope, sc: Scope, span: Span)(using Definitions, Reporter, Source): Word =
    // println("searching scope owner = " + sc.owner + ", autos = " + sc.autos)

    def history: String =
      if trace.isEmpty then ""
      else " Resolution trace: " + trace.map(_.name).mkString(" -> ")

    // Check that target type is initialized
    if target.exists(tp => tp.is[TypeVar] && !tp.as[TypeVar].isInstantiated) then
      val tpText = target.show
      Reporter.error(s"Not fully instantiated auto type $tpText." + history, span.toPos)
      return Namer.errorWord(span)

    val candidates = sc.autos.flatMap: sym =>
      // println("test " + sym.name + " for " + target.show)
      // testing should not change inference state
      namer.inferencer.test:
        val tp = sym.info
        if tp.isProcType then
          var procType = tp.asProcType

          if procType.tparams.nonEmpty then
            val tvars = for tparam <- procType.tparams yield TypeVar(tparam.name, namer.inferencer)
            procType = procType.instantiate(tvars)

          if Subtyping.conforms(procType.resultType, target) then
            sym :: Nil

          else
            Nil

        else
          if Subtyping.conforms(tp, target) then
            sym :: Nil
          else
            // println(sym.name + " not qualify for " + target.show)
            Nil

    candidates match
      case Nil =>
        sc match
          case _: Scope.RootScope =>
            val tpText = target.show
            Reporter.error(s"No autos are found for the type $tpText." + history, span.toPos)
            Namer.errorWord(span)

          case Scope.NestedScope(outer, _, _) => search(target, trace, origin, outer, span)

          case Scope.PrefixedScope(outer, _, _, _) => search(target, trace, origin, outer, span)

          case Scope.LocalPatternScope(outer, _, _) => search(target, trace, origin, outer, span)

      case sym :: Nil if trace.contains(sym) =>
        val tpText = target.show
        val loop = (trace :+ sym).map(_.fullName).mkString(" -> ")
        Reporter.error(s"Divergence in resolving auto of the type $tpText: " + loop + ".", span.toPos)
        Namer.errorWord(span)

      case sym :: Nil =>
        if sym.info.isProcType then
          var procType = sym.info.asProcType
          if procType.params.nonEmpty then
            Reporter.error(s"The auto ${sym.fullName} require non-auto params." + history, span.toPos)
            Namer.errorWord(span)

          else
            var fun: Word = Ident(sym.dealias)(span)
            if procType.tparams.nonEmpty then
              fun = namer.instantiatePoly(procType, fun)
              procType = fun.tpe.asProcType

            // This step cannot revert, thus the inference state is persisted
            Subtyping.conforms(procType.resultType, target)

            if procType.autos.isEmpty then
              Ident(sym.dealias)(span).appliedTo()

            else
              val autos =
                for NamedInfo(name, autoInfo) <- procType.autos yield
                  // Nested resolution should start from origin
                  search(autoInfo, trace :+ sym, origin, origin, span)

              Apply(fun, Nil, autos)
        else
          Ident(sym)(span)

      case _ =>
        val tpText = target.show
        val names  = candidates.map(_.fullName).mkString(", ")
        Reporter.error(s"Ambiguous autos, multiple candidates satisfy the auto type $tpText: " + names + "." + history, span.toPos)
        Namer.errorWord(span)
