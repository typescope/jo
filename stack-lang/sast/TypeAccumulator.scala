package sast

import Types.*

abstract class TypeAccumulator[T](zero: T):
  type Context

  def combine(acc: T, nested: => T): T

  def apply(tp: Type)(using Context): T

  def recur(tp: Type)(using Context): T =
    tp match
      case VoidType | ErrorType | AnyType | BottomType =>
        zero

      case _: StaticRef | _: ConstantType | _: ContainerInfo =>
        zero

      case tvar: TypeVar =>
        if tvar.isInstantiated then this(tvar.instantiated)
        else zero

      case mref: MemberRef =>
        this(mref.prefix)

      case RecordType(fields) =>
        fields.foldLeft(zero): (acc, field) =>
          combine(acc, this(field.info))

      case UnionType(branches) =>
        branches.foldLeft(zero): (acc, branch) =>
          combine(acc, this(branch))

      case AppliedType(_, targs) =>
        targs.foldLeft(zero): (acc, targ) =>
          combine(acc, this(targ))

      case TypeLambda(_, resType, _) =>
        this(resType)

      case LambdaType(params, resType, _) =>
        for param <- params do this(param)
        this(resType)

      case TypeBound(lo, hi) =>
        combine(combine(zero, this(lo)), this(hi))

      case DuckType(baseType) =>
        this(baseType)

      case classInfo: ClassInfo =>
        classInfo.targs.foldLeft(zero): (acc, targ) =>
          combine(acc, this(targ))

      case ProcType(tparams, params, autos, candidates, resType, _, preParamCount) =>
        val acc1 = params.foldLeft(zero): (acc, param) =>
          combine(acc, this(param.info))

        val acc2 = autos.foldLeft(acc1): (acc, auto) =>
          combine(acc, this(auto.info))

        val acc3 = candidates.foldLeft(acc2): (acc, cands) =>
          cands.foldLeft(acc): (acc, cand) =>
            cand match
              case MemberCandidate(tp, _) => combine(acc, this(tp))
              case _ => acc

        combine(acc3, this(resType))
