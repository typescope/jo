package sast

import Types.*
import Trees.*
import Symbols.*

import ast.Positions.*

import scala.collection.mutable

/**
 * Auto parameter resolution.
 *
 * This module resolves auto parameters for function calls by searching through
 * candidate lists. It supports:
 *
 * 1. Value candidates (Symbol): Direct references to values/functions
 * 2. Member candidates (MemberCandidate): Type members that are eta-expanded
 */
object AutoResolution:
  enum Result:
    case Success(args: List[Word])
    case Failure(message: String)

  def resolve(procType: ProcType, havings: List[Symbol], trace: Vector[Symbol], owner: Symbol, span: Span)(using Definitions, Source): Result =
    val autos = new mutable.ArrayBuffer[Word]

    val count = procType.autos.size
    var i = 0
    while i < count do
      val NamedInfo(name, autoInfo) = procType.autos(i)
      search(autoInfo, procType.candidates(i), havings, trace, owner, span) match
        case Some(auto) => autos += auto

        case None =>
          return Result.Failure("Failed to find auto of the type " + autoInfo.show)
      end match
      i += 1
    end while

    Result.Success(autos.toList)

  def findFirst[T, U](l: List[T])(op: T => Option[U]): Option[U] =
    var i = 0
    val count = l.size
    while i < count do
      val res = op(l(i))
      if res.nonEmpty then return res
      i += 1
    end while
    None

  def search(targetType: Type, cands: List[Symbol | MemberCandidate], havings: List[Symbol], trace: Vector[Symbol], owner: Symbol, span: Span)
      (using Definitions, Source)
  : Option[Word] =
    val res = findFirst(havings) { sym => tryValue(sym, targetType, trace, owner, span) }

    if res.nonEmpty then return res

    findFirst(cands):
      case sym: Symbol => tryValue(sym, targetType, trace, owner, span)
      case MemberCandidate(tp, name) => tryMember(tp, name, targetType, trace, owner, span)

  def tryValue(sym: Symbol, targetType: Type, trace: Vector[Symbol], owner: Symbol, span: Span)(using Definitions, Source): Option[Word] =
    val tp = sym.info

    if tp.isProcType then
      val procType = tp.asProcType
      // Should never encounter. Change to assertion?
      if
        procType.isPolyType
        || !Subtyping.conforms(procType.resultType, targetType)
      then
        return None

      if procType.autos.isEmpty then
        val call = Apply(Ident(sym)(span), args = Nil, autos = Nil)(span)
        Some(call)
      else
        // Check for cycles: if sym is already in trace, we have divergence
        if trace.contains(sym) then
          return None

        // Recursive resolution with increased trace
        val newTrace = trace :+ sym
        resolve(procType, havings = Nil, newTrace, owner, span) match
          case Result.Success(autos) =>
            val call = Apply(Ident(sym)(span), args = Nil, autos = autos)(span)
            Some(call)
          case Result.Failure(_) =>
            None


    else
      if Subtyping.conforms(tp, targetType) then Some(Ident(sym)(span)) else None

  def tryMember(receiverType: Type, name: String, targetType: Type, trace: Vector[Symbol], owner: Symbol, span: Span)
      (using defn: Definitions, so: Source)
  : Option[Word] =

    // Look up the member on the type
    receiverType.getTermMember(name) match
      case None => None  // Member doesn't exist

      case Some(memberType) =>
        // For member candidates, create an eta-expanded lambda
        // For [T].member with type (params) => ResultType
        // Eta-expansion gives: (receiver: T, params) => receiver.member(params)

        if memberType.isProcType then
          val procType = memberType.asProcType
          tryMethodMember(procType, memberType, receiverType, name, targetType, trace, owner, span)

        else
          // Simple value member - check conformance
          // For value members, check if target type is (T) => MemberType
          tryValueMember(memberType, memberType, receiverType, name, targetType, trace, owner, span)


  /** Create eta-expanded lambda
    *
    * For [T].member with type (params) => ResultType
    * Creates: (receiver: T, params) => receiver.member(params, autos)
    */
  def tryMethodMember
      (procType: ProcType, memberRefType: Type, receiverType: Type, memberName: String, targetType: Type,
        trace: Vector[Symbol], owner: Symbol, span: Span)
      (using defn: Definitions, so: Source)
  : Option[Word] =
    // Type conformance check for eta-expanded member
    // Eta-expansion adds receiver as first parameter: (receiver, ...params) => result

    // Create the lambda type (receiver :: params => resultType)
    val lambdaType = ProcType(
      tparams = Nil,
      params = NamedInfo("receiver", receiverType) :: procType.params,
      adapters = List.fill(procType.paramCount + 1)(Nil),
      autos = Nil,
      candidates = Nil,
      resultType = procType.resultType,
      receivesInfo = () => Nil,
      preParamCount = 0
    )

    // Get the apply method type from the target if it's an object type
    val targetProcOpt =
      targetType.getTermMember("apply").flatMap: applyType =>
        if applyType.isProcType then Some(applyType.asProcType)
        else None

    targetProcOpt match
      case Some(targetProc) =>
        if !Subtyping.conforms(lambdaType, targetProc) then
          return None
      case None =>
        return None

    // Resolve nested autos if present
    val resolvedAutos =
      if procType.autos.nonEmpty then
        resolve(procType, havings = Nil, trace, owner, span) match
          case Result.Success(autos) => autos
          case Result.Failure(_) => return None
      else
        Nil

    val effectPolicy = Effects.Policy.Capture(except = Nil)

    val lambda = TreeOps.createLambda(lambdaType, owner, effectPolicy, span): (params, autos) =>
      // params(0) is the receiver, rest are method parameters
      val receiver = params.head
      val methodArgs = params.tail
      val member = Select(receiver, memberName)(memberRefType, span)
      Apply(member, methodArgs, resolvedAutos)(span)

    Some(lambda)

  /** Create eta-expanded lambda
    *
    * For [T].member with type ResultType
    * Creates: (receiver: T) => receiver.member
    */
  def tryValueMember
      (resultType: Type, memberRefType: Type, receiverType: Type, memberName: String,
        targetType: Type, trace: Vector[Symbol], owner: Symbol, span: Span)
      (using defn: Definitions, so: Source)
  : Option[Word] =

    // Create the lambda type (receiver => resultType)
    val lambdaType = ProcType(
      tparams = Nil,
      params = List(NamedInfo("receiver", receiverType)),
      adapters = List(Nil),
      autos = Nil,
      candidates = Nil,
      resultType = resultType,
      receivesInfo = () => Nil,
      preParamCount = 0
    )

    // Get the apply method type from the target if it's an object type
    val targetProcOpt =
      targetType.getTermMember("apply").flatMap: applyType =>
        if applyType.isProcType then Some(applyType.asProcType)
        else None

    targetProcOpt match
      case Some(targetProc) =>
        // Check if target takes exactly one parameter of the receiver type
        // and returns a type compatible with the member type
        if Subtyping.conforms(lambdaType, targetProc) then
          // Create simple member access lambda: (receiver: T) => receiver.member
          val effectPolicy = Effects.Policy.Capture(except = Nil)

          val lambda = TreeOps.createLambda(lambdaType, owner, effectPolicy, span): (params, autos) =>
            // params(0) is the receiver
            val receiver = params.head
            Select(receiver, memberName)(memberRefType, span)

          Some(lambda)

        else
          None
      case None =>
        None
