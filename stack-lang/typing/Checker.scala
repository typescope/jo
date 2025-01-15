package typing

import ast.Ast
import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import ast.Positions.*
import reporting.Reporter

import Inference.*

import scala.collection.mutable

/** Perform checks related to types  */
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

  def checkBounds(tctor: TypeTree, targs: List[TypeTree])(using Reporter, Source): Unit =
    if !tctor.tpe.isTypeLambda then
      Reporter.error(s"Expect type lambda, found = ${tctor.tpe.show}", tctor.pos)
    else
      val tl = tctor.tpe.asTypeLambda
      checkBounds(tl.bounds, targs)

  def checkBounds(bounds: List[Type], targs: List[TypeTree])(using Reporter, Source): Unit =
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

  def checkTypeApply(fun: Word, targs: List[TypeTree])(using Reporter, Source): Word =
    if !fun.tpe.isPolyType then
      Reporter.error(s"Expect a poly function type, found = ${fun.tpe.show}", fun.pos)
      Block(words = Nil)(ErrorType, fun.span | targs.last.span)
    else
      val polyType = fun.tpe.asPolyType
      if polyType.paramCount != targs.size then
        Reporter.error(s"Expect ${polyType.paramCount} args, found = ${targs.size}", (targs.head.span | targs.last.span).toPos)
        Block(words = Nil)(ErrorType, fun.span | targs.last.span)
      else
        checkBounds(polyType.bounds, targs)
        val tpe = TypeOps.substTypeParams(polyType.resultType, targs.map(_.tpe))
        TypeApply(fun, targs)(tpe, fun.span)

  def checkType(tree: Tree, tp: Type)(using Reporter, Source): Unit =
    if !Subtyping.conforms(tree.tpe, tp) then
      Reporter.error(s"Expect type ${tp.show}, found = ${tree.tpe.show}", tree.pos)

  def checkValueType(tree: Tree)(using Reporter, Source): Unit =
    checkValueType(tree.tpe, tree.pos)

  def checkValueType(tp: Type, pos: SourcePosition)(using Reporter): Type =
    if !tp.isValueType then
      Reporter.error(s"Expect value type, found = ${tp.show}", pos)
      ErrorType
    else
      tp

  def checkVoidOrValueType(tree: Tree)(using Reporter, Source): Unit =
    if !tree.tpe.isVoidType then checkValueType(tree)

  def checkMutable(sym: Symbol, pos: SourcePosition)(using Reporter): Unit =
    if !sym.isAllOf(Flags.Val | Flags.Mutable) then
      Reporter.error(sym.name + " is not a mutable value", pos)

  def checkTermMember(word: Word, member: String)(using Reporter, Source): Word =
    val tpe = word.tpe
    if tpe.hasTermMember(member) || tpe.isError then
      word
    else
      Reporter.error(s"The prefix does not contain the member $member", word.pos)
      Block(Nil)(ErrorType, word.span)

  def checkInstantiated(tvar: TypeVar, pos: SourcePosition)(using Reporter): Unit =
    if !tvar.isInstantiated then
      Reporter.error("Cannot infer a type for type variable " + tvar, pos)

  def checkCapture(sym: Symbol, pos: SourcePosition)(using sc: Namer.Scope, rp: Reporter): Unit =
    // TODO: better capture check for mutable fields of objects
    if sym.isAllOf(Flags.Val | Flags.Mutable) && !sym.owner.info.hasTermMember(sym.name) then
      // check no capture of mutable local vars
      if sc.owner.enclosingFunction != sym.enclosingFunction then
        Reporter.error("Cannot capture local mutable variable " + sym.name, pos)

  def commonResultType(tp1: Type, tp2: Type, pos: SourcePosition)(using Reporter): Type =
    val commonTypeOpt = TypeOps.commonResultType(tp1, tp2)
    commonTypeOpt match
      case Some(tp) => tp
      case None =>
        Reporter.error(s"Cannot find common result type, tp1 = ${tp1.show}, tp2 = ${tp2.show}", pos)
        ErrorType

  def tagTypes(tag: Ast.Ident, unionType: Type, typeSpan: Span)(using Reporter, Source): Option[List[Type]] =
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
  def adapt(word: Word, targetType: Type)(using Reporter, Source): Word =
    val curType = word.tpe
    if targetType.isVoidType && curType.isValueType then
      Sast.dropValue(word)
    else
      checkType(word, targetType)
      word

  def widen(word: Word): Word = word.tpe match
    case TypeRef(sym) if !sym.isType =>
      Encoded(word)(sym.info)
    case _ =>
      word

  def adapt(word: Word, targetType: TargetType)(using Reporter, Source): Word =
    val word2 =
      if word.tpe.isInvokableType then
        val invokeType = word.tpe.asInvokableType
        if invokeType.paramCount == 0 then
          Apply(word, args = Nil)(invokeType.resultType, word.span)
        else
          word
      else
        word

    targetType match
      case TargetType.Unknown =>
        // Don't widen if the target type is unknown
        word2

      case TargetType.ValueType =>
        checkValueType(word2)
        widen(word2)

      case TargetType.ProperType =>
        checkVoidOrValueType(word2)
        widen(word2)

      case TargetType.Known(tpe) =>
        adapt(word2, tpe)

      case TargetType.TermMember(name) =>
        checkTermMember(word2, name)
