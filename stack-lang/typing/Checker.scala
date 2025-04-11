package typing

import ast.Positions.*

import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import reporting.Reporter
import Inference.*

import common.Debug

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
    checking = false

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
      val polyType = fun.tpe.asProcType
      if polyType.tparamCount != targs.size then
        Reporter.error(s"Expect ${polyType.tparamCount} args, found = ${targs.size}", (targs.head.span | targs.last.span).toPos)
        Block(words = Nil)(ErrorType, fun.span | targs.last.span)
      else
        checkBounds(polyType.bounds, targs)
        val tpe = TypeOps.substTypeParams(polyType.copy(tparams = Nil), targs.map(_.tpe))
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

  def checkMutable(sym: Symbol, pos: SourcePosition)(using Reporter): Unit =
    if !sym.isMutable then
      Reporter.error(sym.name + " is not a mutable value", pos)

  def checkTermMember(word: Word, member: String)(using Reporter, Source): Word =
    val tpe = word.tpe
    if
      tpe.hasTermMember(member)
      || tpe.isTagType && tpe.asTagType.hasParam(member)
      || tpe.isError
    then
      word
    else
      Reporter.error(s"The prefix does not contain the member $member", word.pos)
      Block(Nil)(ErrorType, word.span)

  def checkInstantiated(tvar: TypeVar, pos: SourcePosition)(using Reporter): Unit =
    if !tvar.isInstantiated then
      Reporter.error("Cannot infer a type for type variable " + tvar, pos)

  def checkCapture(sym: Symbol, pos: SourcePosition)(using sc: Namer.Scope, rp: Reporter): Unit =
    if sym.isMutable && !sym.isField then
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

  def adaptIntLiteral(n: Int, origType: Type, targetType: Type)(using Reporter, Source): Type =
    val defn = Definitions.instance

    if
      targetType.refersTo(defn.Predef_Byte) && n < 128 && n >= -128
      || targetType.refersTo(defn.Predef_Char) && n < 65536 && n >= 0
      || targetType.refersTo(defn.Predef_Int)
    then
      targetType

    else
      origType

  def autoCoerceNumeric(word: Word, targetType: Type)(using Reporter, Source): Word =
    val defn = Definitions.instance
    val origType = word.tpe
    if origType.refersTo(defn.Predef_Byte) then
      if targetType.refersTo(defn.Predef_Char) then
        val byteToChar = Ident(defn.Predef_byteToChar)(word.span)
        Apply(byteToChar, word :: Nil)(targetType, word.span)
      else if targetType.refersTo(defn.Predef_Int) then
        val byteToInt = Ident(defn.Predef_byteToInt)(word.span)
        Apply(byteToInt, word :: Nil)(targetType, word.span)
      else
        Reporter.abortInternal("Unexpected numeric type " + targetType.show)

    else if origType.refersTo(defn.Predef_Char) then
      if targetType.refersTo(defn.Predef_Byte) then
        word
      else if targetType.refersTo(defn.Predef_Int) then
        val charToInt = Ident(defn.Predef_charToInt)(word.span)
        Apply(charToInt, word :: Nil)(targetType, word.span)
      else
        Reporter.abortInternal("Unexpected numeric type " + targetType.show)

    else if origType.refersTo(defn.Predef_Int) then
      word
    else
      Reporter.abortInternal("Unexpected numeric type " + origType.show)


  /** Explicit drop of values in if/match expressions */
  def adapt(word: Word, targetType: Type)(using Reporter, Source): Word =
    val curType = word.tpe
    if Subtyping.conforms(curType, targetType) then
      word

    else if targetType.isVoidType && curType.isValueType then
      word.dropValue

    else
      val unitType = Definitions.instance.UnitType

      val isNumeric =
         Definitions.instance.isNumericType(word.tpe)
         && Definitions.instance.isNumericType(targetType)

      if isNumeric && !Subtyping.conforms(word.tpe, targetType) then
        // Numeric coercion
        word match
          case Literal(Constant.Int(n)) =>
            val tp2 = adaptIntLiteral(n, word.tpe, targetType)
            val word2 = Literal(Constant.Int(n))(tp2, word.span)
            checkType(word2, targetType)
            word2

          case _ =>
            // TODO: only widening coercion is allowed for non-literals
            val word2 = autoCoerceNumeric(word, targetType)
            checkType(word2, targetType)
            word2

      else if Subtyping.conforms(unitType, targetType) then
        val unit = RecordLit(args = Nil)(unitType, word.span)
        Block(word.ensureDropValue :: unit :: Nil)(unitType, word.span)

      else
        Reporter.error(s"Expect type ${targetType.show}, found = ${curType.show}", word.pos)
        word

  def widen(word: Word): Word = word.tpe match
    case TypeRef(sym) if !sym.isType =>
      Encoded(word)(sym.info)

    case _ =>
      word

  def adapt(word: Word, targetType: TargetType)(using Reporter, Source): Word = Debug.trace("Adapting " + word.show, (_: Word).show, enable = false):

    val word2 =
      if word.tpe.isProcType then
        val procType = word.tpe.asProcType
        val resType = procType.resultType
        val isParameterlessApply =
          targetType match
            case TargetType.Fun(n) =>
              n != 0 && resType.hasApplyMethod && procType.paramCount == 0

            case _ =>
              procType.paramCount == 0

        if isParameterlessApply then
          Apply(word, args = Nil)(procType.resultType, word.span)
        else
          word

      else
        word

    targetType match
      case TargetType.Unknown =>
        // Don't widen if the target type is unknown
        word2

      case TargetType.ValueType =>
        if word2.tpe.isVoidType then
          // adapt to Unit type
          adapt(word2, Definitions.instance.UnitType)
        else
          checkValueType(word2)
          widen(word2)

      case TargetType.Known(tpe) =>
        adapt(word2, tpe)

      case TargetType.TermMember(name) =>
        checkTermMember(word2, name)

      case TargetType.Fun(n) =>
        // auto .apply insertion --- apply can be polymorphic
        if word2.tpe.hasApplyMethod then
          val memberType = word2.tpe.termMember("apply")
          Select(word2, "apply")(memberType, word2.span)

        else
          // Additional checks are performed for Apply node
          word2


      case TargetType.NamespaceMember | TargetType.ObjectMember =>
        throw new Exception("No adaptation expected: " + word)
