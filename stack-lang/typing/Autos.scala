package typing

import ast.{ Trees => Ast }
import ast.Positions.*

import sast.*
import sast.Trees.*
import sast.Types.*
import sast.Symbols.*

import reporting.Reporter
import typing.Inference.TargetType

import scala.collection.mutable

object Autos:
  def check(candidates: List[Ast.AutoCandidate], autoType: Type, namer: Namer)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : (List[Trees.AutoCandidate], List[Symbol | MemberCandidate]) =

    val validTrees = new mutable.ArrayBuffer[Trees.AutoCandidate]
    val validSymbols = new mutable.ArrayBuffer[Symbol | MemberCandidate]

    for candidate <- candidates do
      candidate match
        case value @ Ast.AutoCandidate.Value(ref) =>
          val candidateRef =
            given TargetType = TargetType.Unknown
            namer.transform(ref)

          candidateRef.tpe match
            case tp @ StaticRef(sym) =>
              if sym.is(Flags.Fun) then
                val procType = sym.info.asProcType

                // Check: must have no regular parameters (only auto parameters allowed)
                if procType.params.nonEmpty then
                  Reporter.error(s"Auto candidate must have no regular parameters, found ${procType.params.size} parameters", value.span.toPos)

                // Check: must have no type parameters
                else if procType.tparams.nonEmpty then
                  Reporter.error(s"Auto candidate cannot have type parameters, found ${procType.tparams.size} type parameters", value.span.toPos)

                // Check: result type must conform to auto type
                else if !Subtyping.conforms(procType.resultType, autoType) then
                  Reporter.error(s"Auto candidate return type ${procType.resultType.show} does not conform to auto type ${autoType.show}", value.span.toPos)

                else
                  validTrees += AutoCandidate.Value(sym)(value.span)
                  validSymbols += sym

              else if tp.isValueType then
                validTrees += AutoCandidate.Value(sym)(value.span)
                validSymbols += sym

              else
                Reporter.error("A reference to a value candidate expected, found = " + tp.show, value.span.toPos)

            case tp =>
              if !tp.isError then
                Reporter.error("A reference to a value candidate expected, found = " + tp.show, value.span.toPos)

        case member @ Ast.AutoCandidate.Member(tpt, memberName) =>
          val typedTpt = namer.transformType(tpt, allowPackType = false)
          val memberType = typedTpt.tpe

          validTrees += AutoCandidate.Member(typedTpt, memberName)(member.span)
          validSymbols += MemberCandidate(memberType, memberName)

    end for
    (validTrees.toList, validSymbols.toList)

class Autos(namer: Namer):
  def derive
      (procType: ProcType, span: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : List[Word] =
    for NamedInfo(name, autoInfo) <- procType.autos yield
      search(autoInfo, Vector.empty, sc, sc, span)

  def search(target: Type, trace: Vector[Symbol], origin: Scope, sc: Scope, span: Span)(using Definitions, Reporter, Source): Word =
    // println("searching scope owner = " + sc.owner + ", autos = " + sc.autos)

    def history: String =
      if trace.isEmpty then ""
      else " Resolution trace: " + trace.map(_.name).mkString(" -> ")

    // Check that target type is initialized
    if target.exists(tp => tp.is[TypeVar] && !tp.as[TypeVar].isInstantiated) then
      val tpText = target.show
      Reporter.error(s"Not fully instantiated auto type $tpText." + history, span.toPos)
      return errorWord(span)

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
            errorWord(span)

          case Scope.NestedScope(outer, _, _) => search(target, trace, origin, outer, span)

          case Scope.PrefixedScope(outer, _, _, _) => search(target, trace, origin, outer, span)

          case Scope.LocalPatternScope(outer, _, _) => search(target, trace, origin, outer, span)

      case sym :: Nil if trace.contains(sym) =>
        val tpText = target.show
        val loop = (trace :+ sym).map(_.fullName).mkString(" -> ")
        Reporter.error(s"Divergence in resolving auto of the type $tpText: " + loop + ".", span.toPos)
        errorWord(span)

      case sym :: Nil =>
        if sym.info.isProcType then
          var procType = sym.info.asProcType
          if procType.params.nonEmpty then
            Reporter.error(s"The auto ${sym.fullName} require non-auto params." + history, span.toPos)
            errorWord(span)

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

              Apply(fun, Nil, autos)(span)
        else
          Ident(sym)(span)

      case _ =>
        val tpText = target.show
        val names  = candidates.map(_.fullName).mkString(", ")
        Reporter.error(s"Ambiguous autos, multiple candidates satisfy the auto type $tpText: " + names + "." + history, span.toPos)
        errorWord(span)
