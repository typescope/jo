package sast

import Sast.*
import Types.*
import Symbols.*

/** Check invariants of SAST */
object TreeChecker extends SastOps.TreeMap:
  type Context = Symbol

  def check(nss: List[Namespace]): List[Namespace] =
    for
      ns <- nss
      case fdef: FunDef <- ns.defs
    do
      given Context = fdef.symbol
      this.recurFunDef(fdef)
    end for

    nss

  def apply(word: Word)(using info: Context): Word =
    word match
      case Ident(sym) =>
        assert(!sym.isOneOf(Flags.NSpace | Flags.Method | Flags.Field | Flags.Type), sym)
        word

      case Select(qual, name) =>
        assert(qual.tpe.isValueType, "Qualifier should be a value type, found = " + qual.tpe.show)
        word

      case FieldAssign(qual, name, rhs) =>
        assert(qual.tpe.isObjectType, "Object type expected, found = " + qual.tpe.show)
        assert(qual.tpe.asObjectType.isMutable(name), s"Field $name is not mutable")
        word

      case Apply(fun, args) =>
        fun.tpe.asProcType match
          case funType =>
            val expectArgSize = funType.paramTypes.size
            assert(expectArgSize == args.size, s"args do not match, expect = $expectArgSize, found = " + args.size)

            for (paramType, arg) <- funType.paramTypes.zip(args) do
              Subtyping.conforms(arg.tpe, paramType)
        end match
        word

      case _ => recur(word)
