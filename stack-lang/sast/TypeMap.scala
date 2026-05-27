package sast

import Types.*

abstract class TypeMap(using Definitions):
  type Context

  def apply(tp: Type)(using Context): Type

  def recur(tp: Type)(using Context): Type =
    tp match
      case VoidType | ErrorType | AnyType | BottomType =>
        tp

      case _: StaticRef | _: ConstantType =>
        tp

      case tvar: TypeVar =>
        if tvar.isInstantiated then this(tvar.instantiated)
        else tvar

      case mref @ MemberRef(prefix, _) =>
        val prefix2 = this(prefix)
        if prefix2 `eq` prefix then mref
        else mref.copy(prefix = prefix2)

      case UnionType(branches) =>
        var changed = false
        val branches2 =
          for branch <- branches yield
            val branch2 = this(branch)
            changed ||= branch2 `ne` branch
            branch2

        if changed then UnionType(branches2) else tp

      case AppliedType(tctor, targs) =>
        var changed = false
        val targs2 = for targ <- targs yield
          val targ2 = this(targ)
          changed ||= targ2 `ne` targ
          targ2

        if changed then AppliedType(tctor, targs2) else tp

      case LambdaType(params, resType, receives) =>
        var changed = false
        val params2 = for param <- params yield
          val param2 = this(param)
          changed ||= param2 `ne` param
          param2

        val resType2 = this(resType)
        changed ||= resType2 `ne` resType

        if changed then LambdaType(params2, resType2, receives) else tp

      case tp @ DuckType(baseType) =>
        val baseType2 = this(baseType)
        if baseType2 `eq` baseType then tp
        else DuckType(baseType2)(tp.adaptersLazy)

      case tp @ ExtensionType(base) =>
        val base2 = this(base)
        if base2 `eq` base then tp
        else ExtensionType(base2)(tp.extensionsLazy)

      case AnnotType(base, annot) =>
        val base2 = this(base)
        if base2 eq base then tp else AnnotType(base2, annot)

      case procType: ProcType =>
        recurProcType(procType)

      case RecordType(fields) =>
        val fields2 =
          for field <- fields
          yield field.copy(info = this(field.info))
        RecordType(fields2)

  private def recurProcType(procType: ProcType)(using Context): ProcType =
    val ProcType(tparams, params, autos, candidates, resType, receives, preParamCount, preTypeParamCount) = procType

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
    ProcType(tparams, params2, autos2, candidates2, resType2, receives, preParamCount, preTypeParamCount)(procType.defaultsLazy)
