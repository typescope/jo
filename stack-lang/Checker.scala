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

  private val delayedChecks = new mutable.ArrayBuffer[() => Unit]

  def delayedCheck(check: => Unit): Unit =
    delayedChecks.addOne(() => check)

  def performDelayedChecks(): Unit =
    for check <- delayedChecks do check()
    delayedChecks.clear()

  def check(word: Word)(using vs: ValueStack): Unit =
    word match
      case _: IntLit | _: BoolLit | _: RecordLit | _: Select | _: Encoded =>
        vs.push(word.tpe)

      case _: Assign =>
        vs.expectEmpty("No result expected before assignment", word.pos)

      case _: ValDef =>
        vs.expectEmpty("No result expected before definition", word.pos)

      case If(cond, thenp, elsep) =>
        if word.tpe.isValueType then vs.push(word.tpe)

      case While(cond, body) =>
        vs.expectEmpty("No result expected before while loop", word.pos)

      case Ident(sym) =>
        // The type of the symbol can be different after type erasure
        val info = word.tpe

        if info.isPolyType then
          Reporter.error(s"Function $sym expects type arguments", word.pos)

        if info.isProcType then
          vs.call(sym, info.asProcType, word.pos)

        else if info.isValueType then
          vs.push(info)

      case Phrase(words) =>
        check(words)

  def check(words: List[Word])(using vs: ValueStack): Unit =
    for word <- words do check(word)

  def checkBounds(tctor: TypeTree, targs: List[TypeTree]): Unit =
    if !tctor.tpe.isTypeLambda then
      Reporter.error(s"Expect type lambda, found = ${tctor.tpe.show}", tctor.pos)
    else
      val tl = tctor.tpe.asTypeLambda
      checkBounds(tl.bounds, targs)

  def checkBounds(bounds: List[Type], targs: List[TypeTree]): Unit =
    if bounds.size != targs.size then
      Reporter.error(s"Expect ${bounds.size} args, found = ${targs.size}", targs.head.pos | targs.last.pos)
    else
      for (targ, bound) <- targs.zip(bounds) do
        val argType = targ.tpe
        val TypeBound(lo, hi) = bound.as[TypeBound]
        val loActual = TypeOps.substTypeParams(lo, targs.map(_.tpe))
        val hiActual = TypeOps.substTypeParams(hi, targs.map(_.tpe))
        if !Subtyping.conforms(argType, hiActual) then
          Reporter.error(s"Arg type ${argType.show} does not conform to bound = ${hi.show}, which expands to ${hiActual.show}", targ.pos)
        if !Subtyping.conforms(loActual, argType) then
          Reporter.error(s"Arg type ${argType.show} does not conform to bound = ${hi.show}, which expands to ${hiActual.show}", targ.pos)

  def checkTypeApply(fun: Word, targs: List[TypeTree]): Word =
    if !fun.tpe.isPolyType then
      Reporter.error(s"Expect a poly function type, found = ${fun.tpe.show}", fun.pos)
      Phrase(words = Nil)(ErrorType, fun.pos | targs.last.pos)
    else
      val polyType = fun.tpe.asPolyType
      if polyType.paramCount != targs.size then
        Reporter.error(s"Expect ${polyType.paramCount} args, found = ${targs.size}", targs.head.pos | targs.last.pos)
        Phrase(words = Nil)(ErrorType, fun.pos | targs.last.pos)
      else
        checkBounds(polyType.bounds, targs)
        val tpe = TypeOps.substTypeParams(polyType.resultType, targs.map(_.tpe))
        // TODO: generalize
        val funSym = fun.asInstanceOf[Ident].symbol
        // perform type erasure
        Ident(funSym)(fun.pos, tpe)

  def checkType(tree: Tree, tp: Type): Unit =
    if !Subtyping.conforms(tree.tpe, tp) then
      Reporter.error(s"Expect type ${tp.show}, found = ${tree.tpe.show}", tree.pos)

  def checkValueType(tree: Tree): Unit =
    checkValueType(tree.tpe, tree.pos)

  def checkValueType(tp: Type, pos: Span): Type =
    if !tp.isValueType then
      Reporter.error(s"Expect value type, found = ${tp.show}", pos)
      ErrorType
    else
      tp

  def checkVoidOrValueType(tree: Tree): Unit =
    if !tree.tpe.isVoid then checkValueType(tree)

  def fieldType(qualType: Type, field: String, pos: Span): Type =
    if !qualType.isRecordType then
      Reporter.error(s"Expect record type, found = ${qualType.show}", pos)
      ErrorType
    else
      val recordType = qualType.asRecordType
      if !recordType.hasField(field) then
        Reporter.error(s"Expect field $field in record type ${recordType.show}, found none", pos)
        ErrorType
      else
        recordType.fieldType(field)

  def checkTagValues(values: List[Word], tagTypes: List[Type], tagPos: Span): Unit =
    if tagTypes.size != values.size then
      Reporter.error(s"Expect ${tagTypes.size} args, found = ${values.size}", tagPos)
    else
      for (value, tagType) <- values.zip(tagTypes) do
        checkType(value, tagType)

  def tagTypes(tag: Ast.Ident, unionType: Type, typePos: Span): Option[List[Type]] =
    if !unionType.isUnionType then
      Reporter.error(s"Expect union type, found = ${unionType.show}", typePos)
      None
    else
      val unionType2 = unionType.asUnionType
      if !unionType2.hasTag(tag.name) then
        Reporter.error(s"The tag ${tag.name} does not exist in union type $unionType2", tag.pos)
        None
      else
        Some(unionType2.tagType(tag.name))

  /** Explicit drop of values in if/match expressions */
  def adapt(word: Word, otherType: Type, pos: Span): Word =
    val curType = word.tpe
    TypeOps.commonResultType(otherType, curType) match
      case Some(commonType) =>
        if commonType.isVoid && curType.isValueType then
          Encoded(word)(VoidType)
        else
          word

      case None =>
        Reporter.error(s"Cannot find common result type between ${curType.show} and ${otherType.show}", pos)
        Phrase(word :: Nil)(ErrorType, pos)
    end match

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

    def call(fun: Symbol, tp: ProcType, pos: Span)(using Reporter): Unit =
      if isError then return

      val ProcType(names, paramTypes, resType) = tp

      if this.size < paramTypes.size then
        Reporter.error(
          s"Function $fun expects ${paramTypes.size} arguments, found = $size",
          pos)
        setError()
      else
        val argTypes = valueTypes.takeRight(paramTypes.size)
        val agree =
          argTypes.zip(paramTypes).forall: (tp1, tp2) =>
            Subtyping.conforms(tp1, tp2)

        if !agree then
          val expect = paramTypes.map(_.show).mkString("(", ", ", ")")
          val actual = argTypes.map(_.show).mkString("(", ", ", ")")
          Reporter.error(
            s"Function $fun expects arguments $expect, found = $actual",
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
        Some(ErrorType)
      else
        val tp = valueTypes.remove(this.size - 1)
        Some(tp)
