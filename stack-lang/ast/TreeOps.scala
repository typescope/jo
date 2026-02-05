package ast

import Trees.*

object TreeOps:
  /** A tree traversal for non-toplevel code */
  trait TypeTreeTraverser:
    def apply(tpt: TypeTree): Unit

    def recur(tpt: TypeTree): Unit =
      tpt match
        case _: Select | _: Ident | _: EmptyTypeTree =>

        case UnionType(branches) =>
          for branch <- branches do
            this(branch)

        case ExprType(types) =>
          for tp <- types do
            this(tp)

        case AppliedType(tctor, targs) =>
          this(tctor)
          for targ <- targs do
            this(targ)

        case FunctionType(paramTypes, resultType, _) =>
          this(resultType)
          for paramType <- paramTypes do
            this(paramType)

        case DuckType(tpe, _) =>
          this(tpe)

        case ExtensionType(base, ext) =>
          this(base)
          this(ext)

    end recur
  end TypeTreeTraverser
