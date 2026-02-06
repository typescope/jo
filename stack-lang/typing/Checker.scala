package typing

import ast.Positions.*
import ast.{ Trees => Ast }

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import reporting.Reporter
import reporting.Config

import Inference.*

import common.OutOfBand
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
          case Kind.Arrow(args, to) =>
            if args.size == targs.size then
              // only simple kinded type parameters are supported
              true

            else
              val size = args.size
              Reporter.error(s"The type constructor specifies $size parameter(s), found = ${targs.size}", tctor.pos)
              false

          case Kind.Simple =>
            if targs.size != 0 then
              Reporter.error(s"The type does not take parameters", tctor.pos)
              false

            else
              true

  def checkSimpleKind(tctor: TypeTree)(using Reporter, Source): Boolean =
    tctor.tpe.kind match
      case None =>
        Reporter.error(s"Invalid type", tctor.pos)
        false

      case Some(kind) =>
        kind match
          case Kind.Arrow(args, to) =>
            val size = args.size
            Reporter.error(s"The type constructor specifies $size parameter(s), found = 0", tctor.pos)
            false

          case Kind.Simple =>
            true

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
        TypeApply(fun, targs)(span)

  def checkValueType(word: Word)(using Reporter, Source): Unit =
    checkValueType(word.tpe, word.pos)

  def checkValueType(tpt: TypeTree)(using Reporter, Source): Boolean =
    checkValueType(tpt.tpe, tpt.pos)

  def checkValueType(tp: Type, pos: SourcePosition)(using Reporter): Boolean =
    if !tp.isValueType then
      val explain = tp.kind match
        case Some(kind) => ", but found a type of kind " + kind.show
        case None => ", but a non-value type"

      Reporter.error(s"Expect value type" + explain, pos)
      false
    else
      true

  def checkMutable(sym: Symbol, pos: SourcePosition)(using Reporter): Unit =
    if !sym.isMutable then
      Reporter.error(sym.name + " is not a mutable value", pos)

  def checkAccess(target: Symbol, scopeOwner: Symbol, span: Span)(using Reporter, Source): Unit =
    target.visibleScope match
      case VisibleScope.Limit(container) =>
        if !scopeOwner.containedIn(container) then
          Reporter.error("Cannot access the private member " + target + ", limit = " + container + ", site = " + scopeOwner, span.toPos)

      case _ =>

  def checkInstantiated(tvars: TypeVars)(using Reporter, Source, Definitions): Unit =
    for tvar <- tvars.typeVars if !tvar.isInstantiated do
      // Initialize to respect invariant
      Subtyping.conforms(BottomType, tvar)
      Reporter.error("Cannot infer a type for type variable " + tvar, tvar.span.toPos)

  def visibility(defn: Ast.Def, owner: Symbol)(using rp: Reporter, so: Source): Visibility =
    def resolveEnclosingContainer(name: String): Option[Symbol] =
      if owner.isContainer && name == owner.name then Some(owner)
      else owner.ownersIterator.find(sym => sym.isContainer && sym.name == name)

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

      case _: Ast.ValDef =>
        mods.foreach:
          case _: Ast.Modifier.Private =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for value definition", mod.pos)

      case _: Ast.PatDef =>
        mods.foreach:
          case _: Ast.Modifier.Private =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for pattern definition", mod.pos)

      case _: Ast.ParamDef =>
        mods.foreach:
          case _: Ast.Modifier.Private =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for context parameter definition", mod.pos)

      case _: Ast.ClassDef =>
        mods.foreach:
          case _: Ast.Modifier.Private =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for class definition", mod.pos)

      case _: Ast.ObjectDef =>
        mods.foreach:
          case _: Ast.Modifier.Private =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for object definition", mod.pos)

      case _: Ast.InterfaceDef =>
        mods.foreach:
          case _: Ast.Modifier.Private =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for interface definition", mod.pos)

      case _: Ast.TypeDef =>
        mods.foreach:
          case _: Ast.Modifier.Private =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for type definition", mod.pos)

      case _: Ast.UnionDef =>
        mods.foreach: mod =>
          Reporter.error("The modifier " + mod.show + " is not allowed for union definition", mod.pos)

      case _: Ast.AutoDef =>
        mods.foreach: mod =>
          Reporter.error("The modifier " + mod.show + " is not allowed for auto definition", mod.pos)

      case _: Ast.Section =>
        mods.foreach:
          case _: Ast.Modifier.Private =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for section definition", mod.pos)

      case _: Ast.ExtensionDef =>
        mods.foreach:
          case _: Ast.Modifier.Private =>
          case mod =>
            Reporter.error("The modifier " + mod.show + " is not allowed for extension definition", mod.pos)

    end match

    flags

  def checkCapture(sym: Symbol, pos: SourcePosition)(using sc: Scope, rp: Reporter): Unit =
    if sym.isMutable && !sym.isField then
      // check no capture of mutable local vars
      if sc.owner.enclosingFunction != sym.enclosingFunction then
        Reporter.error("Cannot capture local mutable variable " + sym.name, pos)

  /** Check shadowing of local definitions */
  def checkShadowing(sym: Symbol)(using sc: Scope, rp: Reporter, config: Config, defn: Definitions): Unit =
    if !Config.checkShadowing.value then return

    // In the constructor, a field can shadow a constructor parameter
    // An explicit `this` check can be used to enforce selection on this.
    val outer =
      sc.outerOpt match
        case Some(outer) => outer
        case None => throw new Exception("Unexpected root scope: check shadowing can only performed for a local scope")

    given OutOfBand = new OutOfBand
    outer.resolveTermOpt(sym.name) match
      case Some(shadowed) if shadowed.isLocal && shadowed.owner == sym.owner =>
        Reporter.error(s"The definition `$sym` shadows another local definition with the same name", sym.sourcePos)

      case _ =>

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

      Autos.resolve(fun, Nil, word.span)

    else
      word

  def adaptMember(word: Word, member: String)(using sc: Scope, rp: Reporter, so: Source, defn: Definitions)
  : Word = Debug.trace(s"adapting ${word.show} to .$member", enable = false):
    val tpe = word.tpe
    if tpe.hasTermMember(member) || tpe.hasContainerMember(member) || tpe.isError then
      word

    else
      // Use Adaptation.adaptMember for consistent view handling
      Adaptation.adaptMember(word, member, sc.owner, selectMember = false) match
        case Adaptation.MemberAdaptResult.Success(adaptedWord) =>
          adaptedWord

        case _: Adaptation.MemberAdaptResult.Invisible =>
          Reporter.error(s"Found a member $member on a delegate view, but it is not visible at the location", word.pos)
          errorWord(word.span)

        case Adaptation.MemberAdaptResult.Ambiguous(candidates) =>
          // Multiple views have the member - provide helpful error message
          val views = candidates.map(_.show).mkString(", ")
          val tip = s"\nPlease disambiguate by selecting the view explicitly, e.g. .view[${candidates.head.show}].$member"
          Reporter.error(s"More than one view has the member $member, views = " + views + tip, word.pos)
          errorWord(word.span)

        case Adaptation.MemberAdaptResult.NotFound =>
          Reporter.error(s"The prefix does not contain the member $member", word.pos)
          errorWord(word.span)

  def adapt(word: Word, targetType: TargetType)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars)
  : Word = Debug.trace("Adapting " + word.show + ", tt = " + targetType.show, (_: Word).show, enable = false):
    val defn = summon[Definitions]

    targetType match
      case TargetType.Unknown =>
        // Don't widen if the target type is unknown
        word

      case TargetType.ExprItem =>
        adaptParameterless(word, targetType)

      case TargetType.VoidType =>
        val word2 = adaptParameterless(word, targetType)
        if word2.tpe.isVoidType then
          word2
        else if word2.tpe.isValueType then
          word2.dropValue
        else
          checkValueType(word2)
          word

      case TargetType.ValueType =>
        val word2 = adaptParameterless(word, targetType)
        if word2.tpe.isVoidType then
          // adapt to Unit type
          Adaptation.adapt(word2, defn.UnitType, Adaptation.NoAdapter)
        else
          checkValueType(word2)
          word2

      case TargetType.Known(tpe) =>
        val word2 = adaptParameterless(word, targetType)

        // Must choose either inference or adapation, not both
        if word2.tpe.isFullyInstantiated then
          try
            val adapter =
              if tpe.isVararg then
                val elementType = tpe.stripVarargs
                Adaptation.createVarargSpliceAdapter(elementType.adapters, sc.owner, sc)
              else
                Adaptation.createSimpleAdapter(tpe.adapters, sc.owner, sc)

            Adaptation.adapt(word2, tpe, adapter)

          catch case ex: Adaptation.AdaptionFailure =>
            // Better message for vararg splices
            val targetType =
              if tpe.isVararg then AppliedType(defn.List_type, tpe.stripVarargs :: Nil)
              else tpe

            val trialsMsg = Adaptation.formatTrials(ex.trials)
            Reporter.error(s"Expect type ${targetType.show}, found = ${word2.tpe.show}${trialsMsg}", word2.pos)
            Encoded(Block(Nil)(word2.span))(tpe)

        else
          if tvars.tryOrRevert { Subtyping.conforms(word2.tpe, tpe) } then
            word2
          else
            Reporter.error(s"Expect type ${tpe.show}, found = ${word2.tpe.show}", word2.pos)
            errorWord(word2.span)

      case TargetType.Member(name) =>
        val wordAutoApplied = adaptParameterless(word, targetType)
        adaptMember(wordAutoApplied, name)

      case TargetType.Call =>
        // Used to prevent no args adapation
        word

      case TargetType.TypeApply =>
        // Used to prevent no args adapation
        word
    end match
