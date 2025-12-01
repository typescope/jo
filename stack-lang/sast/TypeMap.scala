package sast

import Types.*

abstract class TypeMap(using Definitions):
  type Context

  def apply(tp: Type)(using Context): Type

  def recur(tp: Type)(using Context): Type =
    tp match
      case VoidType | ErrorType | AnyType | BottomType =>
        tp

      case _: StaticRef | _: MemberRef | _: TypeVar | _: ConstantType | _: ContainerInfo =>
        tp

      case RecordType(fields) =>
        val fields2 =
          for field <- fields
          yield field.copy(info = this(field.info))
        RecordType(fields2)

      case UnionType(branches) =>
        val branches2 =
          for branch <- branches
          yield this(branch)

        UnionType(branches2)

      case TagType(tag, params) =>
        val params2 =
          for param <- params yield param.copy(info = this(param.info))
        TagType(tag, params2)

      case ObjectType(members, muts) =>
        val members2 = members.map: ninfo =>
          ninfo.copy(info = this(ninfo.info))

        ObjectType(members2, muts)

      case AppliedType(tctor, targs) =>
        val targs2 = for targ <- targs yield this(targ)
        AppliedType(tctor, targs2)

      case TypeLambda(tparams, resType, preParamCount) =>
        // TODO: Once type bounds are supported, we need to transform bounds
        TypeLambda(tparams, this(resType), preParamCount)

      case TypeBound(lo, hi) =>
        TypeBound(this(lo), this(hi))

      case classInfo: ClassInfo =>
        val targs2 = classInfo.targs.map(this.apply)
        classInfo.copy(targs = targs2)

      case ProcType(tparams, params, adapters, autos, candidates, resType, receives, preParamCount) =>
        // TODO: Once type bounds are supported, we need to transform bounds
        val params2 =
          for param <- params
          yield param.copy(info = this(param.info))

        val autos2 =
          for auto <- autos
          yield auto.copy(info = this(auto.info))

        val candidates2 =
          for candidateList <- candidates
          yield candidateList.map {
            case MemberCandidate(tp, name) => MemberCandidate(this(tp), name)
            case sym => sym  // Symbol case (no transformation needed)
          }

        val resType2 = this(resType)
        ProcType(tparams, params2, adapters, autos2, candidates2, resType2, receives, preParamCount)
