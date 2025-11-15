package sast

import Types.*
import Trees.*
import Symbols.*

import ast.Positions.*

import scala.collection.mutable

object AutoResolution:
  enum Result:
    case Success(args: List[Word])
    case Failure(message: String)

  def resolve(procType: ProcType, havings: List[Symbol], span: Span)(using Definitions, Source): Result =
    val autos = new mutable.ArrayBuffer[Word]

    val count = procType.autos.size
    var i = 0
    while i < count do
      val NamedInfo(name, autoInfo) = procType.autos(i)
      search(autoInfo, procType.candidates(i), havings, Vector.empty, span) match
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

  def search(targetType: Type, cands: List[Symbol | MemberCandidate], havings: List[Symbol], trace: Vector[Symbol], span: Span)
      (using Definitions, Source)
  : Option[Word] =
    val res = findFirst(havings) { sym => tryValue(sym, targetType, trace, span) }

    if res.nonEmpty then return res

    findFirst(cands):
      case sym: Symbol => tryValue(sym, targetType, trace, span)
      case MemberCandidate(tp, name) => tryMember(tp, name, targetType, trace, span)

  def tryValue(sym: Symbol, targetType: Type, trace: Vector[Symbol], span: Span)(using Definitions): Option[Word] =
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
        // recursive resolution
        ???


    else
      if Subtyping.conforms(tp, targetType) then Some(Ident(sym)(span)) else None

  def tryMember(tp: Type, name: String, targetType: Type, trace: Vector[Symbol], span: Span)
      (using Definitions, Source)
  : Option[Word] = ???
