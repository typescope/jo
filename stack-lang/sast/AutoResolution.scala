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
 *
 * Implementation status:
 * - ✅ Basic resolution with havings taking priority over candidates
 * - ✅ Recursive resolution for candidates with nested auto parameters
 * - ✅ Cycle detection using trace vector
 * - ✅ Member lookup and type conformance checking (simplified)
 * - ⏸️ Creating Words for eta-expanded member calls (requires lambda infrastructure)
 *
 * TODOs:
 * - Implement proper eta-expansion type conformance checking
 * - Create lambda objects or specialized tree nodes for member candidates
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

  def tryMember(tp: Type, name: String, targetType: Type, trace: Vector[Symbol], owner: Symbol, span: Span)
      (using Definitions, Source)
  : Option[Word] =
    // Look up the member on the type
    tp.getTermMember(name) match
      case None => None  // Member doesn't exist

      case Some(memberType) =>
        // For member candidates, we need to check if the member's type (after eta-expansion)
        // conforms to the target type.
        //
        // For a method: def member(params): ReturnType
        // Eta-expansion gives: (receiver: T, params) => receiver.member(params)
        //
        // The memberType is the type of the member itself.
        // If it's a ProcType, we need to check conformance after eta-expansion.
        // If it's a simple value type, we check conformance directly.

        if memberType.isProcType then
          val procType = memberType.asProcType

          // For eta-expansion, the resulting type should be:
          // (receiver: T, params...) => ResultType
          // We need to check if this conforms to targetType
          //
          // For now, do a simplified check:
          // - If targetType is a ProcType, check that adding receiver type as first param would match
          // - Otherwise, just check result type conformance as approximation
          //
          // TODO: Implement proper eta-expansion type construction and conformance checking
          if targetType.isProcType then
            val targetProc = targetType.asProcType
            // Simplified check: result types should match
            if !Subtyping.conforms(procType.resultType, targetProc.resultType) then
              return None
          else
            // Target is not a ProcType - unlikely for member candidates but handle it
            if !Subtyping.conforms(procType.resultType, targetType) then
              return None

          // If the member has auto parameters, we need to resolve them recursively
          if procType.autos.nonEmpty then
            resolve(procType, havings = Nil, trace, owner, span) match
              case Result.Success(autos) =>
                // TODO: Create eta-expanded member call with resolved autos
                // Need to create a lambda object: (receiver: T, params...) => receiver.member(params..., autos)
                // This requires creating:
                //   1. An Object with an apply method
                //   2. The apply method takes (receiver, params) and calls member on receiver
                //   3. The resolved autos are passed to the member call
                // Alternatively, create a specialized tree node for eta-expanded member calls
                None
              case Result.Failure(_) =>
                None
          else
            // TODO: Create eta-expanded member call without autos
            // Need to create a lambda object: (receiver: T, params...) => receiver.member(params...)
            // For example, for [Int].==:
            //   (receiver: Int, that: Int) => receiver == that
            // This should be a Word that has type (T, Params) => ResultType
            None

        else
          // Simple value member (not a method) - check conformance directly
          if Subtyping.conforms(memberType, targetType) then
            // TODO: Create member access for simple value member
            // This is simpler - just need to create a lambda that accesses the member
            // (receiver: T) => receiver.member
            None
          else
            None
