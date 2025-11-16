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
  : (List[AutoCandidate], List[Symbol | MemberCandidate]) =

    val validTrees = new mutable.ArrayBuffer[AutoCandidate]
    val validSymbols = new mutable.ArrayBuffer[Symbol | MemberCandidate]

    /** Type conformance check could be delayed */
    def checkTypeConform(valueType: Type, span: Span) =
      // instantiate type parameters with type vars and do subtype check
      given tvars: TypeVars = new UnificationSolver
      val map = new TypeOps.InstantiateTypeParam(span)
      val autoTypeFlex = map(autoType)(using ())
      if !Subtyping.conforms(valueType, autoTypeFlex) then
        Reporter.error(s"Auto candidate return type ${valueType.show} does not conform to auto type ${autoType.show}", span.toPos)

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
                // must be delayed after all symbols are forced
                Checks.add:
                  val procType = sym.info.asProcType

                  // Check: must have no regular parameters (only auto parameters allowed)
                  if procType.params.nonEmpty then
                    Reporter.error(s"Auto candidate must have no regular parameters, found ${procType.params.size} parameters", value.span.toPos)

                  // Check: must have no type parameters
                  else if procType.tparams.nonEmpty then
                    Reporter.error(s"Auto candidate cannot have type parameters, found ${procType.tparams.size} type parameters", value.span.toPos)

                  // Check: result type must conform to auto type
                  else
                    checkTypeConform(procType.resultType, value.span)

                validTrees += AutoCandidate.Value(sym)(value.span)
                validSymbols += sym

              else if tp.isValueType then
                Checks.add:
                  checkTypeConform(tp, value.span)

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

    // Check for shadowed candidates
    checkShadowing(validTrees.toList)

    (validTrees.toList, validSymbols.toList)

  /** Check for shadowed candidates - later candidates shadowed by earlier ones */
  def checkShadowing(candidates: List[AutoCandidate])
      (using defn: Definitions, rp: Reporter, so: Source, checks: Checks)
  : Unit =
    var i = 0
    while i < candidates.size do
      val ci = candidates(i)

      var j = i + 1
      while j < candidates.size do
        val cj = candidates(j)

        // Use Checks.add to make the check lazy (avoid cycles)
        Checks.add:
          (ci, cj) match
            case (AutoCandidate.Value(symI), AutoCandidate.Value(symJ)) =>
              // Value candidate shadowing value candidate
              // Check if typeJ conforms to typeI (making cj unreachable)
              val typeI = symI.info
              val typeJ = symJ.info
              if Subtyping.conforms(typeJ, typeI) then
                Reporter.error(
                  s"Auto candidate $symJ is shadowed by the ealier candidate $symI\n" +
                  s"- Shadowed candidate $symJ: ${typeJ.show}\n" +
                  s"- Earlier candidate $symI: ${typeI.show}",
                  cj.span.toPos
                )

            case (AutoCandidate.Member(tpI, nameI), AutoCandidate.Member(tpJ, nameJ)) =>
              // Member candidate shadowing member candidate
              // Same member name means they're redundant
              if nameI == nameJ && Subtyping.conforms(tpI.tpe, tpJ.tpe) then
                if Subtyping.conforms(tpJ.tpe, tpI.tpe) then
                  Reporter.error(
                    s"Member candidate ${cj.show} appears multiple times in candidate list",
                    cj.span.toPos
                  )
                else
                  Reporter.error(
                    s"Member candidate ${cj.show} is shadowed by the ealier candidate ${ci.show}",
                    cj.span.toPos
                  )


            case (AutoCandidate.Member(memberTpt, memberName), AutoCandidate.Value(symJ)) =>
              memberTpt.tpe.getTermMember(memberName) match
                case Some(tpI) =>
                   val tpJ = StaticRef(symJ).effectiveResultType
                   if Subtyping.conforms(tpI.effectiveResultType, tpJ) then
                      Reporter.error(
                        s"Member candidate ${cj.show} is shadowed by the ealier candidate ${ci.show}",
                        cj.span.toPos
                      )

                case None =>

            case (AutoCandidate.Value(symI), AutoCandidate.Member(memberTpt, memberName)) =>
              // Value candidate before member candidate - this is OK
              // Value handles a closed type set, member handles an open type set

        j += 1
      end while
      i += 1
    end while

  def resolve(fun: Word, args: List[Word], havings: List[Symbol], span: Span)
      (using defn: Definitions, source: Source, rp: Reporter, sc: Scope)
  : Word =
    val procType: ProcType = fun.tpe.asProcType

    if procType.autos.isEmpty then return Apply(fun, args, autos = Nil)(span)

    // Check the auto arguments and member candidate are fully initialized
    var fullyInstantiated = true
    for auto <- procType.autos do
      if !auto.info.isFullyInstantiated then
        fullyInstantiated = false
        Reporter.error("The auto type is not fully instantiated: " + auto.info.show, span.endPoint.toPos)

    for
      cands <- procType.candidates
      cand <- cands
    do
      cand match
        case _: Symbol =>
        case MemberCandidate(tp, _) =>
          if !tp.isFullyInstantiated then
            fullyInstantiated = false
            Reporter.error("The member candidate type is not fully instantiated: " + tp.show, span.toPos)
      end match

    if !fullyInstantiated then return errorWord(span)

    AutoResolution.resolve(procType, havings, Vector.empty[AutoResolution.TraceElement], sc.owner, span.endPoint) match
      case AutoResolution.Result.Success(autos) =>
        Apply(fun, args, autos)(span)

      case AutoResolution.Result.Failure(message) =>
        Reporter.error(message, span.toPos)
        errorWord(span)
