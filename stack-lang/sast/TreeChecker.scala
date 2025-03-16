package sast

import Sast.*
import Types.*
import Symbols.*

import ast.Positions.Source
import reporting.Reporter

/** Check invariants of SAST */
object TreeChecker:
  def check(nss: List[Namespace])(using Reporter): List[Namespace] =
    for
      ns <- nss
      case fdef: FunDef <- ns.defs
    do
      given Source = fdef.symbol.sourcePos.source
      new TreeChecker().recurFunDef(fdef)
    end for

    nss

class TreeChecker()(using Source) extends SastOps.TreeTraverser:
  type Context = Reporter

  def apply(word: Word)(using info: Context): Unit =
    word match
      case Ident(sym) =>
        assert(!sym.isOneOf(Flags.NSpace | Flags.Method | Flags.Field | Flags.Type), sym)

      case Select(qual, name) =>
        if !qual.tpe.isValueType then
          Reporter.error("Qualifier should be a value type, found = " + qual.tpe.show, word.pos)

        else if !qual.tpe.hasTermMember(name) then
          Reporter.error(s"Qualifier does not have member $name, found = " + qual.tpe.show, word.pos)

        else if !Subtyping.conforms(qual.tpe.termMember(name), word.tpe) then
          val memberType = qual.tpe.termMember(name)
          Reporter.error(s"Member type ${memberType.show} is not a subtype of ${word.tpe.show}", word.pos)

      case _: RecordLit =>
        if !word.tpe.isRecordType then
          Reporter.error("Expect record type, found = " + word.tpe.show, word.pos)

      case _: Object =>
        if !word.tpe.isObjectType then
          Reporter.error("Expect object type, found = " + word.tpe.show, word.pos)

      case FieldAssign(qual, name, rhs) =>
        if !qual.tpe.isObjectType then
          Reporter.error("Object type expected, found = " + qual.tpe.show, word.pos)

        if !qual.tpe.asObjectType.isMutable(name) then
          Reporter.error(s"Field $name is not mutable", word.pos)

      case Apply(fun, args) =>
        fun.tpe.asProcType match
          case funType =>
            val expectArgSize = funType.paramTypes.size
            if expectArgSize != args.size then
              Reporter.error(s"args do not match, expect = $expectArgSize, found = " + args.size + ", tree = " + word.show, word.pos)

            for (paramType, arg) <- funType.paramTypes.zip(args) do
              if !Subtyping.conforms(arg.tpe, paramType) then
                Reporter.error("Found arg type = " + arg.tpe.show + ", paramType = " + paramType.show + ", tree = " + word.show, word.pos)

            if !Subtyping.conforms(funType.resultType, word.tpe) then
              Reporter.error("word.tpe = " + word.tpe.show + ", result type = " + funType.resultType + " tree = " + word.show, word.pos)
        end match

        fun match
          case Select(qual, _) =>
            if !qual.tpe.isObjectType then
              Reporter.error("Expect object type, found = " + qual.tpe.show, qual.pos)

          case TypeApply(Select(qual, _), targs) =>
            if !qual.tpe.isObjectType then
              Reporter.error("Expect object type, found = " + qual.tpe.show, qual.pos)

          case _ =>

      case _ => recur(word)
