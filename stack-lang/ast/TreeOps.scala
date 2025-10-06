package ast

import Trees.*

object TreeOps:
  /** A tree traversal for non-toplevel code */
  trait TypeTreeTraverser:
    def apply(tpt: TypeTree): Unit

    def recur(tpt: TypeTree): Unit =
      tpt match
        case _: Select | _: Ident | _: EmptyTypeTree =>

        case RecordType(fields) =>
          for param <- fields do
            this(param.tpt)

        case UnionType(branches) =>
          for branch <- branches do
            this(branch)

        case ExprType(types) =>
          for tp <- types do
            this(tp)

        case TagType(_, params) =>
          for param <- params do
            this(param.tpt)

        case AppliedType(tctor, targs) =>
          this(tctor)
          for targ <- targs do
            this(targ)

        case FunctionType(paramTypes, resultType, _) =>
          this(resultType)
          for paramType <- paramTypes do
            this(paramType)

        case ObjectType(members) =>
          members.foreach:
            case vdef: ValDef =>
              this(vdef.tpt)

            case ddef: FunDef =>
              for tparam <- ddef.tparams do this(tparam.bound)
              for param <- ddef.params do this(param.tpt)
              this(ddef.resultType)

    end recur
  end TypeTreeTraverser
