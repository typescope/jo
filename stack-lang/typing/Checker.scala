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

/** Perform checks related to types  */
object Checker:
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

  def checkTypeApply(fun: Word, targs: List[TypeTree], span: Span)(using Definitions, Reporter, Source): Word =
    if !fun.tpe.isPolyType then
      Reporter.error(s"Expect a poly function type, found = ${fun.tpe.show}", fun.pos)
      errorWord(span)
    else
      val polyType = fun.tpe.asProcType
      if polyType.tparamCount != targs.size then
        Reporter.error(s"Expect ${polyType.tparamCount} args, found = ${targs.size}", (targs.head.span | targs.last.span).toPos)
        errorWord(fun.span | targs.last.span)
      else
        checkBounds(polyType.tparams, targs)
        val tpe = polyType.instantiate(targs.map(_.tpe))
        TypeApply(fun, targs)(tpe, span)

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

  def checkAccess(target: Symbol, scopeOwner: Symbol, span: Span)(using Reporter, Source): Unit =
    target.visibleScope match
      case VisibleScope.Limit(container) =>
        if !scopeOwner.containedIn(container) then
          Reporter.error("Cannot access the private member " + target, span.toPos)

      case _ =>

  def checkTermMember(word: Word, member: String)(using Reporter, Source, Definitions): Word =
    val tpe = word.tpe
    if tpe.hasTermMember(member) || tpe.isError then
      word
    else
      Reporter.error(s"The prefix of the type ${tpe.show} does not contain the member $member", word.pos)
      errorWord(word.span)

  def checkInstantiated(tvars: TypeVars)(using Reporter, Source): Unit =
    for tvar <- tvars.typeVars if !tvar.isInstantiated do
      Reporter.error("Cannot infer a type for type variable " + tvar, tvar.span.toPos)

  def visibility(defn: Ast.Def, owner: Symbol)(using rp: Reporter, so: Source): Visibility =
    def resolveEnclosingContainer(name: String): Option[Symbol] =
      if name == owner.name then Some(owner)
      else owner.ownersIterator.find(_.name == name)

    defn.modifiers.find(_.isPrivate) match
      case Some(Ast.Modifier.Private(qualOpt)) =>
        qualOpt match
           case Some(qual) =>
             resolveEnclosingContainer(qual.name) match
               case Some(symbol) =>
                 val visibility = Visibility.Private(symbol)
                 if !owner.visibleScope.contains(VisibleScope.Limit(symbol)) then
                   Reporter.error("Visibility cannot be greater than parent", qual.pos)
                   Visibility.Default
                 else
                   visibility

               case None =>
                 Reporter.error("Cannot find an enclosing container named " + qual.name, qual.pos)
                 Visibility.Default

           case None =>
             Visibility.Private(owner)

      case _ => Visibility.Default

  def checkModifiers(defn: Ast.Def)(using rp: Reporter, so: Source): Flags =
    val mods = defn.modifiers
    if mods.isEmpty then return Flags.empty

    var flags = Flags.empty

    defn match
      case fdef: Ast.FunDef =>
        mods.foreach:
          case _: Ast.Modifier.Private =>

          case _: Ast.Modifier.Defer =>
            flags = flags | Flags.Defer

            // Deferred function with default implementation
            if !fdef.body.isEmptyBlock then
              flags = flags | Flags.Default

      case vdef: Ast.ValDef =>
        mods.foreach:
          case _: Ast.Modifier.Private =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for value definition", mod.pos)

      case pdef: Ast.PatDef =>
        mods.foreach:
          case _: Ast.Modifier.Private =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for pattern definition", mod.pos)

      case pdef: Ast.ParamDef =>
        mods.foreach:
          case _: Ast.Modifier.Private =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for context parameter definition", mod.pos)

      case cdef: Ast.ClassDef =>
        mods.foreach:
          case _: Ast.Modifier.Private =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for class definition", mod.pos)

      case tdef: Ast.TypeDef =>
        mods.foreach:
          case _: Ast.Modifier.Private =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for type definition", mod.pos)

      case _: Ast.DataDef | _: Ast.EnumDef =>
        mods.foreach: mod =>
          Reporter.error("The modifier " + mod.show + " is not allowed for data definition", mod.pos)

      case sec: Ast.Section =>
        mods.foreach:
          case _: Ast.Modifier.Private =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for section definition", mod.pos)

      case adef: Ast.AliasDef =>
        val kind = adef.kind
        mods.foreach:
          case _: Ast.Modifier.Private =>
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

  def adaptParameterless(word: Word, targetType: TargetType)(using Definitions, Scope, Reporter, Source, TypeVars): Word =
    if !word.tpe.isProcType then return word

    val procType = word.tpe.asProcType
    val isParameterlessCall = procType.paramCount == 0

    if isParameterlessCall then
      val fun =
        if procType.tparams.isEmpty then word
        else TreeOps.instantiatePoly(procType, word)
      val procType2 = fun.tpe.asProcType
      val resType = procType2.resultType

      // Constrain result type
      Inference.conditionalInstantiate(resType, targetType)

      Autos.resolve(fun, args = Nil, havings = Nil, word.span)

    else
      word

  def adapt(word: Word, targetType: TargetType)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars)
  : Word = Debug.trace("Adapting " + word.show + ", tt = " + targetType.show, (_: Word).show, enable = false):
    val defn = summon[Definitions]

    // Adapt Container selection List -> List.List
    val word2: Word =
      if word.tpe.isTermRef then
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
          Ident(memSym)(word.span)
        else
          word

      else
        word

    targetType match
      case TargetType.Unknown =>
        // Don't widen if the target type is unknown
        word2

      case TargetType.ExprItem =>
        adaptParameterless(word2, targetType)

      case TargetType.VoidType =>
        val word3 = adaptParameterless(word2, targetType)
        if word3.tpe.isVoidType then
          word3
        else if word3.tpe.isValueType then
          word3.dropValue
        else
          checkValueType(word3)
          word3

      case TargetType.ValueType =>
        val word3 = adaptParameterless(word2, targetType)
        if word3.tpe.isVoidType then
          // adapt to Unit type
          Adaptation.adapt(word3, defn.UnitType, Adaptation.NoAdapter)
        else
          checkValueType(word3)
          word3

      case TargetType.Known(tpe, adapter) =>
        val word3 = adaptParameterless(word2, targetType)

        try
          val wordAdapted = Adaptation.adapt(word3, tpe, adapter)
          checkType(wordAdapted, tpe)
          wordAdapted

        catch case ex: Adaptation.AdaptionFailure =>
          val trialsMsg = Adaptation.formatTrials(ex.trials)
          Reporter.error(s"Expect type ${tpe.show}, found = ${word3.tpe.show}${trialsMsg}", word3.pos)
          Encoded(Block(Nil)(word3.span))(tpe)

      case TargetType.TermMember(name) =>
        val wordAutoApplied = adaptParameterless(word, targetType)
        checkTermMember(wordAutoApplied, name)

      case TargetType.TypeMember(name) =>
        // checked in namer
        word2

      case TargetType.Call =>
        // The `.apply` insertion happens at the transform for `Apply`.
        // It ensures that in `Apply(fun, args)` the fun is an ident or select.
        word2

      case TargetType.TypeApply =>
        // Used to prevent no args adapation
        word2
    end match
