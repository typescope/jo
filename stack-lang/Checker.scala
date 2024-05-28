import Sast.*
import Types.*
import Reporter.Span

import scala.collection.mutable

/**
  * Check stack safety
  *
  * - Fences can execute with empty value stack.
  * - Value and function definitions work with empty value stack and result in
  *   one value.
  * - The condition of an if-statement works as a fence and result in one value.
  * - Empty stack is never popped.
  */
class Checker(using Reporter):
  import Checker.ValueStack

  def check(word: Word)(using vs: ValueStack): Unit =
    word match
      case _: Word.IntLit | _: Word.BoolLit =>
        vs.push(word.tpe.asInstanceOf[ValueType] :: Nil)

      case Word.Assign(sym, words) =>
        vs.expectEmpty("No result expected before assignment", word.pos)

      case Word.If(cond, thenp, elsep) =>
        word.tpe match
          case Type.Void =>
          case tp: ValueType => vs.push(tp :: Nil)

      case Word.While(cond, body) =>
        vs.expectEmpty("No result expected before while loop", word.pos)

      case Word.Ident(sym) =>
        val info = sym.info

        info match
          case tp: ValueType => vs.push(tp :: Nil)

          case Type.Void =>

          case Type.Error =>

          case Proc(names, paramTypes, resType) =>

  def check(words: List[Word])(using vs: ValueStack): Unit =
    for word <- words do check(word)

  def expect(tree: Tree, tp: Type): Unit =
    if !matches(tree.tpe, tp) then
      Reporter.error(s"Expect type $tp, found = ${tree.info}", word.pos)

  def expectValueType(tp: Type, pos: Span): Unit =
    if !tp.isValueType then
      Reporter.error(s"Expect value type, found = $tp", word.pos)

object Checker:
  /**
    * Represent the types of values on the value stack.
    *
    * Used to check stack safety.
    */
  class ValueStack:
    /** Don't expose size in order to handle errors */
    private val valueTypes = mutable.ArrayBuffer.empty[ValueType]
    private var hasError = false

    def setError() =
      hasError = true

    def isError = hasError

    def size: Int = valueTypes.size

    def expectEmpty(msg: String, pos: Span)(using Reporter): Unit =
      if this.size != 0 then
        Reporter.error(s"$msg, found = $size", pos)
        setError()

    def expect(expect: Type, pos: Span)(using Reporter): Unit =
      if isError then return

      expect match
        case Type.Void =>
          // Void type is only expected as function return, and is trivially
          // satisfied

        case tp  =>
          if this.size != 1 then
            Reporter.error(s"One value expected, found = $size", pos)
            setError()
          else if !matches(valueTypes.head, expect) then
            Reporter.error(s"Expect type $expect, found = ${valueTypes.head}", pos)
            setError()

    def push(tps: List[ValueType]) =
      valueTypes ++= tps
      if tps.exists(_.isInstanceOf[Type.Error] then setError()

    def pop(): Option[ValueType] =
      if valueTypes.isEmpty then
        None
      else if isError then
        Some(Type.Error)
      else
        val tp = valueTypes.remove(this.size - 1)
        Some(tp)

    def clear() = valueTypes.clear()

    def pop(tps: List[ValueType], msg: String, pos: Span)(using Reporter) =
      if !isError && this.size < tps.size then
        Reporter.error(s"$msg, found = $size", pos)
        setError()
      else
