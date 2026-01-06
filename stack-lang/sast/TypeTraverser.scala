package sast

import Types.*

abstract class TypeTraverser:
  def apply(tp: Type): Unit

  def recur(tp: Type): Unit =
    tp match
      case VoidType | ErrorType | AnyType | BottomType =>

      case _: StaticRef | _: ContainerInfo | _: ClassInfo  | _: ConstantType =>

      case tvar: TypeVar =>
        if tvar.isInstantiated then this(tvar.instantiated)

      case mref: MemberRef =>
        this(mref.prefix)

      case RecordType(fields) =>
        for field <- fields do this(field.info)

      case UnionType(branches) =>
        for branch <- branches do this(branch)

      case AppliedType(tctor, targs) =>
        for targ <- targs do this(targ)

      case TypeLambda(tparams, resType, _) =>
        // TODO: Once type bounds are supported, we need to transform bounds
        this(resType)

      case LambdaType(params, resType, receives) =>
        for param <- params do this(param)
        this(resType)

      case TypeBound(lo, hi) =>
        this(lo)
        this(hi)

      case DuckType(baseType) =>
        this(baseType)

      case ViewType(baseType) =>
        this(baseType)

      case ProcType(tparams, params, autos, candidates, resType, _, preParamCount) =>
        // TODO: Once type bounds are supported, we need to transform bounds
        for param <- params do this(param.info)

        for auto <- autos do this(auto.info)

        for candidateList <- candidates; candidate <- candidateList do
          candidate match
            case MemberCandidate(tp, name) => this(tp)
            case _ => // Symbol case - no traversal needed

        this(resType)
