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
  /** Trace element to track resolution path and detect cycles */
  enum TraceElement:
    case ValueElement(sym: Symbol)
    case MemberElement(receiverType: Type, memberName: String)

  /** Failure reasons for auto resolution */
  enum FailureReason:
    case Cycle(trace: Vector[TraceElement])
    case TypeMismatch(found: Type, expected: Type)
    case MemberNotFound(receiverType: Type, memberName: String)
    case PolymorphicFunction(sym: Symbol)
    case NestedResolutionFailed
    case NotKnownTypeForArrayBuilder(tp: Type)

  /** Candidate types for auto resolution */
  enum Candidate:
    case ValueCandidate(sym: Symbol)
    case MemberCandidate(tp: Type, name: String)
    case LocalAutoCandidate(sym: Symbol)
    case ArrayBuilderSynthesis(tp: Type)

  /** For error reporting */
  enum SearchNode:
    case Choice(auto: Type, children: mutable.ArrayBuffer[Trial])
    case All(children: mutable.ArrayBuffer[Choice])
    case Trial(cand: Candidate, var next: All | Failure | Success.type)
    case Failure(reason: FailureReason)
    case Success

  def resolve(procType: ProcType, localAutos: List[Symbol], trace: Vector[TraceElement], all: SearchNode.All, owner: Symbol, span: Span)
      (using Definitions, Source)
  : Option[List[Word]] =

    val autos = new mutable.ArrayBuffer[Word]

    val count = procType.autos.size
    var i = 0
    while i < count do
      val NamedInfo(name, autoInfo) = procType.autos(i)
      val cands = procType.candidates(i)

      val choice = new SearchNode.Choice(autoInfo, new mutable.ArrayBuffer)
      all.children += choice

      search(autoInfo, cands, localAutos, trace, choice, owner, span) match
        case Some(auto) => autos += auto

        case None =>
          return None
      end match
      i += 1
    end while

    Some(autos.toList)

  def findFirst[T, U](l: List[T])(op: T => Option[U]): Option[U] =
    var i = 0
    val count = l.size
    while i < count do
      val res = op(l(i))
      if res.nonEmpty then return res
      i += 1
    end while
    None

  def search
      (targetType: Type, cands: List[Symbol | MemberCandidate], localAutos: List[Symbol],
        trace: Vector[TraceElement], choice: SearchNode.Choice, owner: Symbol, span: Span)
      (using defn: Definitions, source: Source)
  : Option[Word] =

    // First, search local autos
    val res = findFirst(localAutos) { sym =>
      // For local autos, track in the search tree with LocalAutoCandidate
      val trial = new SearchNode.Trial(Candidate.LocalAutoCandidate(sym), next = null)
      choice.children += trial
      tryValue(sym, targetType, trace, trial, owner, localAutos, span)
    }

    if res.nonEmpty then return res

    // Then search through candidates
    val candRes = findFirst(cands): cand =>
      val searchCand = cand match
        case sym: Symbol => Candidate.ValueCandidate(sym)
        case MemberCandidate(tp, name) => Candidate.MemberCandidate(tp, name)

      val trial = new SearchNode.Trial(searchCand, next = null)
      choice.children += trial

      cand match
        case sym: Symbol => tryValue(sym, targetType, trace, trial, owner, localAutos, span)
        case MemberCandidate(tp, name) => tryMember(tp, name, targetType, trace, trial, owner, localAutos, span)

    if candRes.nonEmpty then return candRes

    // Last resort: synthesize ArrayBuilder[T] for known types
    targetType match
      case AppliedType(sym, List(elemType)) if sym == defn.ArrayBuilder =>
        val trial = new SearchNode.Trial(Candidate.ArrayBuilderSynthesis(targetType), next = null)
        choice.children += trial

        trySynthesizeArrayBuilder(elemType, trial, owner, span)

      case _ =>
        None

  def tryValue
      (sym: Symbol, targetType: Type, trace: Vector[TraceElement], trial: SearchNode.Trial, owner: Symbol, localAutos: List[Symbol], span: Span)
      (using Definitions, Source)
  : Option[Word] =

    val tp = sym.info

    if tp.isProcType then
      val procType = tp.asProcType
      // Should never encounter. Change to assertion?
      if procType.isPolyType then
        trial.next = SearchNode.Failure(FailureReason.PolymorphicFunction(sym))
        return None

      if !Subtyping.conforms(procType.resultType, targetType) then
        trial.next = SearchNode.Failure(FailureReason.TypeMismatch(procType.resultType, targetType))
        return None

      if procType.autos.isEmpty then
        val call = Apply(Ident(sym)(span), args = Nil, autos = Nil)(span)
        trial.next = SearchNode.Success
        Some(call)
      else
        val loop = trace.exists:
          case TraceElement.ValueElement(s) => s == sym
          case _ => false

        // Check for cycles: if sym is already in trace, we have divergence
        if loop then
          trial.next = SearchNode.Failure(FailureReason.Cycle(trace))
          return None

        val all: SearchNode.All = SearchNode.All(new mutable.ArrayBuffer)
        trial.next = all
        // Recursive resolution with increased trace
        val newTrace = trace :+ TraceElement.ValueElement(sym)
        resolve(procType, localAutos, newTrace, all, owner, span) match
          case Some(resolvedAutos) =>
            val call = Apply(Ident(sym)(span), args = Nil, autos = resolvedAutos)(span)
            Some(call)
          case _ =>
            None


    else
      if Subtyping.conforms(tp, targetType) then
        trial.next = SearchNode.Success
        Some(Ident(sym)(span))
      else
        trial.next = SearchNode.Failure(FailureReason.TypeMismatch(tp, targetType))
        None

  def tryMember
      (receiverType: Type, name: String, targetType: Type, trace: Vector[TraceElement],
        trial: SearchNode.Trial, owner: Symbol, localAutos: List[Symbol], span: Span)
      (using defn: Definitions, so: Source)
  : Option[Word] =

    // Check for cycles: if this member candidate is already in trace, we have divergence
    if trace.exists {
      case TraceElement.MemberElement(rt, mn) =>
        // Check if same member on same type
        mn == name && Subtyping.isEqualType(rt, receiverType)
      case _ => false
    } then
      trial.next = SearchNode.Failure(FailureReason.Cycle(trace))
      return None

    // Look up the member on the type
    receiverType.getTermMember(name) match
      case None =>
        trial.next = SearchNode.Failure(FailureReason.MemberNotFound(receiverType, name))
        None  // Member doesn't exist

      case Some(memberType) =>
        // For member candidates, create an eta-expanded lambda
        // For [T].member with type (params) => ResultType
        // Eta-expansion gives: (receiver: T, params) => receiver.member(params)

        if memberType.isProcType then
          val procType = memberType.asProcType
          tryMethodMember(procType, receiverType, name, targetType, trace, trial, owner, localAutos, span)

        else
          // Simple value member - check conformance
          // For value members, check if target type is (T) => MemberType
          tryValueMember(memberType, receiverType, name, targetType, trial, owner, span)


  /** Create eta-expanded lambda
    *
    * For [T].member with type (params) => ResultType
    * Creates: (receiver: T, params) => receiver.member(params, autos)
    */
  def tryMethodMember
      (procType: ProcType, receiverType: Type, memberName: String, targetType: Type,
        trace: Vector[TraceElement], trial: SearchNode.Trial, owner: Symbol, localAutos: List[Symbol], span: Span)
      (using defn: Definitions, so: Source)
  : Option[Word] =
    // Type conformance check for eta-expanded member
    // Eta-expansion adds receiver as first parameter: (receiver, ...params) => result

    // Create the lambda type for type checking (receiver :: params => resultType)
    val params = NamedInfo("receiver", receiverType) :: procType.params
    val lambdaType = LambdaType(
      params = params.map(_.info),
      resultType = procType.resultType,
      receives = Nil
    )

    // Check if target is a LambdaType
    if !targetType.isLambdaType then
      trial.next = SearchNode.Failure(FailureReason.TypeMismatch(lambdaType, targetType))
      return None

    val targetLambda = targetType.asLambdaType
    if !Subtyping.conforms(lambdaType, targetLambda) then
      trial.next = SearchNode.Failure(FailureReason.TypeMismatch(lambdaType, targetLambda))
      return None

    // Resolve nested autos if present
    val resolvedAutos =
      if procType.autos.nonEmpty then
        // Add current member to trace before resolving nested autos
        val newTrace = trace :+ TraceElement.MemberElement(receiverType, memberName)
        val all: SearchNode.All = SearchNode.All(new mutable.ArrayBuffer)
        trial.next = all
        resolve(procType, localAutos, newTrace, all, owner, span) match
          case Some(autos) => autos
          case _ => return None
      else
        trial.next = SearchNode.Success
        Nil

    val lambda = TreeOps.createLambda(lambdaType, owner, span): params =>
      // params(0) is the receiver, rest are method parameters
      val receiver = params.head
      val methodArgs = params.tail
      val member = Select(receiver, memberName)(span)
      Apply(member, methodArgs, resolvedAutos)(span)

    Some(lambda)

  /** Create eta-expanded lambda
    *
    * For [T].member with type ResultType
    * Creates: (receiver: T) => receiver.member
    */
  def tryValueMember
      (resultType: Type, receiverType: Type, memberName: String,
        targetType: Type, trial: SearchNode.Trial, owner: Symbol, span: Span)
      (using defn: Definitions, so: Source)
  : Option[Word] =

    // Create the lambda type for type checking (receiver => resultType)
    val params = List(NamedInfo("receiver", receiverType))
    val lambdaType = LambdaType(
      params = params.map(_.info),
      resultType = resultType,
      receives = Nil
    )

    // Check if target is a LambdaType
    if !targetType.isLambdaType then
      trial.next = SearchNode.Failure(FailureReason.TypeMismatch(lambdaType, targetType))
      return None

    val targetLambda = targetType.asLambdaType
    if !Subtyping.conforms(lambdaType, targetLambda) then
      trial.next = SearchNode.Failure(FailureReason.TypeMismatch(lambdaType, targetLambda))
      return None

    // Create simple member access lambda: (receiver: T) => receiver.member
    val lambda = TreeOps.createLambda(lambdaType, owner, span): params =>
      // params(0) is the receiver
      val receiver = params.head
      Select(receiver, memberName)(span)

    trial.next = SearchNode.Success
    Some(lambda)


  /** Auto-synthesize ArrayBuilder[T] for known types
    *
    * For numeric types (Int, Float, Char, Byte), returns the corresponding existing
    * ArrayBuilder object (IntArrayBuilder, FloatArrayBuilder, etc.).
    *
    * For non-numeric known types, synthesizes: (size: Int) => ObjectArray[T](size)
    */
  def trySynthesizeArrayBuilder(elemType: Type, trial: SearchNode.Trial, owner: Symbol, span: Span)
      (using defn: Definitions, so: Source)
  : Option[Word] =
    val isSynthesizable = elemType match
      case tvar: TypeVar if !tvar.isInstantiated => false
      case StaticRef(sym) if sym.dealias.isTypeParameter => false
      case _ => true

    if !isSynthesizable then
      trial.next = SearchNode.Failure(FailureReason.NotKnownTypeForArrayBuilder(elemType))
      None

    // For numeric types, use the existing ArrayBuilder objects
    else if elemType.isSubtype(defn.IntType) then
      trial.next = SearchNode.Success
      Some(Ident(defn.IntArrayBuilder)(span))

    else if elemType.isSubtype(defn.FloatType) then
      trial.next = SearchNode.Success
      Some(Ident(defn.FloatArrayBuilder)(span))

    else if elemType.isSubtype(defn.CharType) then
      trial.next = SearchNode.Success
      Some(Ident(defn.CharArrayBuilder)(span))

    else if elemType.isSubtype(defn.ByteType) then
      trial.next = SearchNode.Success
      Some(Ident(defn.ByteArrayBuilder)(span))

    // For non-numeric types, synthesize ObjectArray[T] call
    else
      // Create the result type: Array[T]
      val arrayType = AppliedType(defn.Array_type, List(elemType))

      // Synthesize: (size: Int) => ObjectArray[T](size)
      val intType = defn.IntType
      val lambdaType = LambdaType(List(intType), arrayType, Nil)

      val lambda = TreeOps.createLambda(lambdaType, owner, span): params =>
        val sizeParam = params.head
        // Create: ObjectArray[elemType](size)
        val objectArrayIdent = Ident(defn.ObjectArray)(span)
        val typeApplied = TypeApply(objectArrayIdent, List(TypeTree(elemType)(span)))(elemType, span)
        Apply(typeApplied, List(sizeParam), Nil)(span)

      trial.next = SearchNode.Success
      Some(lambda)

  /** Format search tree as error message */
  def formatSearchTree(all: AutoResolution.SearchNode.All, baseIndent: String = "")(using Definitions): String =
    val sb = new mutable.StringBuilder

    def formatCand(cand: Candidate): String = cand match
      case Candidate.ValueCandidate(sym) => sym.name
      case Candidate.MemberCandidate(tp, name) => s"[${tp.show}].$name"
      case Candidate.LocalAutoCandidate(sym) => s"(local: ${sym.name}: ${sym.info.show})"
      case Candidate.ArrayBuilderSynthesis(tp) => s"synthesizing ${tp.show}"

    def formatFailureReason(reason: FailureReason): String = reason match
      case FailureReason.Cycle(trace) =>
        "cycle"

      case FailureReason.TypeMismatch(found, expected) =>
        s"type mismatch: found ${found.show}, expected ${expected.show}"

      case FailureReason.MemberNotFound(receiverType, memberName) =>
        s"member $memberName not found on type ${receiverType.show}"

      case FailureReason.PolymorphicFunction(sym) =>
        s"polymorphic function ${sym.name} cannot be used as auto candidate"

      case FailureReason.NestedResolutionFailed =>
        "nested auto resolution failed"

      case FailureReason.NotKnownTypeForArrayBuilder(tp) =>
        s"Failure to synthesize because ${tp.show} is not a known type"

    def formatChoice(choice: SearchNode.Choice, indent: String): Unit =
      sb.append(s"${indent}? ${choice.auto.show}\n")
      if choice.children.nonEmpty then
        for trial <- choice.children do
          formatTrial(trial, indent)
      else
        sb.append(s"$indent  ✗ (no candidates)\n")

    def formatTrial(trial: SearchNode.Trial, indent: String): Unit =
      sb.append(s"$indent  → ${formatCand(trial.cand)}")
      trial.next match
        case AutoResolution.SearchNode.Success =>
          sb.append(" ✓\n")

        case SearchNode.Failure(reason) =>
          sb.append(s" ✗ ${formatFailureReason(reason)}\n")

        case all: SearchNode.All =>
          sb.append("\n")
          for choice <- all.children do
            formatChoice(choice, indent + "      ")

        case null =>
          sb.append(" ? (incomplete)\n")

    for choice <- all.children do
      formatChoice(choice, baseIndent)

    sb.toString
