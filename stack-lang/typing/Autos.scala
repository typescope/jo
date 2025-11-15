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
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, checks: Checks)
  : (List[Trees.AutoCandidate], List[Symbol | MemberCandidate]) =

    val validTrees = new mutable.ArrayBuffer[Trees.AutoCandidate]
    val validSymbols = new mutable.ArrayBuffer[Symbol | MemberCandidate]

    for candidate <- candidates do
      candidate match
        case value @ Ast.AutoCandidate.Value(ref) =>
          val candidateRef =
            given TargetType = TargetType.Unknown
            Inference.freshIsolate:
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

  def resolve(fun: Word, args: List[Word], havings: List[Ident], span: Span)(using Definitions, Source, Reporter) =
    val procType: ProcType = fun.tpe.asProcType

    // TODO: check the auto arguments are fully initialized

    AutoResolution.resolve(procType, havings, span) match
      case AutoResolution.Result.Success(autos) =>
        Apply(fun, args, autos)(span)

      case AutoResolution.Result.Failure(message) =>
        Reporter.error(message, span.toPos)
        errorWord(span)
