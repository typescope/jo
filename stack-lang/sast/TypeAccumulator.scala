package sast

import Types.*
import Denotations.MemberCandidate

abstract class TypeAccumulator[T](zero: T):
  type Context

  def combine(acc: T, nested: => T): T

  def apply(tp: Type)(using Context): T

  def recur(tp: Type)(using Context): T =
    tp match
      case VoidType | ErrorType | AnyType | BottomType =>
        zero

      case _: StaticRef | _: ConstantType =>
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

      case LambdaType(params, resType, _) =>
        for param <- params do this(param)
        this(resType)

      case DuckType(baseType) =>
        this(baseType)

      case ExtensionType(base) =>
        this(base)

      case AnnotType(base, _) =>
        this(base)

      case ProcType(tparams, params, autos, resType, _, preParamCount, preTypeParamCount) =>
        val acc1 = params.foldLeft(zero): (acc, param) =>
          combine(acc, this(param.tpe))

        val acc2 = autos.foldLeft(acc1): (acc, auto) =>
          val acc2a = combine(acc, this(auto.tpe))

          auto.candidates.foldLeft(acc2a): (acc, cand) =>
            cand match
              case MemberCandidate(tp, _) => combine(acc, this(tp))
              case _ => acc

        combine(acc2, this(resType))
