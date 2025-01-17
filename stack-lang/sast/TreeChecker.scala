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
        assert(!sym.isOneOf(Flags.NSpace | Flags.Method | Flags.Field), sym)
        word

      case Select(qual, name) =>
        assert(qual.tpe.isValueType, "Qualifier should be a value type, found = " + qual.tpe.show)
        word

      case FieldAssign(qual, name, rhs) =>
        assert(qual.tpe.isObjectType, "Object type expected, found = " + qual.tpe.show)
        word

      case _: This =>
        assert(word.tpe.isObjectType, "this should have object type, found = " + word.tpe.show)
        word

      case Apply(fun, args) =>
        fun.tpe.asInvokableType match
          case appType: InvokableType =>
            val expectArgSize = appType.paramTypes.size
            assert(expectArgSize == args.size, s"args do not match, expect = $expectArgSize, found = " + args.size)
        end match
        word

      case _ => recur(word)
