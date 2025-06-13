package sast

import Types.*

import scala.collection.mutable

/** Cache for expensive operations */
class Cache:
  private val subtypingCache: mutable.Map[Type, mutable.Map[Type, Boolean]] =
    mutable.Map.empty

  def conforms(tp1: Type, tp2: Type, cache: Boolean)(work: => Boolean)(using Definitions): Boolean =
    val tp1norm = TypeOps.normalize(tp1)
    val tp2norm = TypeOps.normalize(tp2)
    subtypingCache.get(tp1norm) match
      case Some(innerMap) =>
        innerMap.get(tp2norm) match
          case Some(res) => res
          case None =>
            val res = work
            if cache then innerMap(tp2norm) = res
            res

      case None =>
        val innerMap: mutable.Map[Type, Boolean] = mutable.Map.empty
        subtypingCache(tp1norm) = innerMap
        val res = work
        if cache then innerMap(tp2norm) = res
        res

  private val substitutionCache: mutable.Map[Type, mutable.Map[List[Type], Type]] =
    mutable.Map.empty

  def substitute(tp: Type, targs: List[Type])(work: => Type)(using Definitions): Type =
    val tpNorm = TypeOps.normalize(tp)
    val targs2 = targs.map(TypeOps.normalize)
    substitutionCache.get(tpNorm) match
      case Some(innerMap) =>
        innerMap.get(targs2) match
          case Some(res) => res
          case None =>
            val res = work
            innerMap(targs2) = res
            res

      case None =>
        val innerMap: mutable.Map[List[Type], Type] = mutable.Map.empty
        substitutionCache(tp) = innerMap
        val res = work
        innerMap(targs2) = res
        res

  private val approxCache: mutable.Map[Type, Type] =
    mutable.Map.empty

  def approximate(tp: Type)(work: => Type): Type =
    approxCache.get(tp) match
      case Some(approxType) if !approxType.is[TypeVar] => approxType

      case _ =>
        val res = work
        approxCache(tp) = res
        res
