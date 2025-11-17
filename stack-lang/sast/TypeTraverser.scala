package sast

import Types.*

abstract class TypeTraverser:
  type Context = Definitions

  def apply(tp: Type)(using Context): Unit

  def recur(tp: Type)(using Context): Unit =
    tp match
      case VoidType | ErrorType | AnyType | BottomType =>

      case _: StaticRef | _: MemberRef | _: TypeVar | _: ContainerInfo | _: ClassInfo  | _: ConstantType =>

      case RecordType(fields) =>
        for field <- fields do this(field.info)

      case UnionType(branches) =>
        for branch <- branches do this(branch)

      case TagType(tag, params) =>
        for param <- params do this(param.info)

      case ObjectType(members, _) =>
        for member <- members do this(member.info)

      case AppliedType(tctor, targs) =>
        apply(tctor)
        for targ <- targs do this(targ)

      case TypeLambda(tparams, resType, _) =>
        // TODO: Once type bounds are supported, we need to transform bounds
        this(resType)

      case TypeBound(lo, hi) =>
        this(lo)
        this(hi)

      case ProcType(tparams, params, adapters, autos, candidates, resType, receives, preParamCount) =>
        // TODO: Once type bounds are supported, we need to transform bounds
        for param <- params do this(param.info)

        for auto <- autos do this(auto.info)

        for candidateList <- candidates; candidate <- candidateList do
          candidate match
            case MemberCandidate(tp, name) => this(tp)
            case _ => // Symbol case - no traversal needed

        this(resType)
