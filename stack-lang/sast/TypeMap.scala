package sast

import Types.*

abstract class TypeMap(using Definitions):
  type Context

  def apply(tp: Type)(using Context): Type

  def recur(tp: Type)(using Context): Type =
    tp match
      case VoidType | ErrorType | AnyType | BottomType =>
        tp

      case _: StaticRef  | _: ConstantType | _: ContainerInfo =>
        tp

      case tvar: TypeVar =>
        if tvar.isInstantiated then this(tvar.instantiated)
        else tvar

      case mref: MemberRef =>
        mref.copy(prefix = this(mref.prefix))

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

      case AppliedType(tctor, targs) =>
        val targs2 = for targ <- targs yield this(targ)
        AppliedType(tctor, targs2)

      case TypeLambda(tparams, resType, preParamCount) =>
        // TODO: Once type bounds are supported, we need to transform bounds
        TypeLambda(tparams, this(resType), preParamCount)

      case LambdaType(params, resType, receives) =>
        val params2 = params.map(this.apply)
        val resType2 = this(resType)
        LambdaType(params2, resType2, receives)

      case TypeBound(lo, hi) =>
        TypeBound(this(lo), this(hi))

      case tp @ DuckType(baseType) =>
        val baseType2 = this(baseType)
        DuckType(baseType2)(() => tp.adapters)

      case tp @ ExtensionType(base) =>
        ExtensionType(this(base))(() => tp.extensions)

      case classInfo: ClassInfo =>
        val targs2 = classInfo.targs.map(this.apply)
        val views2 = classInfo.directViews.map(this.apply)
        new ClassInfo(
          classInfo.classSymbol,
          classInfo.tparams,
          targs2,
          classInfo.self,
          classInfo.fields,
          classInfo.methods,
          views2
        )(() => classInfo.extensions)

      case procType: ProcType =>
        recurProcType(procType)

  private def recurProcType(procType: ProcType)(using Context): ProcType =
    val ProcType(tparams, params, autos, candidates, resType, receives, preParamCount, preTypeParamCount) = procType
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
    // DefaultValue contains no Types to map; thread defaultsFun through unchanged
    ProcType(tparams, params2, autos2, candidates2, resType2, receives, preParamCount, preTypeParamCount)(procType.defaultsFun)
