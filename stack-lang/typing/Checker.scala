package typing

import ast.Positions.*
import ast.{ Trees => Ast }

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import reporting.Reporter
import Inference.*

import common.Debug

import scala.collection.mutable

/** Perform checks related to types  */
class Checker(namer: Namer):
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

  /** Check kind of a type
    *
    * Note: Do not access info of type symbols.
    */
  def checkKind(tctor: TypeTree, targs: List[TypeTree])(using Reporter, Source): Boolean =
    tctor.tpe.kind match
      case None =>
        Reporter.error(s"Invalid type constructor", tctor.pos)
        false

      case Some(kind) =>
        kind match
          case Kind.Arrow(args, to) if args.size == targs.size =>
            // only simple kinded type parameters are supported
            true

          case Kind.Arrow(args, to) =>
            val size = args.size
            Reporter.error(s"The type constructor specifies $size parameter(s), found = ${targs.size}", tctor.pos)
            false

          case Kind.Simple =>
            Reporter.error(s"The type does not take parameters", tctor.pos)
            false


  def checkBounds(tparams: List[Symbol], targs: List[TypeTree])(using Definitions, Reporter, Source): Unit =
    val subst = tparams.zip(targs.map(_.tpe)).toMap
    for (targ, tparam) <- targs.zip(tparams) do
      val argType = targ.tpe
      val TypeBound(lo, hi) = tparam.info.as[TypeBound]
      val loActual = TypeOps.substSymbols(lo, subst)
      val hiActual = TypeOps.substSymbols(hi, subst)

      if !Subtyping.conforms(argType, hiActual) then
        Reporter.error(s"Arg type ${argType.show} does not conform to bound = ${hi.show}, which expands to ${hiActual.show}", targ.pos)

      if !Subtyping.conforms(loActual, argType) then
        Reporter.error(s"Arg type ${argType.show} does not conform to bound = ${hi.show}, which expands to ${hiActual.show}", targ.pos)

  def checkTypeApply(fun: Word, targs: List[TypeTree])(using Definitions, Reporter, Source): Word =
    if !fun.tpe.isPolyType then
      Reporter.error(s"Expect a poly function type, found = ${fun.tpe.show}", fun.pos)
      Namer.errorWord(fun.span | targs.last.span)
    else
      val polyType = fun.tpe.asProcType
      if polyType.tparamCount != targs.size then
        Reporter.error(s"Expect ${polyType.tparamCount} args, found = ${targs.size}", (targs.head.span | targs.last.span).toPos)
        Namer.errorWord(fun.span | targs.last.span)
      else
        checkBounds(polyType.tparams, targs)
        val tpe = polyType.instantiate(targs.map(_.tpe))
        TypeApply(fun, targs)(tpe)

  def checkType(word: Word, tp: Type)(using Definitions, Reporter, Source): Unit =
    if !Subtyping.conforms(word.tpe, tp) then
      Reporter.error(s"Expect type ${tp.show}, found = ${word.tpe.show}", word.pos)

  def checkValueType(word: Word)(using Reporter, Source): Unit =
    checkValueType(word.tpe, word.pos)

  def checkValueType(tpt: TypeTree)(using Reporter, Source): Type =
    checkValueType(tpt.tpe, tpt.pos)

  def checkValueType(tp: Type, pos: SourcePosition)(using Reporter): Type =
    if !tp.isValueType then
      val explain = tp.kind match
        case Some(kind) => ", but found a type of kind " + kind.show
        case None => ", but a non-value type"

      Reporter.error(s"Expect value type" + explain, pos)
      ErrorType
    else
      tp

  def checkMutable(sym: Symbol, pos: SourcePosition)(using Reporter): Unit =
    if !sym.isMutable then
      Reporter.error(sym.name + " is not a mutable value", pos)

  def checkTermMember(word: Word, member: String)(using Reporter, Source, Definitions): Word =
    val tpe = word.tpe
    if tpe.hasTermMember(member) || tpe.isError then
      word
    else
      Reporter.error(s"The prefix does not contain the member $member", word.pos)
      Namer.errorWord(word.span)

  def checkInstantiated(tvar: TypeVar, pos: SourcePosition)(using Reporter): Unit =
    if !tvar.isInstantiated then
      Reporter.error("Cannot infer a type for type variable " + tvar, pos)

  def checkUnpackUsage(app: Apply, tt: TargetType)(using rp: Reporter, defn: Definitions, so: Source): Unit =
    if app.fun.refers(defn.Predef_dotdot) then
      tt match
        case TargetType.Known(tp) if tp.refers(defn.Internal_PackElemType) =>

        case _ =>
          Reporter.error(".. may only be used in unpacking an argument to a vararg function", app.pos)

  def checkModifiers(defn: Ast.Def)(using rp: Reporter, so: Source): Flags =
    val mods = defn.modifiers
    if mods.isEmpty then return Flags.empty

    var flags = Flags.empty
    mods.foreach:
      case _: Ast.Modifier.Auto =>
        flags = flags | Flags.Auto

      case mod =>
        Reporter.error("Not support the modifier " + mod.show, mod.pos)

    defn match
      case fdef: Ast.FunDef =>
        mods.foreach:
          case _: Ast.Modifier.Auto =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for function definition", mod.pos)

        if flags.is(Flags.Auto) && fdef.params.nonEmpty then
          val tip =
            if fdef.autos.isEmpty then " Do you forget the modifier auto?"
            else ""

          Reporter.warn("The function will be ignored in auto derivation as it requires non-auto arguments." + tip, fdef.params.head.pos)

      case vdef: Ast.ValDef =>
        mods.foreach:
          case _: Ast.Modifier.Auto =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for value definition", mod.pos)

      case pdef: Ast.PatDef =>
        mods.foreach: mod =>
          Reporter.error("The modifier " + mod.show + " is not allowed for pattern definition", mod.pos)

      case pdef: Ast.ParamDef =>
        // TODO: Disable auto context params for now.
        //
        // It's powerful, but also scaring --- remote binding may easily break assumptions.
        mods.foreach:
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for context parameter definition", mod.pos)

      case cdef: Ast.ClassDef =>
        mods.foreach: mod =>
          Reporter.error("The modifier " + mod.show + " is not allowed for pattern definition", mod.pos)

      case tdef: Ast.TypeDef =>
        mods.foreach: mod =>
          Reporter.error("The modifier " + mod.show + " is not allowed for type definition", mod.pos)

      case _: Ast.DataDef | _: Ast.EnumDef =>
        mods.foreach: mod =>
          Reporter.error("The modifier " + mod.show + " is not allowed for data definition", mod.pos)

      case sec: Ast.Section =>
        mods.foreach: mod =>
          Reporter.error("The modifier " + mod.show + " is not allowed for section definition", mod.pos)

      case adef: Ast.AliasDef =>
        val kind = adef.kind
        mods.foreach:
          case _: Ast.Modifier.Auto if kind == Ast.AliasKind.Def =>
          case mod =>
            Reporter.error(s"The modifier ${mod.show} is not allowed for alias $kind definition", mod.pos)
    end match

    flags

  def checkCapture(sym: Symbol, pos: SourcePosition)(using sc: Scope, rp: Reporter, defn: Definitions): Unit =
    if sym.isMutable && !sym.isField then
      // check no capture of mutable local vars
      if sc.owner.enclosingFunction != sym.enclosingFunction then
        Reporter.error("Cannot capture local mutable variable " + sym.name, pos)

  def commonResultType(tp1: Type, tp2: Type, pos: SourcePosition)(using Definitions, Reporter, TargetType): Type =
    val commonTypeOpt = Inference.commonResultType(tp1, tp2)
    commonTypeOpt match
      case Some(tp) => tp
      case None =>
        Reporter.error(s"Cannot find common result type, tp1 = ${tp1.show}, tp2 = ${tp2.show}", pos)
        ErrorType

  def adaptNoArgs(word: Word, procType: ProcType, targetType: TargetType)(using Definitions, Scope, Reporter, Source): Word =
    val isParameterlessCall =
      procType.paramCount == 0 && targetType.match
        case TargetType.Fun(n) =>
          n != 0

        case TargetType.TypeApply =>
          false

        case _ =>
          true

    if isParameterlessCall then
      val fun =
        if procType.tparams.isEmpty then word
        else namer.instantiatePoly(procType, word)
      val procType2 = fun.tpe.asProcType
      val resType = procType2.resultType

      // Always prefer type constraints from outer scope if present
      for tp <- targetType.knownType do Subtyping.conforms(resType, tp)

      val autos = namer.autoResolver.derive(procType2, word.span)
      Apply(fun, args = Nil, autos)

    else
      word


  def adapt(word: Word, targetType: TargetType)(using Definitions, Scope, Reporter, Source): Word = Debug.trace("Adapting " + word.show, (_: Word).show, enable = false):
    val defn = summon[Definitions]

    val word2 =
      if word.tpe.isProcType then
        val procType = word.tpe.asProcType
        adaptNoArgs(word, procType, targetType)

      else if word.tpe.isTermRef then
        val ref = word.tpe.as[RefType]
        val sym = ref.symbol
        if
          sym.isContainer
          && ref.hasTermMember(sym.name)
          && !targetType.isInstanceOf[TargetType.TermMember]
          && !targetType.isInstanceOf[TargetType.TypeMember]
        then
          val memSym = sym.termMember(sym.name).dealias
          // The selection might need parameterless call adaption
          return adapt(Ident(memSym)(word.span), targetType)
        else
          word

      else
        word

    targetType match
      case TargetType.Unknown =>
        // Don't widen if the target type is unknown
        word2

      case TargetType.VoidType =>
        if word2.tpe.isVoidType then
          word2
        else if word2.tpe.isValueType then
          word2.dropValue
        else
          checkValueType(word2)
          word2

      case TargetType.ValueType =>
        if word2.tpe.isVoidType then
          // adapt to Unit type
          TreeOps.adapt(word2, defn.UnitType)
        else
          checkValueType(word2)
          word2

      case TargetType.Known(tpe) =>
        try
          val wordAdapted = TreeOps.adapt(word2, tpe)
          checkType(wordAdapted, tpe)
          wordAdapted

        catch case ex: TreeOps.AdaptionFailure =>
          Reporter.error(s"Expect type ${tpe.show}, found = ${word2.tpe.show}", word2.pos)
          word2

      case TargetType.TermMember(name) =>
        checkTermMember(word2, name)

      case TargetType.TypeMember(name) =>
        // checked in namer
        word2

      case TargetType.Fun(n) =>
        // The `.apply` insertion happens at the transform for `Apply`.
        // It ensures that in `Apply(fun, args)` the fun is an ident or select.
        word2

      case TargetType.TypeApply =>
        // Used to prevent no args adapation
        word2
