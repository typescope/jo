import Sast.*
import Symbols.*
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
      case _: IntLit | _: BoolLit | _: RecordLit | _: Select | _: Encoded =>
        vs.push(word.tpe)

      case Assign(sym, words) =>
        vs.expectEmpty("No result expected before assignment", word.pos)

      case If(cond, thenp, elsep) =>
        if word.tpe.isValueType then vs.push(word.tpe)

      case While(cond, body) =>
        vs.expectEmpty("No result expected before while loop", word.pos)

      case Ident(sym) =>
        val info = sym.info

        info match
          case tp: Type.Proc => vs.call(sym, tp, word.pos)

          case _ => if info.isValueType then vs.push(info)

      case Phrase(words) =>
        check(words)

  def check(words: List[Word])(using vs: ValueStack): Unit =
    for word <- words do check(word)

  def expect(tree: Tree, tp: Type): Unit =
    if !matches(tree.tpe, tp) then
      Reporter.error(s"Expect type $tp, found = ${tree.tpe}", tree.pos)

  def expectValueType(tp: Type, pos: Span): Unit =
    if !tp.isValueType then
      Reporter.error(s"Expect value type, found = $tp", pos)

  def expectValueType(tree: Tree): Unit =
    expectValueType(tree.tpe, tree.pos)

  def fieldType(qualType: Type, field: String, pos: Span): Type =
    if !qualType.isRecordType then
      Reporter.error(s"Expect record type, found = $qualType", pos)
      Type.Error
    else if !qualType.hasField(field) then
      Reporter.error(s"Expect field $field in record type $qualType, found none", pos)
      Type.Error
    else
      qualType.fieldType(field)

  def checkTagValue(tag: Ast.Ident, value: Phrase, unionType: Type, typePos: Span): Type =
    if !unionType.isUnionType then
      Reporter.error(s"Expect union type, found = $unionType", typePos)
      Type.Error
    else if !unionType.hasTag(tag.name) then
      Reporter.error(s"The tag $tag does not exist in union type $unionType", tag.pos)
      Type.Error
    else
      val tagType = unionType.tagType(tag.name)
      expect(value, tagType)
      tagType
object Checker:
  /**
    * Represent the types of values on the value stack.
    *
    * Used to check stack safety.
    */
  class ValueStack:
    /** Don't expose size in order to handle errors */
    private val valueTypes = mutable.ArrayBuffer.empty[Type]
    private var hasError = false

    private def setError() =
      hasError = true

    def isError = hasError

    def size: Int = valueTypes.size

    def expectEmpty(msg: String, pos: Span)(using Reporter): Unit =
      if !isError && this.size != 0 then
        Reporter.error(s"$msg, found = $size", pos)
        setError()

    def call(fun: Symbol, tp: Type.Proc, pos: Span)(using Reporter): Unit =
      if isError then return

      val Type.Proc(names, paramTypes, resType) = tp

      if this.size < paramTypes.size then
        Reporter.error(
          s"Function $fun expects ${paramTypes.size} arguments, found = $size",
          pos)
        setError()
      else
        val argTypes = valueTypes.takeRight(paramTypes.size)
        val agree =
          paramTypes.zip(argTypes).forall: (tp1, tp2) =>
            matches(tp1, tp2)

        if !agree then
          Reporter.error(
            s"Function $fun expects arguments $paramTypes, found = ${argTypes}",
            pos)
          setError()
        end if

        valueTypes.dropRightInPlace(paramTypes.size)

        if resType.isValueType then
          push(resType)

    def push(tp: Type): Unit =
      assert(tp.isValueType, tp)
      valueTypes += tp
      if tp.isError then setError()

    def push(tps: List[Type]): Unit = for tp <- tps do push(tp)

    def pop(): Option[Type] =
      if valueTypes.isEmpty then
        None
      else if isError then
        Some(Type.Error)
      else
        val tp = valueTypes.remove(this.size - 1)
        Some(tp)
