import Sast.*
import Symbols.*
import Types.*
import Inference.*
import Positions.Span

import scala.collection.mutable

/**
  * Perform checks related to types  */
class Checker:
  private val delayedChecks = new mutable.ArrayBuffer[() => Unit]
  var checking = false

  def delayedCheck(check: => Unit): Unit =
    if checking then throw new Exception("cannot add new task during checking")
    delayedChecks.addOne(() => check)

  def performDelayedChecks(): Unit =
    checking = true
    for check <- delayedChecks do check()
    delayedChecks.clear()

  def checkBounds(tctor: TypeTree, targs: List[TypeTree])(using Reporter): Unit =
    if !tctor.tpe.isTypeLambda then
      Reporter.error(s"Expect type lambda, found = ${tctor.tpe.show}", tctor.pos)
    else
      val tl = tctor.tpe.asTypeLambda
      checkBounds(tl.bounds, targs)

  def checkBounds(bounds: List[Type], targs: List[TypeTree])(using Reporter): Unit =
    if bounds.size != targs.size then
      Reporter.error(s"Expect ${bounds.size} args, found = ${targs.size}", (targs.head.span | targs.last.span).toPos)
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

  def checkTypeApply(fun: Word, targs: List[TypeTree])(using Reporter): Word =
    if !fun.tpe.isPolyType then
      Reporter.error(s"Expect a poly function type, found = ${fun.tpe.show}", fun.pos)
      Phrase(words = Nil)(ErrorType, fun.span | targs.last.span)
    else
      val polyType = fun.tpe.asPolyType
      if polyType.paramCount != targs.size then
        Reporter.error(s"Expect ${polyType.paramCount} args, found = ${targs.size}", (targs.head.span | targs.last.span).toPos)
        Phrase(words = Nil)(ErrorType, fun.span | targs.last.span)
      else
        checkBounds(polyType.bounds, targs)
        val tpe = TypeOps.substTypeParams(polyType.resultType, targs.map(_.tpe))
        TypeApply(fun, targs)(tpe, fun.span)

  def checkType(tree: Tree, tp: Type)(using Reporter): Unit =
    if !Subtyping.conforms(tree.tpe, tp) then
      Reporter.error(s"Expect type ${tp.show}, found = ${tree.tpe.show}", tree.pos)

  def checkValueType(tree: Tree)(using Reporter): Unit =
    checkValueType(tree.tpe, tree.span)

  def checkValueType(tp: Type, span: Span)(using Reporter): Type =
    if !tp.isValueType then
      Reporter.error(s"Expect value type, found = ${tp.show}", span.toPos)
      ErrorType
    else
      tp

  def checkVoidOrValueType(tree: Tree)(using Reporter): Unit =
    if !tree.tpe.isVoidType then checkValueType(tree)

  def checkMutable(sym: Symbol, span: Span)(using Reporter): Unit =
    if !sym.isAllOf(Flags.Val | Flags.Mutable) then
      Reporter.error(sym.name + " is not a mutable value", span.toPos)

  def checkRecordType(word: Word, field: String)(using Reporter): Word =
    val tpe = word.tpe
    val pos = word.pos
    if !tpe.isRecordType then
      Reporter.error(s"Expect record type, found = ${tpe.show}", pos)
      Phrase(Nil)(ErrorType, word.span)
    else
      val recordType = tpe.asRecordType
      if !recordType.hasField(field) then
        Reporter.error(s"Expect field $field in record type ${tpe.show}, found none", pos)
        Phrase(Nil)(ErrorType, word.span)
      else
        word

  def commonResultType(tp1: Type, tp2: Type, span: Span)(using Reporter): Type =
    val commonTypeOpt = TypeOps.commonResultType(tp1, tp2)
    commonTypeOpt match
      case Some(tp) => tp
      case None =>
        Reporter.error(s"Cannot find common result type, tp1 = ${tp1.show}, tp2 = ${tp2.show}", span.toPos)
        ErrorType

  def tagTypes(tag: Ast.Ident, unionType: Type, typeSpan: Span)(using Reporter): Option[List[Type]] =
    if !unionType.isUnionType then
      Reporter.error(s"Expect union type, found = ${unionType.show}", typeSpan.toPos)
      None
    else
      val unionType2 = unionType.asUnionType
      if !unionType2.hasTag(tag.name) then
        Reporter.error(s"The tag ${tag.name} does not exist in union type ${unionType2.show}", tag.pos)
        None
      else
        Some(unionType2.tagType(tag.name))

  /** Explicit drop of values in if/match expressions */
  def adapt(word: Word, targetType: Type)(using Reporter): Word =
    val curType = word.tpe
    if targetType.isVoidType && curType.isValueType then
      Sast.dropValue(word)
    else
      checkType(word, targetType)
      word

  def adapt(word: Word, targetType: TargetType)(using Reporter): Word =
    def widen(): Word = word.tpe match
      case TypeRef(sym) if !sym.isType =>
        Encoded(word)(sym.info)
      case _ =>
        word

    targetType match
      case TargetType.Unknown =>
        // Don't widen if the target type is unknown
        word

      case TargetType.ValueType =>
        checkValueType(word)
        widen()

      case TargetType.ProperType =>
        checkVoidOrValueType(word)
        widen()

      case TargetType.Known(tpe) =>
        adapt(word, tpe)

      case TargetType.Member(name) =>
        checkRecordType(word, name)
