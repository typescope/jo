package typing

import ast.{ Trees => Ast }
import ast.Naming
import ast.Positions.*

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Denotations.*
import sast.Types.*

import common.Debug
import common.KeyProps
import common.OutOfBand

import reporting.Reporter
import reporting.Config

import Inference.*
import Namer.{ lazyValue, lazyDef, withDefn }

import scala.collection.mutable

/**
  * The namer handles name resolution and desugaring.
  *
  * It converts ASTs to Semantic ASTs.
  *
  * All aliases are resolved thus later phase may assume there are no aliases
  * any more.
  *
  * This phase only deals with type checking. Effect inference are only hooked
  * but not performed.
  *
  * It is important to NOT trigger effect inference and effect check during type
  * checking.
  */
class Namer(using Config) extends Applications with SelectionTyper:
  val patternTyper = PatternTyper(this)
  val exprTyper = new ExprTyper(this)

  def transform
      (units: List[Ast.FileUnit], rootNameTable: NameTable, worldScope: Scope)
      (using defnLazy: Definitions.Lazy, rp: Reporter)
  : List[FileUnit] = Checks.delayed:

    given SymbolIndex = defnLazy.index

    val delayedImports = new mutable.ArrayBuffer[() => Unit]
    val delayedUnits = new mutable.ArrayBuffer[(() => FileUnit, Source)]

    for unit <- units do
      given source: Source = unit.source

      val unitSym = resolveNamespace(unit.qualid, rootNameTable)
      val memberTable = unitSym.nameTable

      // Default imports should be treated as just before normal imports
      val importScope: Scope = worldScope.fresh(unitSym)
      val defsScope: Scope = importScope.fresh(unitSym, memberTable)

      val delayedDefs =
        given Scope = defsScope
        index(unit.defs)

      val imports = new mutable.ArrayBuffer[Symbol]

      delayedImports += { () =>
        // handle imports after indexing members
        for imp <- unit.imports do
          imports ++= Imports.doImport(imp.qualid, imp.alias.map(_.name), importScope, rootNameTable)
      }

      delayedUnits += (() => withDefn:
        val defs = for delayed <- delayedDefs.toList yield delayed.force()
        FileUnit(unitSym, imports.toList, defs, source)
      ) -> source
    end for

    delayedImports.foreach(_.apply())

    val results =
      for (makeUnit, source) <- delayedUnits
      yield makeUnit() <| source.file

    results.toList

  /** Resolve namespace and create intermediate namespace on demand
    *
    * It also checks redefinition of namespace.
    */
  def resolveNamespace
      (qualid: Ast.RefTree, rootNameTable: NameTable)
      (using rp: Reporter, so: Source)
  : Symbol =

    def check(sym: Symbol): Symbol =
      val name = sym.name
      val pos = sym.sourcePos
      if sym.isNamespace && !sym.isAlias then
        sym

      else
        rp.error(s"The $name is already defined as a member at $pos, ", qualid.pos)
        val flags = Flags.NSpace
        ContainerSymbol.create(sym.name, new NameTable, flags, Visibility.Default, sym.owner, qualid.pos)

    qualid match
      case Ast.Select(qual, name) =>
        assert(qual.isInstanceOf[Ast.RefTree], "Unexpected qualid = " + qualid)
        val nsSym = resolveNamespace(qual.asInstanceOf[Ast.RefTree], rootNameTable)

        assert(nsSym.isNamespace, "Not a namespace " + nsSym)
        val nameTable = nsSym.nameTable

        nameTable.resolveContainer(name) match
          case Some(sym) => check(sym)

          case None =>
            val flags = Flags.NSpace
            val sym = ContainerSymbol.create(name, new NameTable, flags, Visibility.Default, nsSym, qualid.pos)
            nameTable.define(sym)
            sym

      case Ast.Ident(name) =>
        rootNameTable.resolveContainer(name) match
          case None =>
            val flags = Flags.NSpace
            val sym = ContainerSymbol.create(name, new NameTable, flags, Visibility.Default, owner = null, qualid.pos)
            rootNameTable.define(sym)
            sym

          case Some(sym) => check(sym)


  private def index
      (defs: List[Ast.Def])
      (using defnLazy: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : List[LazyDef[Def]] =

    val delayedDefs = new mutable.ArrayBuffer[LazyDef[Def]]

    // Synthesize definitions
    val desugaredDefs = Desugaring.synthesize(defs)

    for
      defn <- desugaredDefs
      delayedDef <- index(defn)
    do
      // The ContainerInfo is built from the NameTable of current scope. This
      // way, by entering once the name can be access in two different ways in
      // the current context.
      sc.define(delayedDef.symbol)
      delayedDefs += delayedDef

    delayedDefs.toList

  private def index
      (defn: Ast.Def)
      (using defnLazy: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : List[LazyDef[Def]] =

    defn match
      case fdef: Ast.FunDef =>
        transformFunDef(fdef, Flags.Fun, Effects.Policy.Infer) :: Nil

      case tdef: Ast.TypeDef =>
        transformTypeDef(tdef) :: Nil

      case pdef: Ast.ParamDef =>
        transformParamDef(pdef) :: Nil

      case pdef: Ast.PatDef =>
        patternTyper.transformPatDef(pdef) :: Nil

      case cdef: Ast.ClassDef =>
        transformClassDef(cdef) :: Nil

      case idef: Ast.InterfaceDef =>
        transformInterfaceDef(idef) :: Nil

      case section: Ast.Section =>
        transformSection(section) :: Nil

      case _: Ast.ExtensionDef =>
        Reporter.error("[Internal Error] Extension definition should have been desugared", defn.pos)
        Nil

      case _: Ast.UnionDef  =>
        Reporter.error("[Internal Error] Union definition should have been desugared", defn.pos)
        Nil

      case _: Ast.ObjectDef  =>
        Reporter.error("[Internal Error] Object definition should have been desugared", defn.pos)
        Nil

      case vdef: Ast.ValDef =>
        Reporter.error("Unexpected top-level value definitions", vdef.pos)
        Nil

      case adef: Ast.AutoDef =>
        Reporter.error("Auto definitions are not allowed at top-level", adef.pos)
        Nil

      case adef: Ast.AnnotationDef =>
        transformAnnotationDef(adef) :: Nil
    end match
  end index

  extension (word: Word)
    def adapt(using tt: TargetType, defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars): Word =
      Checker.adapt(word, tt)

  def transform(word: Ast.Word)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars, cs: ControlScope)
  : Word = Debug.trace(s"Typing ${word.show}, owner = ${sc.owner}, tt = ${tt.show}", (_: Word).show, enable = false) {

    word.testKey(Namer.TypedWord) match
    case Some(typedWord) => typedWord.adapt
    case None =>
      word match
      case lit: Ast.IntLit =>
        NumericTyper.typeIntLit(lit).adapt

      case lit: Ast.FloatLit =>
        NumericTyper.typeFloatLit(lit).adapt

      case lit: Ast.CharLit =>
        NumericTyper.typeCharLit(lit).adapt

      case Ast.BoolLit(v) =>
        Literal(Constant.Bool(v))(defn.BoolType, word.span).adapt

      case Ast.StringLit(v) =>
        Literal(Constant.String(v))(defn.StringType, word.span).adapt

      case Ast.RegexLit(pattern, flags, _) =>
        val fun = Ident(defn.Regex_compileValidated)(word.span)
        val patternArg = Literal(Constant.String(pattern))(defn.StringType, word.span)
        val flagsArg = Literal(Constant.String(flags))(defn.StringType, word.span)
        fun.appliedTo(patternArg, flagsArg).adapt

      case Ast.InterpolatedString(parts) =>
        transformInterpolatedString(parts, word.span).adapt

      case _: Ast.This =>
        transformIdent(Ast.Ident("this")(word.span))

      case id: Ast.Ident =>
        transformIdent(id)

      case select: Ast.Select =>
        transformSelect(select)

      case Ast.TypeAscribe(expr, tpt) =>
        val tpt2 = Checks.eager { transformValueType(tpt) }
        val expr2 =
          given TargetType = TargetType.Known(tpt2.tpe)
          transform(expr)
        Encoded(Block(expr2 :: Nil)(word.span))(tpt2.tpe).adapt

      case lambda: Ast.Lambda =>
        transformLambda(lambda).adapt

      case Ast.Fence(phrase) =>
        transform(phrase)

      case app: Ast.Apply =>
        transformCall(app)

      case newExpr: Ast.New =>
        transformNew(newExpr)

      case list: Ast.ListLit =>
        transformListLit(list)

      case bracketApply: Ast.BracketApply =>
        transformBracketApply(bracketApply)

      case isExpr: Ast.IsExpr =>
        given flowScope: FlowScope = new FlowScope(sc)
        transformIsExpr(isExpr).adapt

      case infixCall: Ast.InfixCall =>
        // Nested infix call come from another non-flow infix call or desugaring
        // of operator calls.
        //
        // println 3 + 5
        transformInfixCall(infixCall)

      case infixCall: Ast.InfixOperatorCall =>
        // Nested infix call come from another non-flow infix call
        // For `set + 5 + 3 * 6`, we may encounter `3 * 6` here

        given flowScope: FlowScope = new FlowScope(sc)
        FlowTyper.transformInfixOperatorCall(infixCall, this)

      case prefixCall: Ast.PrefixOperatorCall =>
        // Nested infix call come from another non-flow infix call
        // For `5 | ~6`, we may encounter `~6` here

        given flowScope: FlowScope = new FlowScope(sc)
        FlowTyper.transformPrefixOperatorCall(prefixCall, this)

      case expr: Ast.Expr  =>
        given flowScope: FlowScope = new FlowScope(sc)
        FlowTyper.transformExpr(expr, this)

      case Ast.With(expr, args) =>
        val exprSast = transform(expr)
        val argsSast =
          for arg <- args yield Inference.freshIsolate:
            transformWithArg(arg)
        With(exprSast, argsSast)

      case Ast.Allow(expr, params) =>
        val exprSast = transform(expr)
        val paramRefs =
          for
            param <- params
          yield
            transformParamRef(param)

        Allow(exprSast, paramRefs)

      case ifte: Ast.If =>
        transformIf(ifte).adapt

      case _while: Ast.While =>
        transformWhile(_while).adapt

      case ret: Ast.Return =>
        transformReturn(ret).adapt

      case brk: Ast.Break =>
        transformBreak(brk).adapt

      case cont: Ast.Continue =>
        transformContinue(cont).adapt

      case _for: Ast.For =>
        transformFor(_for).adapt

      case assign: Ast.Assign =>
        transformAssign(assign).adapt

      case patmat: Ast.Match =>
        patternTyper.transformMatch(patmat).adapt

      case patValDef: Ast.PatValDef =>
        patternTyper.transformPatValDef(patValDef).adapt

      case vdef: Ast.ValDef =>
        if vdef.name == "_" then
          val rhs =
            Inference.freshIsolate:
              given TargetType = TargetType.VoidType
              given Scope = sc.fresh()
              transform(vdef.rhs)
          Block(rhs :: Nil)(vdef.span).adapt
        else
          val vdef2 = transformLocalValDef(vdef)
          sc.define(vdef2.symbol)
          Checker.checkShadowing(vdef2.symbol)
          vdef2.adapt

      case adef: Ast.AutoDef =>
        val adef2 = transformLocalAutoDef(adef)
        sc.define(adef2.symbol)
        adef2.adapt

      case fdef: Ast.FunDef => Checks.delayed: // checks after forcing

        val delayedDef = transformFunDef(fdef, Flags.Fun, Effects.Policy.Infer)
        // A function is available for checking its rhs
        val symbol = delayedDef.symbol
        sc.define(symbol)
        Checker.checkShadowing(symbol)

        delayedDef.force().adapt

      case pdef: Ast.PatDef => Checks.delayed: // checks after forcing
        val delayedDef = patternTyper.transformPatDef(pdef)
        // A pattern predicate is available for checking its rhs
        sc.define(delayedDef.symbol)
        delayedDef.force().adapt

      case block: Ast.Block =>
        transformBlock(block)
    }

  def transformIdent(id: Ast.Ident)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars): Word =
    val name = id.name

    def tryTermName(): Word =
      given oob: OutOfBand = new OutOfBand
      val sym = sc.resolveTerm(name, id.pos)
      handlePrefix(sym, oob)

    def handlePrefix(sym: Symbol, oob: OutOfBand): Word =
      oob.testKey(Scope.PrefixKey) match
        case Some(prefix) =>
          // Normalize SAST
          val qual = Ident(prefix)(id.span.point)
          Select(qual, sym.name)(id.span)

        case _ =>
          Checker.checkCapture(sym, id.pos)
          Ident(sym)(id.span)

    tt match
      case TargetType.Member =>
        given oob: OutOfBand = new OutOfBand
        sc.resolveTermOpt(name) match
          case Some(sym) if sym.tpe.isValueType =>
            // Prefer values
            handlePrefix(sym, oob).adapt

          case _ =>
            sc.resolveContainerOpt(name) match
              case Some(sym) => Ident(sym)(id.span).adapt

              case None =>
                tryTermName().adapt

      case _ =>
        tryTermName().adapt

  def transformSelect(word: Ast.Select)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars, cs: ControlScope): Word =
    val Ast.Select(qual, name) = word

    val qualTyped =
      given TargetType = TargetType.Member
      transform(qual)

    if qualTyped.tpe.isError then return errorWord(word.span)

    def tryTermMember(): Word =
      val selectReporter = rp.fresh(buffer = true)
      val selected =
        given Reporter = selectReporter
        resolveTypedSelect(qualTyped, name, word.span, allowAdapt = true)

      if !selectReporter.hasErrors then
        selected

      else if tt != TargetType.Call then
        tryDynamicSelect(qualTyped, name, word.span) match
          case Some(result) =>
            result

          case None =>
            selectReporter.commit(rp)
            errorWord(word.span)

      else
        selectReporter.commit(rp)
        errorWord(word.span)

    qualTyped match
      case Ident(sym) if sym.isContainer && tt == TargetType.Member =>
        sym.nameTable.resolveContainer(name) match
          case Some(memberSym) =>
            Checker.checkAccess(memberSym, sc.owner, word.span)
            Checker.adapt(Ident(memberSym.dealias)(word.span), tt)

          case None =>
            Checker.adapt(tryTermMember(), tt)

      case _ =>
        Checker.adapt(tryTermMember(), tt)

  def transformBracketApply(word: Ast.BracketApply)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars, cs: ControlScope)
  : Word =
    val Ast.BracketApply(subject, args) = word

    val subjectTyped =
      // No parameterless adaptation for TypeApply
      given TargetType = TargetType.TypeApply
      transform(subject)

    if subjectTyped.tpe.isError then
      errorWord(word.span)

    else if subjectTyped.tpe.isPolyType then
      Desugaring.toTypeTrees(args) match
        case Right(targs) => Checks.eager:
          val targs2 = targs.map(targ => transformValueType(targ))
          Checker.checkTypeApply(subjectTyped, targs2, word.span).adapt

        case Left(badArg) =>
          Reporter.error("Expected a type argument, found an expression", badArg.pos)
          errorWord(word.span)

    else
      val getReporter = rp.fresh(buffer = true)
      val fun =
        given Reporter = getReporter
        resolveTypedSelect(subjectTyped, "get", subject.span, allowAdapt = true)

      if !getReporter.hasErrors && !fun.tpe.isError then
        applyResolvedFun(fun, args, word.span)

      else
        tryDynamicGet(subjectTyped, args, word.span) match
          case Some(result) =>
            result

          case None =>
            getReporter.commit(rp)
            errorWord(word.span)

  /** Resolve a container by a fully qualified name */
  def resolveContainer(qualid: Ast.RefTree)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): Option[Symbol] = Debug.trace("Resolving " + qualid.show, enable = false):
    qualid match
      case Ast.Select(qual, name) =>
        val prefix = qual.asInstanceOf[Ast.RefTree]
        val symOpt = resolveContainer(prefix)

        symOpt match
          case Some(sym) =>
            sym.nameTable.resolveContainer(name) match
              case res @ Some(sym) =>
                Checker.checkAccess(sym, sc.owner, qualid.span)
                res

              case _ =>
                Reporter.error(s"`$name` is not a member of ${prefix.name}", qualid.pos)
                None

          case _ => None
        end match

      case Ast.Ident(name) =>
        // path needs to be fully qualified
        sc.resolveContainerOpt(name) match
          case res @ Some(_) => res
          case None =>
            Reporter.error(s"`$name` is not found", qualid.pos)
            None
        end match

  def resolveQualid
      (qualid: Ast.RefTree, universe: Universe)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : Option[Symbol] = Debug.trace(s"resolving qualid $qualid", enable = false):

    qualid match
      case Ast.Ident(name) =>
        given oob: OutOfBand = new OutOfBand
        sc.resolveOpt(name, universe) match
          case None =>
            Reporter.error(s"Undefined $universe name: " + name, qualid.pos)
            None

          case res @ Some(_) =>
            if oob.hasKey(Scope.PrefixKey) then
              Reporter.error(s"A top-level name expected, but '$name' is not top-level", qualid.pos)
              None
            else
              res

      case Ast.Select(qual, name) =>
        resolveContainer(qual.asInstanceOf[Ast.RefTree]).flatMap: sym =>
          sym.nameTable.resolve(name, universe) match
            case res @ Some(target) =>
              Checker.checkAccess(target, sc.owner, qualid.span)
              res

            case None =>
              Reporter.error(s"`$name` is not a $universe member of $sym", qualid.pos)
              None

  def transformListLit(listLit: Ast.ListLit)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars, cs: ControlScope)
  : Word =
    def getConstructor(default: Symbol): Symbol =
      tt match
        case TargetType.Known(expectedType) =>
          expectedType.widen.dealias match
            case AppliedType(sym, _) if sym == defn.ArrayBuffer_type =>
              defn.ArrayBuffer_def

            case _ =>
              default

        case _ =>
          default

    val constructor = getConstructor(defn.List_def)
    val ref = Ident(constructor)(listLit.span)
    listLit.addKey(Namer.TypedWord, ref)
    transform(Ast.Apply(listLit, listLit.words)(listLit.span))

  def transformBlock(block: Ast.Block)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars, cs: ControlScope)
  : Word =

    val phrases = block.phrases
    val words =
      given Scope = sc.fresh()
      for (phrase, i) <- phrases.zipWithIndex yield
        if i == phrases.size - 1 then
          transform(phrase)

        else
          given TargetType = TargetType.VoidType
          Inference.freshIsolate:
            transform(phrase)

    if words.isEmpty then
      Checker.adapt(Block(Nil)(block.span), tt)

    else
      Block(words)(block.span)

  /** Handles new Foo[T](arg1, arg2, ...) */
  def transformNew(newExpr: Ast.New)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars, cs: ControlScope)
  : Word =

    val classTree = Checks.eager:
      transformType(newExpr.classType)

    if !classTree.tpe.isClassType then
      Reporter.error("A class type expected for new expressions", classTree.pos)
      return errorWord(newExpr.span)

    val classSym = classTree.tpe.classSymbol

    val instanceType =
      if classTree.tpe.kind != Some(Kind.Simple) then
        val tparams = classSym.classInfo.tparams
        val tvars = for tparam <- tparams yield TypeVar(tparam.name, classTree.span)
        val instanceType = AppliedType(classSym, tvars)

        // Conditionally apply context instantiation
        Inference.conditionalInstantiate(instanceType, tt)

        instanceType

      else
        classTree.tpe

    instanceType.getTermMember(Names.Constructor) match
      case None =>
        Reporter.error("The class cannot be instantiated as it does not have a constructor.", newExpr.pos)
        errorWord(newExpr.span)

      case Some(tp) =>
        assert(tp.is[RefType], "TermRef expected for class member, found = " + tp)
        val refType = tp.as[RefType]

        val cls = refType.symbol.owner
        if cls.is(Flags.Object) then
          Reporter.error("Cannot create new instance of the object " + cls, newExpr.pos)
          errorWord(newExpr.span)

        else
          assert(refType.isProcType, "ProcType expected for constructor, found = " + refType.info)
          val procType = refType.asProcType

          assert(procType.tparams.isEmpty, "Constructor should not take type parameters, found = " + procType)

          val span = classTree.span
          val newInstance = New(TypeTree(instanceType)(span))(span)

          newExpr.addKey(Namer.TypedWord, newInstance)
          val ctorSelect = Ast.Select(newExpr, Names.Constructor)(span)
          val ctorCall = Ast.Apply(ctorSelect, newExpr.args)(newExpr.span)
          transformCall(ctorCall)

  def transformAssign(assign: Ast.Assign)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars, cs: ControlScope): Word =
    val Ast.Assign(lhs, rhs) = assign

    lhs match
      case id: Ast.Ident =>
        given oob: OutOfBand = new OutOfBand
        val sym = sc.resolveTerm(id.name, id.pos)

        Checker.checkMutable(sym, id.pos)

        val rhs2 =
          given TargetType = TargetType.Known(sym.tpe)
          Inference.freshIsolate:
            transform(rhs)

        if sym.isField then
          // Normalize SAST
          val qual = Ident(oob.getKey(Scope.PrefixKey))(id.span)
          val lhs2 = Select(qual, sym.name)(lhs.span)
          FieldAssign(lhs2, rhs2)

        else
          val id = Ident(sym)(lhs.span)
          Checker.checkCapture(sym, id.pos)
          Assign(id, rhs2, isDefine = false)

      case Ast.Select(qual, name) =>
        val qualTyped =
          given TargetType = TargetType.Member
          Inference.freshIsolate:
            transform(qual)

        if qualTyped.tpe.isError then return errorWord(assign.span)

        if qualTyped.tpe.hasTermMember(name) then
          qualTyped.tpe.getTermMember(name) match
            case Some(ref: RefType) if ref.symbol.isMutable =>
              Checks.eager:
                Checker.checkAccess(ref.symbol, sc.owner, lhs.span)

              val lhs2 = Select(qualTyped, name)(lhs.span)

              val rhs2 = Inference.freshIsolate:
                given TargetType = TargetType.Known(ref.widenTermRef)
                transform(rhs)

              FieldAssign(lhs2, rhs2)

            case _ =>
              Reporter.error(s"The member $name is immutable", lhs.pos)
              errorWord(assign.span)

        else
          given TargetType = TargetType.VoidType
          tryDynamicUpdate(qualTyped, name, rhs, assign.span) match
            case Some(result) =>
              result

            case None =>
              Reporter.error(s"The prefix does not contain the member $name", lhs.pos)
              errorWord(assign.span)

      case Ast.BracketApply(subject, args) =>
        val subjectTyped =
          given TargetType = TargetType.ValueType
          Inference.freshIsolate:
            transform(subject)

        if subjectTyped.tpe.isError then
          errorWord(assign.span)

        else
          val setReporter = rp.fresh(buffer = true)
          val fun =
            given Reporter = setReporter
            Inference.freshIsolate:
              resolveTypedSelect(subjectTyped, "set", subject.span, allowAdapt = false)

          if !setReporter.hasErrors then
            given TargetType = TargetType.VoidType
            Inference.freshIsolate:
              applyResolvedFun(fun, args :+ rhs, assign.span)

          else
            given TargetType = TargetType.VoidType
            tryDynamicSet(subjectTyped, args, rhs, assign.span) match
              case Some(result) =>
                result

              case None =>
                setReporter.commit(rp)
                errorWord(assign.span)

      case _ =>
        Reporter.error("Unexpected left-side of assignment", assign.lhs.pos)
        errorWord(assign.span)

  private def transformParamRef(ref: Ast.RefTree)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : Ident =

    val paramOpt: Option[Symbol] = resolveQualid(ref, Universe.Term)

    def errorSymbol: Symbol =
      TermSymbol.create(ref.name, ErrorType, Flags.Synthetic, Visibility.Default, sc.owner, ref.pos)

    val paramSym =
      paramOpt match
        case Some(sym) =>
          if sym.is(Flags.Context) then
            sym

          else
            Reporter.error("A reference to a contextual parameter expected, found = " + sym, ref.pos)
            errorSymbol

        case None =>
          errorSymbol

    Ident(paramSym)(ref.span)

  private def transformWithArg(arg: Ast.WithArg)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, cs: ControlScope)
  : Assign =

    val paramRef = transformParamRef(arg.paramRef)

    val rhs =
      given TargetType =
        if paramRef.tpe.isError then TargetType.ValueType
        else TargetType.Known(paramRef.symbol.tpe)

      Inference.freshIsolate:
        transform(arg.rhs)

    Assign(paramRef, rhs, isDefine = false)

  private def transformInterpolatedString(parts: List[Ast.Word], span: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, cs: ControlScope)
  : Word = Inference.freshIsolate:

    // Type check each part and build concatenation
    val typedParts = parts.map: part =>
      part match
        case Ast.StringLit(value) =>
          // String literals are already typed as String
          Literal(Constant.String(value))(defn.StringType, part.span)

        case expr =>
          // Type check the interpolation expression
          Inference.freshIsolate:
            given TargetType = TargetType.Known(defn.StringLikeType)
            transform(expr)

    // Build concatenation using the + method on String
    typedParts match
      case Nil =>
        // Empty interpolated string
        Literal(Constant.String(""))(defn.StringType, span)
      case head :: Nil =>
        // Single part
        head
      case head :: tail =>
        // Multiple parts - concatenate using +
        tail.foldLeft(head) { (lhs, rhs) =>
          // Build: lhs + rhs using select and appliedTo
          lhs.select("+").appliedTo(rhs)
        }

  private def transformWhile(word: Ast.While)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, cs: ControlScope): Word =
    val Ast.While(cond, body) = word
    val flowScope = new FlowScope(sc)
    val loopEnd = TermSymbol.create(
      "_loop_end",
      VoidType,
      Flags.Label | Flags.Synthetic,
      Visibility.Default,
      sc.owner,
      word.pos
    )

    val loopBody = TermSymbol.create(
      "_loop_body",
      VoidType,
      Flags.Label | Flags.Synthetic,
      Visibility.Default,
      sc.owner,
      word.pos
    )

    val loopFrame = new LoopFrame(loopEnd, loopBody)

    val cond2 =
      given FlowScope = flowScope
      given TargetType = TargetType.Known(defn.BoolType)
      Inference.freshIsolate:
        FlowTyper.transformFlow(cond, this)

    val body2 =
      given TargetType = TargetType.VoidType
      given Scope = flowScope.fresh()
      given ControlScope = cs.enterLoop(loopFrame)

      Inference.freshIsolate:
        transform(body)

    val whileBody =
      if loopFrame.isContinueUsed then Labeled(loopBody, VoidType, body2)(body.span)
      else body2

    val loop = While(cond2, whileBody)(word.span)

    if loopFrame.isBreakUsed then
      Labeled(loopEnd, VoidType, loop)(word.span)
    else
      loop

  /** Desugar a for loop
    *
    * From:
    *   for expr_pattern in expr [if cond] do block
    *
    * To:
    *   val $iter = expr.iterator
    *   while $iter.hasNext do
    *     val expr_pattern = $iter.next
    *     if cond then block
    */
  private def transformFor(forLoop: Ast.For)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, cs: ControlScope): Word =
    val Ast.For(pattern, iter, condOpt, body) = forLoop
    val span = forLoop.span

    val iterTyped = iter.getKeyOrUpdate(Namer.TypedWord):
      given TargetType = TargetType.ValueType
      given ControlScope = ControlScope.NoReturn
      Inference.freshIsolate:
        transform(iter)

    val rhs =
      iterTyped.tpe.approx.typeSymbolOpt match
        case Some(sym) if sym == defn.Iterator_type => iter
        case _ =>
          iterTyped.tpe.getTermMember("iterator") match
             case Some(_) => Ast.Select(iter, "iterator")(iter.span)

             case None =>
               Reporter.error("The value must have the type Iterator[T] or have a member .iterator conforms to the type", iter.pos)
               return unitValue(forLoop.span)

    // val $iter = iter.iterator
    val iterIdent = Ast.Ident("$iter")(iter.span)
    val iterVal = Ast.ValDef(iterIdent, Ast.EmptyTypeTree()(iter.span), rhs, mutable = false)(iter.span)

    // Build while condition: $iter.hasNext
    val iterRef1 = Ast.Ident("$iter")(iter.span)
    val hasNext = Ast.Select(iterRef1, "hasNext")(iter.span)

    // Build while body: val pattern = $iter.next; [if cond then] body
    val iterRef2 = Ast.Ident("$iter")(iter.span)
    val next = Ast.Select(iterRef2, "next")(iter.span)
    val patValDef = Ast.PatValDef(pattern, next)(pattern.span | next.span)

    // Build the body of the while loop
    val whileBody = condOpt match
      case None =>
        Ast.Block(List(patValDef, body))(patValDef.span | body.span)
      case Some(cond) =>
        val ifStmt = Ast.If(cond, body, Ast.Block(Nil)(body.span))(cond.span | body.span)
        Ast.Block(List(patValDef, ifStmt))(patValDef.span | ifStmt.span)

    // Create while loop: while $iter.hasNext do whileBody
    val whileLoop = Ast.While(hasNext, whileBody)(forLoop.span)

    // Return block with val definition followed by while loop
    Inference.freshIsolate:
      given TargetType = TargetType.VoidType
      transform(Ast.Block(List(iterVal, whileLoop))(span))

  private def transformReturn(ret: Ast.Return)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, cs: ControlScope): Word =
    if cs.inLambda then
      Reporter.error("return is not allowed inside a lambda", ret.pos)
      errorWord(ret.span)

    else
      cs.funReturn match
        case None =>
          Reporter.error("return requires an explicit return type annotation", ret.pos)
          errorWord(ret.span)

        case Some((label, returnType)) =>
          val value =
            ret.value match
              case Some(expr) =>
                Inference.freshIsolate:
                  given TargetType = TargetType.Known(returnType)
                  transform(expr)
              case None =>
                Inference.freshIsolate:
                  Checker.adapt(unitValue(ret.span), TargetType.Known(returnType))
          Return(label, value)(ret.span)

  private def transformBreak(brk: Ast.Break)
      (using rp: Reporter, so: Source, cs: ControlScope): Word =
    cs.loops.headOption match
      case None =>
        Reporter.error("break is only allowed inside while/for", brk.pos)
        errorWord(brk.span)
      case Some(loopFrame) =>
        loopFrame.markBreakUsed()
        Return(loopFrame.breakLabel, Block(Nil)(brk.span))(brk.span).dropValue

  private def transformContinue(cont: Ast.Continue)
      (using rp: Reporter, so: Source, cs: ControlScope): Word =
    cs.loops.headOption match
      case None =>
        Reporter.error("continue is only allowed inside while/for", cont.pos)
        errorWord(cont.span)
      case Some(loopFrame) =>
        loopFrame.markContinueUsed()
        Return(loopFrame.continueLabel, Block(Nil)(cont.span))(cont.span).dropValue

  private def transformIf(ifte: Ast.If)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars, cs: ControlScope): Word =
    val Ast.If(cond, thenp, elsep) = ifte

    val flowScope = new FlowScope(sc)

    val cond2 =
      given FlowScope = flowScope
      given TargetType = TargetType.Known(defn.BoolType)
      Inference.freshIsolate:
        FlowTyper.transformFlow(cond, this)

    val then2 =
      given Scope = flowScope.fresh()
      transform(thenp)

    val else2 =
      given Scope = sc.fresh()
      transform(elsep)

    // result type
    val commonType = Checker.commonResultType(then2.tpe, else2.tpe, ifte.pos)
    If(cond2, then2, else2)(commonType, ifte.span)


  def transformIsExpr(isExpr: Ast.IsExpr)(using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source, cs: ControlScope): Word =
    val Ast.IsExpr(scrutinee, pattern) = isExpr

    val scrutinee2 = Inference.freshIsolate:
      given TargetType = TargetType.ValueType
      FlowTyper.transformFlow(scrutinee, this)

    val pattern2 = Inference.freshIsolate:
      patternTyper.transformPattern(pattern, scrutinee2.tpe.widen)

    IsExpr(scrutinee2, pattern2)

  def transformLambda(lambda: Ast.Lambda)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word =

     val Ast.Lambda(params, body) = lambda

     // Extract target type information from LambdaType
     val knownParamTypesOpt: Option[List[Type]] =
       tt match
         case TargetType.Known(tp) =>
           if tp.isLambdaType then Some(tp.asLambdaType.paramTypes)
           else tp.getLambdaInterfaceType.map(_.paramTypes)

         case TargetType.LambdaType(paramTypes, _, _) => Some(paramTypes)

         case _ => None

     /* For closures, the effects stored in the type are different from those
      * raw effects computed from the code due to the capture behavior.
      */
     val receives: List[Symbol] =
       tt match
         case TargetType.Known(tp) =>
           if tp.isLambdaType then
             tp.asLambdaType.receives

           else
             tp.getLambdaInterfaceType match
               case Some(LambdaType(_, _, receives)) => receives
               case None => Nil

         case TargetType.LambdaType(_, _, receives) => receives

         case _ => Nil

     val bodyTargetType: TargetType =
       tt match
         case TargetType.Known(tp) =>
           if tp.isLambdaType then
             TargetType.Known(tp.asLambdaType.resultType)

           else
             tp.getLambdaInterfaceType match
               case Some(LambdaType(_, resultType, _)) => TargetType.Known(resultType)
               case None => TargetType.ValueType

         case TargetType.LambdaType(_, resultTarget, _) => resultTarget

         case _ => TargetType.ValueType

     // Check parameter count if target type is known
     knownParamTypesOpt match
       case Some(paramTypes) =>
         val expect = paramTypes.size
         if expect != params.size then
           Reporter.error(s"Expect a function with $expect parameters, found = ${params.size}", lambda.pos)
           return errorWord(lambda.span)

       case None =>

     val lambdaSym = TermSymbol.create("lambda", Flags.Fun | Flags.Synthetic, Visibility.Default, sc.owner, lambda.pos)
     val lambdaScope = sc.fresh(lambdaSym)

     def inferParamType(i: Int): Type =
       knownParamTypesOpt match
         case Some(paramTypes) => paramTypes(i)
         case None => TypeVar(params(i).name, params(i).span)

     val paramSyms = Checks.eager:
      for (param, i) <- params.zipWithIndex yield
        val tp = if param.tpt.isEmpty then inferParamType(i) else transformValueType(param.tpt).tpe
        val paramSym = TermSymbol.create(param.name, tp, Flags.Param, Visibility.Default, lambdaSym, param.pos)
        lambdaScope.define(paramSym)
        paramSym

     val bodyTyped =
       given Scope = lambdaScope
       given TargetType = bodyTargetType
       given ControlScope = ControlScope.InLambda
       transform(body)

     val res = Lambda(lambdaSym, paramSyms, receives, bodyTyped)(lambda.span)

     // Not really useful, but maintain the invariant that each symbol has info
     defn.index.add(lambdaSym, res.tpe)

     res

  private def transformParamDef(pdef: Ast.ParamDef)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : LazyDef[Def] =
    assert(pdef.default.isEmpty, "optional context param not desugared: " + pdef)

    val index = lazyDefn.index

    val extraFlags = pdef.getKeyOrElse(Desugaring.ExtraFlags)(Flags.empty)
    val flags = Checker.checkModifiers(pdef) | Flags.Context | extraFlags

    val paramSym = TermSymbol.create(pdef.name, flags, Checker.visibility(pdef, sc.owner), sc.owner, pdef.pos)

    val annotationsLazy = lazyValue:
      transformAnnotations(pdef.annotations)

    index.addLazy(paramSym, () => withDefn { transformValueType(pdef.tpt).tpe })
    index.setAnnotations(paramSym, () => annotationsLazy.value.map(TreeOps.applyToAnnotation))
    index.setDocComment(paramSym, pdef.docComment)

    lazyDef(paramSym):
      val tpt = TypeTree(paramSym.tpe)(pdef.tpt.span)
      ParamDef(paramSym, tpt)(annotationsLazy.value, pdef.span)

  private def transformLocalValDef(vdef: Ast.ValDef)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, cs: ControlScope): Assign =
    var flags = Checker.checkModifiers(vdef)
    if vdef.mutable then flags = flags | Flags.Mutable

    val sym = TermSymbol.create(vdef.name, flags, Visibility.Default, sc.owner, vdef.ident.pos)

    val givenType: LazyValue[Type] = lazyValue:
      Checks.eager:
        transformValueType(vdef.tpt).tpe

    val rhs: Word =
      given Scope = sc.fresh()
      given TargetType =
        if vdef.tpt.isEmpty then TargetType.ValueType
        else TargetType.Known(givenType.value)

      Inference.freshIsolate:
        transform(vdef.rhs)

    val tp: Type =
      if vdef.tpt.isEmpty then rhs.tpe.widen
      else givenType.value

    val index = defn.index
    index.add(sym, tp)
    index.setDocComment(sym, vdef.docComment)

    Assign(Ident(sym)(vdef.ident.span), rhs, isDefine = true)

  private def transformLocalAutoDef(adef: Ast.AutoDef)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, cs: ControlScope): Assign =
    // Auto definitions always have explicit types and are marked with Auto flag
    val flags = Checker.checkModifiers(adef) | Flags.Auto

    val sym = TermSymbol.create(adef.name, flags, Visibility.Default, sc.owner, adef.ident.pos)

    val givenType: Type = Checks.eager:
      transformValueType(adef.tpt).tpe

    val rhs: Word =
      given Scope = sc.fresh()
      given TargetType = TargetType.Known(givenType)

      Inference.freshIsolate:
        transform(adef.rhs)

    val index = defn.index
    index.add(sym, givenType)
    index.setDocComment(sym, adef.docComment)

    Assign(Ident(sym)(adef.ident.span), rhs, isDefine = true)

  def transformTypeParams(tparams: List[Ast.TypeParam])
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : List[TypeSymbol] =
    for tparam <- tparams yield
      // Only support simple-kinded type parameters
      val sym = TypeSymbol.create(Kind.Simple, tparam.name, AnyType, Flags.Param, Visibility.Default, sc.owner, tparam.pos)
      sc.define(sym)
      sym

  def transformParams(params: List[Ast.Param])
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : List[Symbol] =

    for (param, i) <- params.zipWithIndex yield
      val tpt = transformValueType(param.tpt, allowPackType = i == params.size - 1)
      val paramSym = TermSymbol.create(param.name, tpt.tpe, Flags.Param, Visibility.Default, sc.owner, param.pos)
      sc.define(paramSym)
      paramSym


  def transformAutos(autos: List[Ast.Auto])
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : List[Symbol] =

    for auto <- autos yield
      val tpt = transformValueType(auto.tpt)
      val autoSym = TermSymbol.create(auto.name, tpt.tpe, Flags.Param | Flags.Auto, Visibility.Default, sc.owner, auto.pos)
      sc.define(autoSym)
      autoSym

  def transformReceives(receives: Option[List[Ast.RefTree]], policy: Effects.Policy)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : Effects.Policy =

    receives match
      case Some(params) =>
        val effs =
          for
            param <- params
          yield
            transformParamRef(param)

        policy match
          case Effects.Policy.Infer | Effects.Policy.InferCapture =>
            val effSyms = effs.map(_.symbol)
            Effects.Policy.CheckBound(effSyms)

          case _ =>
            Effects.checkEffectsConform(effs, policy)
            policy

      case None =>
        policy

  /** Transform an annotation definition into a FunDef with Flags.Annotation. */
  private def transformAnnotationDef(adef: Ast.AnnotationDef)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : LazyDef[FunDef] =

    val flags = Flags.Fun | Flags.Annotation | Checker.checkModifiers(adef)
    val funSym = TermSymbol.create(adef.name, flags, Checker.visibility(adef, sc.owner), sc.owner, adef.ident.pos)

    given funScope: Scope = sc.fresh(funSym)

    val paramSymsLazy = lazyValue:
      transformParams(adef.params)

    Defaults.validatePostDefaultShape(adef.params)
    val defaultsLazy = lazyValue:
      Defaults.checkPostDefaults(adef.params, paramSymsLazy.value, this)

    Checks.add { defaultsLazy.value }

    def computeInfo() = withDefn:
      ProcType(
        tparams = Nil,
        params = paramSymsLazy.value.map(_.toNamedInfo),
        autos = Nil,
        candidates = Nil,
        resultType = VoidType,
        receivesInfo = Nil,
        preParamCount = 0,
        preTypeParamCount = 0
      )(defaultsLazy)

    val index = lazyDefn.index
    index.addLazy(funSym, computeInfo, () => computeInfo())

    Checks.add:
      withDefn:
        val defn = summon[Definitions]
        if !funSym.containedIn(defn.jo) then
          Reporter.error(
            s"Annotation definitions are currently restricted to the namespace `${defn.jo.fullName}`",
            adef.ident.pos
          )
        for (paramSym, astParam) <- paramSymsLazy.value.zip(adef.params) do
          val tpe = paramSym.tpe
          if tpe != defn.IntType && tpe != defn.BoolType && tpe != defn.StringType then
            Reporter.error(
              s"Annotation parameter type must be Int, Bool, or String, found ${tpe.show}",
              astParam.tpt.span.toPos
            )

    lazyDef(funSym):
      val tpt = TypeTree(VoidType)(adef.span)
      val body = Block(Nil)(adef.span)
      FunDef(funSym, Nil, paramSymsLazy.value, Nil, Nil, tpt, Effects.Policy.CheckBound(Nil), body)(annots = Nil, adef.span)

  /** Resolve AST annotation uses on a definition to SAST Apply nodes, and set them on the symbol.
    *
    * Annotation args go through the full application type-checking machinery
    * (applyResolvedFun), so defaults, arity checks, and type conformance all work.
    * After typing, a post-check enforces that every resolved arg is a literal constant.
    */
  private[typing] def transformAnnotations(astAnnots: List[Ast.Annotation])
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : List[Apply] =

    val seen = mutable.HashSet.empty[Symbol]

    astAnnots.flatMap: annot =>
      resolveQualid(annot.name, Universe.Annot) match
        case None =>
          Nil

        case Some(annotSym) =>
          if !annotSym.isAnnotation then
            Reporter.error(s"`${annot.name.name}` is not an annotation", annot.name.pos)
            Nil
          else if seen.contains(annotSym) then
            Reporter.error(
              s"The annotation `${annotSym.fullName}` may only be applied once to the same definition",
              annot.name.pos
            )
            Nil
          else
            seen += annotSym
            val annotIdent = Ident(annotSym)(annot.name.span)

            // Type-check the call through normal application machinery so that
            // defaults, arity, and param-type checks all run as usual.
            given TargetType = TargetType.VoidType
            given ControlScope = ControlScope.NoReturn
            val callReporter = rp.fresh(buffer = true)
            val typedCall: Word =
              given Reporter = callReporter
              Inference.freshIsolate:
                applyResolvedFun(annotIdent, annot.args, annot.span)

            callReporter.commit(rp)
            val hasCallErrors = callReporter.hasErrors

            typedCall match
              case Apply(_, args, _) =>
                // Post-check: each argument must be a literal constant (no runtime expressions).
                // Named-arg wrappers — Apply(namedArg_sym, [key, value], _) — are accepted and
                // stripped so the stored Apply always carries plain Literals.
                // Skip if the call-checker already reported errors to avoid a confusing second message.
                def stripArg(arg: Word): Option[Literal] =
                  arg match
                    case lit: Literal => Some(lit)
                    case Apply(fun, List(_, lit: Literal), _) if fun.refers(defn.compile_namedArg) => Some(lit)
                    case _ => None

                val litsOpt: Option[List[Literal]] =
                  if hasCallErrors then None
                  else
                    val buf = scala.collection.mutable.ArrayBuffer.empty[Literal]
                    var ok = true
                    for arg <- args if ok do
                      stripArg(arg) match
                        case Some(lit) => buf += lit
                        case None =>
                          Reporter.error("Annotation argument must be a literal constant", arg.span.toPos)
                          ok = false
                    if ok then Some(buf.toList) else None

                litsOpt match
                  case Some(lits) => List(Apply(annotIdent, lits, Nil)(annot.span))
                  case None       => Nil

              case _ =>
                Nil  // error already reported by applyResolvedFun


  private def transformFunDef(funDef: Ast.FunDef, initialFlags: Flags, policy: Effects.Policy)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : LazyDef[FunDef] =
    val extraFlags = funDef.getKeyOrElse(Desugaring.ExtraFlags)(Flags.empty)
    val flags = Checker.checkModifiers(funDef) | initialFlags | extraFlags

    val funSym = TermSymbol.create(funDef.name, flags, Checker.visibility(funDef, sc.owner), sc.owner, funDef.ident.pos)

    given Scope = sc.fresh(funSym)

    if flags.is(Flags.Defer) then
      if funDef.resultType.isEmpty then
        Reporter.error("A deferred definition should have explicit result type", funDef.ident.pos)

      // view accessor have such flags
      if sc.owner.isLocal then
        Reporter.error("A deferred definition should be at top-level", funDef.ident.pos)

    else if Config.explicitReturnType.value && funDef.resultType.isEmpty && !flags.is(Flags.Annotation) then
      Reporter.error("This project requires functions to have explicit return type", funDef.ident.pos)

    val annotationsLazy = lazyValue:
      given Scope = sc
      transformAnnotations(funDef.annotations)

    val tparamSymsLazy = lazyValue:
      transformTypeParams(funDef.tparams)

    val paramSymsLazy = lazyValue:
      tparamSymsLazy.value
      transformParams(funDef.params)

    val autoSymsLazy = lazyValue:
      tparamSymsLazy.value
      transformAutos(funDef.autos)

    val candidatesLazy = lazyValue:
      funDef.autos.zip(autoSymsLazy.value).map: (auto, autoSym) =>
        Autos.check(auto.candidates, autoSym.tpe, this)

    val givenResultTypeLazy = lazyValue:
      tparamSymsLazy.value

      assert(!funDef.resultType.isEmpty)
      transformValueType(funDef.resultType).tpe

    val typedBodyLazy = lazyValue:
      val defn = summon[Definitions]
      paramSymsLazy.value
      autoSymsLazy.value

      if flags.is(Flags.Defer) && !flags.is(Flags.Default) then
        // Dummy body deferred function without default implementation
        val dummyBody = Block(Nil)(funDef.body.span)
        if funDef.resultType.isEmpty then dummyBody.encodedAs(defn.UnitType)
        else dummyBody.encodedAs(givenResultTypeLazy.value)
      else
        val targetType =
          if !funDef.resultType.isEmpty then
            TargetType.Known(givenResultTypeLazy.value)
          else
            TargetType.ValueType

        val returnScope =
          if !funDef.resultType.isEmpty then ControlScope.fun(funSym, givenResultTypeLazy.value)
          else ControlScope.NoReturn

        given ControlScope = returnScope
        Inference.freshIsolate:
          given TargetType = targetType
          transform(funDef.body)

    // Inferring result type would need fixed point computation for recursive
    // functions. That complicates the machinery in the namer (in particular
    // post checks).
    //
    // We cannot simply introduce a type variable as the result type because the
    // type variable might refer to type parameters in its instantiation, thus
    // requires substitution.
    //
    // Generalizing a substitution mechanism is not worth the effort for the
    // moment. Therefore, recursive functions have to be explicitly typed.
    val resultTypeLazy = lazyValue:
      if !funDef.resultType.isEmpty then
        givenResultTypeLazy.value
      else
        typedBodyLazy.value.tpe.widen
      end if

    val effectPolicyLazy = lazyValue:
      if flags.is(Flags.Defer) && funDef.receives.isEmpty then
        Effects.Policy.CheckBound(Nil)
      else
        transformReceives(funDef.receives, policy)

    // Eagerly validate post-parameter default shape (syntax-only check)
    val astPostParams = funDef.params.drop(funDef.preParamCount)
    Defaults.validatePostDefaultShape(astPostParams)

    def computeInfo(resultType: Type) = withDefn:
      val candidates = candidatesLazy.value.map(_._2)
      val postParamSyms = paramSymsLazy.value.drop(funDef.preParamCount)
      val defaults = lazyValue:
        Defaults.checkPostDefaults(astPostParams, postParamSyms, this)
      Checks.add { defaults.value }

      /* The effects of a method symbol stored in the type is different from those
       * raw effects computed from the code due to the auto provision of optional
       * context parameters.
       */
      val receivesInfo: Symbol | List[Symbol] =
        effectPolicyLazy.value.bound match
          case Some(effs) => effs
          case None => funSym

      ProcType(
        tparamSymsLazy.value, paramSymsLazy.value.map(_.toNamedInfo),
        autoSymsLazy.value.map(_.toNamedInfo), candidates,
        resultType, receivesInfo, funDef.preParamCount, funDef.preTypeParamCount
      )(defaults)

    val index = lazyDefn.index
    index.addLazy(funSym, () => computeInfo(resultTypeLazy.value), () => computeInfo(ErrorType))
    index.setAnnotations(funSym, () => annotationsLazy.value.map(TreeOps.applyToAnnotation))
    index.setDocComment(funSym, funDef.docComment)

    lazyDef(funSym):
      val candidateTrees = candidatesLazy.value.map(_._1)
      val tpt = TypeTree(resultTypeLazy.value)(funDef.resultType.span)
      FunDef(
        funSym, tparamSymsLazy.value, paramSymsLazy.value, autoSymsLazy.value,
        candidateTrees, tpt, effectPolicyLazy.value, typedBodyLazy.value
      )(annotationsLazy.value, funDef.span)

  private def transformConstructor(funDef: Ast.FunDef, thisSym: Symbol, classSym: Symbol)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : LazyDef[FunDef] =

    val flags = Flags.Fun | Flags.Method | Flags.Constructor

    val visibility = Checker.visibility(funDef, classSym)

    val annotationsLazy = lazyValue:
      given Scope = sc
      transformAnnotations(funDef.annotations)

    val funSym = TermSymbol.create(Names.Constructor, flags, visibility, classSym, funDef.ident.pos)

    given ctorScope: Scope = sc.fresh(funSym)

    if funDef.tparams.nonEmpty then
      Reporter.error("Constructor may not take type parameters", funDef.tparams.head.pos)

    val astPostParams = funDef.params  // constructors have no pre-params
    Defaults.validatePostDefaultShape(astPostParams)

    val paramSymsLazy = lazyValue:
      transformParams(funDef.params)

    val autoSymsLazy = lazyValue:
      transformAutos(funDef.autos)

    val candidatesLazy = lazyValue:
      funDef.autos.zip(autoSymsLazy.value).map: (auto, autoSym) =>
        Autos.check(auto.candidates, autoSym.tpe, this)

    val resultTypeLazy = lazyValue:
      if !funDef.resultType.isEmpty then
        val resTypeTree = transformValueType(funDef.resultType)
        val res = resTypeTree.tpe

        if !Subtyping.isEqualType(res, thisSym.tpe) then
          Reporter.error("The result type of constructor should be the same as the class", funDef.resultType.pos)

      thisSym.tpe

    def checkBody(stats: List[Ast.Word]): Word = withDefn:
      given ControlScope = ControlScope.NoReturn
      val classInfo = classSym.classInfo
      val uninitialized = mutable.Set.from(classInfo.fields)
      val words = new mutable.ArrayBuffer[Word]

      // Process all statements
      for stat <- stats do
        stat match
          case Ast.Assign(lhs @ Ast.Select(qual: Ast.This, name), rhs) =>
            // Field initialization
            StaticRef(thisSym).getTermMember(name) match
              case Some(tp) =>
                assert(tp.is[RefType], "class member should be RefType, found = " + tp)

                val sym = tp.as[RefType].symbol
                if !uninitialized.contains(sym) then
                  Reporter.error("The field " + name + " already initialized", lhs.pos)

                else
                  val lhsTyped = Select(Ident(thisSym)(qual.span), name)(lhs.span)

                  // Type-check RHS with accumulated field scope (params + previously initialized fields)
                  val rhsTyped = Inference.freshIsolate:
                    given TargetType = TargetType.Known(tp.widenTermRef)
                    transform(rhs)

                  words += FieldAssign(lhsTyped, rhsTyped)
                  uninitialized -= sym

              case None =>
                Reporter.error("The field " + name + " does not exist in class " + classSym, lhs.pos)

          case _ =>
            // Regular statement
            Inference.freshIsolate:
              given TargetType = TargetType.VoidType
              words += transform(stat)

      // Check that all fields are initialized
      if uninitialized.nonEmpty then
        val names = uninitialized.map(_.name).mkString(", ")
        Reporter.error("Uninitialized field(s): " + names, funDef.pos)

      // Automatically append 'this' to return the instance
      words += Ident(thisSym)(funDef.body.span)

      Block(words.toList)(funDef.body.span)

    val typedBodyLazy = lazyValue:
      paramSymsLazy.value
      autoSymsLazy.value

      funDef.body match
        case Ast.Block(stats) =>
          checkBody(stats)

        case _ =>
          Reporter.error("Constructor body must be a block", funDef.body.pos)
          errorWord(funDef.body.span)

    val effectPolicyLazy = lazyValue:
      transformReceives(funDef.receives, Effects.Policy.Infer)

    val tparamSyms = Nil
    def computeInfo(resultType: Type) = withDefn:
      val candidateSymbols = candidatesLazy.value.map(_._2)
      val defaultsLazy = lazyValue:
        Defaults.checkPostDefaults(astPostParams, paramSymsLazy.value, this)
      Checks.add { defaultsLazy.value }

      ProcType(
        tparamSyms, paramSymsLazy.value.map(_.toNamedInfo), autoSymsLazy.value.map(_.toNamedInfo), candidateSymbols,
        resultType, funSym, funDef.preParamCount, funDef.preTypeParamCount)(defaultsLazy)

    val index = lazyDefn.index
    index.addLazy(funSym, () => computeInfo(resultTypeLazy.value), () => computeInfo(ErrorType))
    index.setAnnotations(funSym, () => annotationsLazy.value.map(TreeOps.applyToAnnotation))
    index.setDocComment(funSym, funDef.docComment)

    lazyDef(funSym):
      val candidateTrees = candidatesLazy.value.map(_._1)
      val tpt = TypeTree(resultTypeLazy.value)(funDef.resultType.span)
      FunDef(
        funSym, tparamSyms, paramSymsLazy.value, autoSymsLazy.value,
        candidateTrees, tpt, effectPolicyLazy.value, typedBodyLazy.value
      )(annotationsLazy.value, funDef.span)

  private def transformTypeDef(tdef: Ast.TypeDef)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : LazyDef[TypeDef] =

    var flags = Checker.checkModifiers(tdef)

    def isAnyOrBottom: Boolean =
      lazyDefn.rootNameTable.resolveContainer("jo") match
         case Some(sym) if sym == sc.owner =>
            val typeName = tdef.name
            typeName == "Any" || typeName == "Bottom"

         case _ =>
            false

    if tdef.rhs.isEmpty && !isAnyOrBottom then
      flags = flags | Flags.Defer

    val kind = Kind.simpleKinded(tdef.tparams.size)

    val annotationsLazy = lazyValue:
      given Scope = sc
      transformAnnotations(tdef.annotations)

    val typeSym = TypeSymbol.create(kind, tdef.name, flags, Checker.visibility(tdef, sc.owner), sc.owner, tdef.ident.pos)

    given sc2: Scope = sc.fresh(typeSym)
    val tparamSymsLazy = lazyValue:
      transformTypeParams(tdef.tparams)

    val rhsTypeLazy = lazyValue:
      val defn = summon[Definitions]
      // force creation of symbols for type parameters
      tparamSymsLazy.value

      if tdef.rhs.isEmpty then
        if sc.owner == defn.jo then
          val typeName = tdef.name
          if typeName == "Any" then AnyType
          else if typeName == "Bottom" then BottomType
          else AnyType
        else
          AnyType

      else
        val rhsTree = transformValueType(tdef.rhs)
        val rhs = rhsTree.tpe

        if TypeOps.hasCyclesInType(typeSym, rhs) then
          Reporter.error("Cycles detected for the type definition " + typeSym, tdef.ident.pos)
          ErrorType
        else
          rhs


    def computeInfo(): Denotation =
      if tdef.tparams.isEmpty then
        rhsTypeLazy.value
      else
        TypeOperatorInfo(tparamSymsLazy.value, rhsTypeLazy.value, tdef.preParamCount)

    val errorType = () =>
      if tdef.tparams.isEmpty then ErrorType
      else TypeOperatorInfo(tparamSymsLazy.value, ErrorType, tdef.preParamCount)

    val index = lazyDefn.index
    index.addLazy(typeSym, computeInfo, errorType)
    index.setDocComment(typeSym, tdef.docComment)
    index.setAnnotations(typeSym, () => annotationsLazy.value.map(TreeOps.applyToAnnotation))

    // check type symbols after completion to allow cycles, type A = A
    lazyDef(typeSym):
      val tpt = TypeTree(rhsTypeLazy.value)(tdef.rhs.span)
      TypeDef(typeSym, tparamSymsLazy.value, tpt)(annotationsLazy.value, tdef.span)

  private def transformClassDef(cdef0: Ast.ClassDef)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : LazyDef[ClassDef] =

    val extraFlags = cdef0.getKeyOrElse(Desugaring.ExtraFlags)(Flags.empty)
    val flags = Checker.checkModifiers(cdef0) | extraFlags | Flags.Class
    val kind = Kind.simpleKinded(cdef0.tparams.size)

    val classAnnotationsLazy = lazyValue:
      given Scope = sc
      transformAnnotations(cdef0.annotations)

    val classSym = TypeSymbol.create(kind, cdef0.name, flags, Checker.visibility(cdef0, sc.owner), sc.owner, cdef0.ident.pos)
    val thisSym = TermSymbol.create("this", Flags.Synthetic, Visibility.Default, classSym, cdef0.ident.pos)

    // Class desugaring now happens in Desugaring.synthesize
    val cdef = Desugaring.desugarClassDef(cdef0, thisSym)

    given paramScope: Scope = sc.fresh(classSym)

    val tparamSymsLazy = lazyValue:
      transformTypeParams(cdef.tparams)

    val fields = new mutable.ArrayBuffer[Symbol]
    val methods = new mutable.ArrayBuffer[Symbol]

    val directViewTreesLazy = lazyValue:
      tparamSymsLazy.value

      cdef.views.map: vdecl =>
        transformValueType(vdecl.tpe)

    val classInfoLazy = lazyValue:
      val directViews = directViewTreesLazy.value.map(_.tpe)

      new ClassInfo(
        classSym,
        tparamSymsLazy.value,
        thisSym,
        fields.toList,
        methods.toList,
        directViews
      )

    val index = lazyDefn.index
    index.addLazy(classSym, () => classInfoLazy.value)
    index.setAnnotations(classSym, () => classAnnotationsLazy.value.map(TreeOps.applyToAnnotation))
    index.setDocComment(classSym, cdef0.docComment)

    // Add this to scope
    val thisScope = paramScope.fresh()
    thisScope.define(thisSym)
    val shortCutScope = thisScope.freshPrefixedScope(prefix = thisSym, owner = classSym)

    val thisInfoLazy = lazyValue:
      val classRef = StaticRef(classSym)
      if tparamSymsLazy.value.isEmpty then classRef
      else AppliedType(classSym, tparamSymsLazy.value.map(StaticRef.apply))

    index.addLazy(thisSym, () => thisInfoLazy.value)

    val delayedDefs = new mutable.ArrayBuffer[LazyDef[FunDef]]
    val delayedFields = new mutable.ArrayBuffer[LazyDef[FieldDecl]]

    for vdef <- cdef.vals do
      var flags = Checker.checkModifiers(vdef) | vdef.getKeyOrElse(Desugaring.ExtraFlags)(Flags.empty)
      if vdef.mutable then flags = flags | Flags.Field | Flags.Mutable
      else flags = flags | Flags.Field

      val fieldAnnotationsLazy = lazyValue:
        transformAnnotations(vdef.annotations)

      val sym = TermSymbol.create(vdef.name, flags, Checker.visibility(vdef, classSym), classSym, vdef.ident.pos)
      shortCutScope.define(sym)

      val fieldDeclLazy = lazyValue:
        val tpt =
          if vdef.tpt.isEmpty then
            val rhs = vdef.rhs.getKeyOrUpdate(Namer.TypedWord):
              given Scope = shortCutScope
              given TargetType = TargetType.ValueType
              given ControlScope = ControlScope.NoReturn
              Inference.freshIsolate:
                transform(vdef.rhs)
            TypeTree(rhs.tpe.widen)(vdef.rhs.span)
          else
            transformValueType(vdef.tpt)

        FieldDecl(sym, tpt)(vdef.span, fieldAnnotationsLazy.value)

      def checkType() = fieldDeclLazy.value.tpt.tpe

      if vdef.name == cdef.name then
        Reporter.error("Class name cannot be used as field name", vdef.pos)

      else
        fields += sym

        index.addLazy(sym, () => checkType())
        index.setAnnotations(sym, () => fieldAnnotationsLazy.value.map(TreeOps.applyToAnnotation))
        index.setDocComment(sym, vdef.docComment)

        delayedFields += lazyDef(sym)(fieldDeclLazy.value)

    for fdef <- cdef.funs do
      given Scope = shortCutScope

      if fdef.preParamCount != 0 then
        Reporter.error("Methods cannot have pre-arguments", fdef.pos)

      val delayedDef =
        if fdef.name == cdef.name then
          transformConstructor(fdef, thisSym, classSym)

        else
          transformFunDef(fdef, Flags.Fun | Flags.Method, Effects.Policy.Infer)

      val symbol = delayedDef.symbol
      if methods.exists(_.name == symbol.name) then
        Reporter.error("A method with the name " + symbol.name + " is already defined", symbol.sourcePos)

      else
        methods += delayedDef.symbol

        // Operator name should not be called directly without a prefix
        if !Naming.isOperator(symbol.name) then
          shortCutScope.define(symbol)

        delayedDefs += delayedDef

    lazyDef(classSym):
      val fields: List[FieldDecl] =
        for delayedField <- delayedFields.toList yield delayedField.force()

      val funs: List[FunDef] =
        for delayedDef <- delayedDefs.toList yield delayedDef.force()

      ClassDef(classSym, thisSym, tparamSymsLazy.value, fields, funs, directViewTreesLazy.value)(classAnnotationsLazy.value, cdef.span)

  private def transformInterfaceDef(idef: Ast.InterfaceDef)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : LazyDef[InterfaceDef] =

    val flags = Checker.checkModifiers(idef) | Flags.Interface
    val kind = Kind.simpleKinded(idef.tparams.size)

    val annotationsLazy = lazyValue:
      given Scope = sc
      transformAnnotations(idef.annotations)

    val interfaceSym = TypeSymbol.create(kind, idef.name, flags, Checker.visibility(idef, sc.owner), sc.owner, idef.ident.pos)

    val selfSym = TermSymbol.create("this", Flags.Synthetic, Visibility.Default, interfaceSym, idef.ident.pos)

    given paramScope: Scope = sc.fresh(interfaceSym)

    val tparamSymsLazy = lazyValue:
      transformTypeParams(idef.tparams)

    val methods = new mutable.ArrayBuffer[Symbol]

    val interfaceInfoLazy = lazyValue:
      annotationsLazy.value
      // Reuse ClassInfo but with empty fields
      new ClassInfo(
        interfaceSym,
        tparamSymsLazy.value,
        selfSym,
        fields = Nil,
        methods.toList,
        directViews = Nil
      )

    val index = lazyDefn.index
    index.addLazy(interfaceSym, () => interfaceInfoLazy.value)
    index.setAnnotations(interfaceSym, () => annotationsLazy.value.map(TreeOps.applyToAnnotation))
    index.setDocComment(interfaceSym, idef.docComment)

    // Add self to scope for use in default method implementations
    val selfScope = paramScope.fresh()
    selfScope.define(selfSym)
    val shortCutScope = selfScope.freshPrefixedScope(prefix = selfSym, owner = interfaceSym)

    val selfInfoLazy = lazyValue:
      val interfaceRef = StaticRef(interfaceSym)
      if tparamSymsLazy.value.isEmpty then interfaceRef
      else AppliedType(interfaceSym, tparamSymsLazy.value.map(StaticRef.apply))

    index.addLazy(selfSym, () => selfInfoLazy.value)

    val delayedDefs = new mutable.ArrayBuffer[LazyDef[FunDef]]
    for fdef <- idef.members do
      given Scope = shortCutScope

      if fdef.preParamCount != 0 then
        Reporter.error("Interface methods cannot have pre-arguments", fdef.pos)

      var methodFlags = Flags.Fun | Flags.Method

      // Only abstract methods (without body) are deferred
      if fdef.body.isEmptyBlock then
        methodFlags |= Flags.Defer

      val delayedDef = transformFunDef(fdef, methodFlags, Effects.Policy.Infer)
      methods += delayedDef.symbol

      // Operator name should not be called directly without a prefix
      if !Naming.isOperator(delayedDef.symbol.name) then
        shortCutScope.define(delayedDef.symbol)

      delayedDefs += delayedDef

    lazyDef(interfaceSym):
      val methodDefs: List[FunDef] =
        for delayedDef <- delayedDefs.toList yield delayedDef.force()

      InterfaceDef(interfaceSym, selfSym, tparamSymsLazy.value, methodDefs)(annotationsLazy.value, idef.span)

  private def transformSection
      (section: Ast.Section)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : LazyDef[Section] =

    val flags = Checker.checkModifiers(section) | Flags.Section
    val nameTable = new NameTable

    val annotationsLazy = lazyValue:
      given Scope = sc
      transformAnnotations(section.annotations)

    val sym = ContainerSymbol.create(section.name, nameTable, flags, Checker.visibility(section, sc.owner), sc.owner, section.ident.pos)

    given Scope = sc.fresh(sym, nameTable)

    val delayedDefs = index(section.defs)
    nameTable.freeze()

    val idx = lazyDefn.index
    idx.setAnnotations(sym, () => annotationsLazy.value.map(TreeOps.applyToAnnotation))
    idx.setDocComment(sym, section.docComment)

    lazyDef(sym):
      val defs = for delayed <- delayedDefs.toList yield delayed.force()
      Section(sym, defs)(annotationsLazy.value, section.span)

  def transformValueType(tpt: Ast.TypeTree, allowPackType: Boolean = false)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : TypeTree =
    // The value kind check will cover
    val tptTyped = transformType(tpt, allowPackType = allowPackType)
    if Checker.checkValueType(tptTyped.tpe, tpt.pos) then
      tptTyped

    else
      TypeTree(ErrorType)(tpt.span)

  /** Type check type tree
    *
    * Checks must be delayed by using `checks.add`.
    */
  def transformType(tpt: Ast.TypeTree, allowPackType: Boolean = false)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : TypeTree =

    def check(sym: Symbol) =
      if sym == defn.jo_Pack && !allowPackType then
        Reporter.error(".. not allowed here. It can only be used as the type of the last varargs parameter.", tpt.pos)

    tpt.getKeyOrElse(Namer.TypedTypeTree):
      tpt match
      case Ast.Ident(name) =>
        sc.resolveTypeOpt(name) match
          case Some(sym) =>
            check(sym)
            TypeTree(StaticRef(sym))(tpt.span)

          case None =>
            Reporter.error(s"Cannot find the type $name", tpt.pos)
            TypeTree(ErrorType)(tpt.span)

      case Ast.Select(qual, name) =>
        resolveContainer(qual.asInstanceOf[Ast.RefTree]) match
          case Some(sym) =>
            sym.nameTable.resolveType(name) match
              case Some(sym) =>
                check(sym)
                Checker.checkAccess(sym, sc.owner, tpt.span)
                TypeTree(StaticRef(sym))(tpt.span)

              case None =>
                Reporter.error(s"The namespace $sym does not contain the type member $name", qual.pos)
                TypeTree(ErrorType)(tpt.span)

          case _ =>
            TypeTree(ErrorType)(tpt.span)

      case tpt: Ast.ExprType  =>
        exprTyper.transformType(tpt, allowPackType)

      case Ast.UnionType(branches) =>
        val branchTypes = new mutable.ArrayBuffer[Type]
        val classes = mutable.Set.empty[Symbol]
        for branch <- branches do
          val branchType = transformValueType(branch).tpe
          val branchClasses =
            if branchType.isClassType then
              branchType.classSymbol :: Nil

            else if branchType.isUnionType then
              branchType.asUnionType.classes

            else
              Reporter.error("Only class type or union type allowed inside a union type, found = " + branchType.show, branch.pos)
              Nil

          var validBranch = branchClasses.nonEmpty
          for cls <- branchClasses do
            if classes.exists(_ == cls) then
              Reporter.error("Branch " + cls + " already defined", branch.pos)
              validBranch = false
            else
              classes += cls

          if validBranch then
            branchTypes += branchType

        end for

        // Check for numeric type conflicts (JS backend limitation)
        // Multiple numeric types cannot be distinguished at runtime in JavaScript
        val numericOrBoolTypes = branchTypes.filter(_.isNumericOrBoolType)

        if numericOrBoolTypes.size > 1 then
          val typeNames = numericOrBoolTypes.map(_.show).mkString(", ")
          Reporter.error(
            s"Union type cannot contain multiple numeric/boolean types: ($typeNames)",
            tpt.pos
          )

        val unionType = UnionType(branchTypes.toList)
        TypeTree(unionType)(tpt.span)

      case Ast.DuckType(baseTypeTpt, adapters) =>
        val baseTypeTree = transformValueType(baseTypeTpt)
        val baseType = baseTypeTree.tpe

        // Check that we have at least one adapter
        if adapters.isEmpty then
          Reporter.error("Duck type must have at least one adapter", tpt.pos)
          TypeTree(ErrorType)(tpt.span)
        else
          // Check and validate adapters - they should convert TO the base type
          val adaptersLazy = lazyValue:
            Adapters.check(adapters, baseType, this)

          Checks.add { adaptersLazy.value }

          if baseType.adapters.nonEmpty then
            // Base type already has adapters (e.g., it's a duck type)
            Reporter.error("Duck type base type cannot have adapters", baseTypeTpt.pos)
            TypeTree(baseType)(tpt.span)

          else
            val duckType = DuckType(baseType)(adaptersLazy)
            TypeTree(duckType)(tpt.span)

      case Ast.AppliedType(tctor, targs) =>
        val tctor2 = transformType(tctor, allowPackType)
        val targs2 = for targ <- targs yield transformValueType(targ, allowPackType = false)
        tctor2.tpe match
          case StaticRef(tctorSym) =>
            if tctor2.tpe == ErrorType || !Checker.checkKind(tctor2, targs2) then
              TypeTree(ErrorType)(tpt.span)
            else
              val tp = AppliedType(tctorSym, targs2.map(_.tpe))
              TypeTree(tp)(tpt.span)

          case tp =>
            Reporter.error("A type reference expected, found = " + tp.show, tctor.pos)
            TypeTree(ErrorType)(tpt.span)

      case Ast.FunctionType(paramTypes, resType, receives) =>
        val paramTypes2 =
          for paramType <- paramTypes yield transformValueType(paramType).tpe

        val effs =
          for
            param <- receives
          yield
            transformParamRef(param).symbol

        val resType2 = transformValueType(resType)
        val resTypeChecked = resType2.tpe

        val lambdaType = LambdaType(paramTypes2, resTypeChecked, effs)
        TypeTree(lambdaType)(tpt.span)

      case Ast.ExtensionType(baseTpt, methodEntries) =>
        // Type-check the base type
        val baseTree = transformValueType(baseTpt)
        val baseType = baseTree.tpe

        // Resolve each method reference to a symbol.
        val extensionsLazy = lazyValue:
          methodEntries.flatMap:
            case (ref: Ast.RefTree, isOverride: Boolean) =>
              resolveQualid(ref, Universe.Term) match
                case Some(sym) =>
                  if Extensions.checkMethod(sym, baseType, isOverride, fromAnnotation = false, ref.pos) then
                    Some(sym)
                  else None
                case None =>
                  Reporter.error(s"Cannot find method ${ref.show}", ref.pos)
                  None

            case ref: Ast.RefTree =>
              resolveQualid(ref, Universe.Term) match
                case Some(sym) =>
                  val isOverride = sym.hasAnnotation(defn.shadow)
                  if Extensions.checkMethod(sym, baseType, isOverride, fromAnnotation = true, sym.sourcePos) then
                    Some(sym)
                  else None
                case None =>
                  Reporter.error(s"Cannot find method ${ref.show}", ref.pos)
                  None

        Checks.add { extensionsLazy.value }
        val extensionType = ExtensionType(baseType)(extensionsLazy)
        TypeTree(extensionType)(tpt.span)

      case Ast.AnnotType(innerTpt, astAnnot) =>
        val baseTree = transformValueType(innerTpt)
        val baseType = baseTree.tpe

        resolveQualid(astAnnot.name, Universe.Annot) match
          case None =>
            baseTree  // unknown annotation — silently transparent

          case Some(annotSym) if !annotSym.isAnnotation =>
            Reporter.error(s"`${astAnnot.name.show}` is not an annotation", astAnnot.name.pos)
            baseTree

          case Some(annotSym) =>
            val args = astAnnot.args.flatMap:
              case Ast.StringLit(value)      => List(Constant.String(value))
              case Ast.IntLit(value, _)      => List(Constant.Int(value.toInt))
              case Ast.BoolLit(value)        => List(Constant.Bool(value))
              case arg =>
                Reporter.error("Annotation type argument must be a literal", arg.span.toPos)
                Nil
            TypeTree(AnnotType(baseType, Symbols.Annotation(annotSym, args)))(tpt.span)

      case _: Ast.EmptyTypeTree =>
        Reporter.abort("Unexpected empty type tree", tpt.pos)

object Namer:
  /** The typed word associated with an untyped word
    *
    * It is used to avoid re-typing a word.
    */
  val TypedWord = new KeyProps.Key[Word]("Namer.TypedWord")

  val TypedTypeTree = new KeyProps.Key[TypeTree]("Namer.TypedTypeTree")

  def lazyValue[T](f: Definitions ?=> T)(using defnLazy: Definitions.Lazy): LazyValue[T] =
    LazyValue(() => f(using defnLazy.value))

  def lazyDef[T](sym: Symbol)(f: Definitions ?=> T)(using defnLazy: Definitions.Lazy): LazyDef[T] =
    LazyDef(sym, () => f(using defnLazy.value))

  def withDefn[T](f: Definitions ?=> T)(using defnLazy: Definitions.Lazy): T =
    f(using defnLazy.value)
