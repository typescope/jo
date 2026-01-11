package typing

import ast.{ Trees => Ast }
import ast.Naming
import ast.Positions.*

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import common.Debug
import common.KeyProps
import common.OutOfBand

import reporting.Reporter
import reporting.Config

import Inference.*

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
class Namer(using Config):
  val patternTyper = PatternTyper(this)
  val exprTyper = new ExprTyper(this)

  def transform
      (nss: List[Ast.Namespace], rootNameTable: NameTable, worldScope: Scope)
      (using defnLazy: Definitions.Lazy, rp: Reporter)
  : List[Namespace] = Checks.delayed:

    given ip: InfoProvider = defnLazy.infoProvider

    val delayedImports = new mutable.ArrayBuffer[() => Unit]
    val delayedNamespaces = new mutable.ArrayBuffer[DelayedDef[Namespace]]

    for ns <- nss do
      given source: Source = Reporter.source(ns.source)

      val nsSym = resolveNamespace(ns.qualid, rootNameTable, isBranch = false)
      val memberTable = nsSym.nameTable

      // Default imports should be treated as just before normal imports
      val importScope: Scope = worldScope.fresh(nsSym)
      val defsScope: Scope = importScope.fresh(nsSym, memberTable)

      val delayedDefs =
        given Scope = defsScope
        index(ns.defs)

      val imports = new mutable.ArrayBuffer[Symbol]

      delayedImports += { () =>
        // handle imports after indexing members
        for imp <- ns.imports do
          imports ++= Imports.doImport(imp.qualid, importScope, rootNameTable)
      }

      delayedNamespaces += DelayedDef(nsSym, { () =>
        given Definitions = defnLazy.value
        val defs = for delayed <- delayedDefs.toList yield delayed.force()
        Namespace(nsSym, imports.toList, defs)(ns.span)
      })
    end for

    delayedImports.foreach(_.apply())

    val namespaces =
      for delayedDef <- delayedNamespaces
      yield delayedDef.delayed() <| delayedDef.symbol.sourcePos.source.file

    namespaces.toList

  /** Resolve namespace and create intermediate namespace on demand
    *
    * It also checks redefinition of namespace.
    */
  def resolveNamespace
      (qualid: Ast.RefTree, rootNameTable: NameTable, isBranch: Boolean)
      (using rp: Reporter, so: Source, ip: InfoProvider)
  : Symbol =

    def check(sym: Symbol): Symbol =
      val name = sym.name
      val pos = sym.sourcePos
      if sym.isNamespace && !sym.isAlias then
        if isBranch && !sym.is(Flags.Branch) then
          rp.error(s"The $name is already defined as a namespace at $pos", qualid.pos)
          sym

        else if !isBranch then
          // leaf namespace should not exist
          if sym.is(Flags.Branch) then
            rp.error(s"The namespace $name is already defined as a branch name at $pos", qualid.pos)
          else
            rp.error(s"The namespace $name is already defined at $pos", qualid.pos)

          sym

        else
          sym

      else
        rp.error(s"The $name is already defined as a member at $pos, ", qualid.pos)
        val flags = if isBranch then Flags.NSpace | Flags.Branch else Flags.NSpace
        ContainerSymbol.create(sym.name, new NameTable, flags, Visibility.Default, sym.owner, qualid.pos)

    qualid match
      case Ast.Select(qual, name) =>
        assert(qual.isInstanceOf[Ast.RefTree], "Unexpected qualid = " + qualid)
        val nsSym = resolveNamespace(qual.asInstanceOf[Ast.RefTree], rootNameTable, isBranch = true)

        assert(nsSym.isNamespace, "Not a namespace " + nsSym)
        val nameTable = nsSym.nameTable

        nameTable.resolveContainer(name) match
          case Some(sym) => check(sym)

          case None =>
            val flags = if isBranch then Flags.NSpace | Flags.Branch else Flags.NSpace
            val sym = ContainerSymbol.create(name, new NameTable, flags, Visibility.Default, nsSym, qualid.pos)
            nameTable.define(sym)
            sym

      case Ast.Ident(name) =>
        rootNameTable.resolveContainer(name) match
          case None =>
            val flags = if isBranch then Flags.NSpace | Flags.Branch else Flags.NSpace
            val sym = ContainerSymbol.create(name, new NameTable, flags, Visibility.Default, owner = null, qualid.pos)
            rootNameTable.define(sym)
            sym

          case Some(sym) => check(sym)


  private def index
      (defs: List[Ast.Def])
      (using defnLazy: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : List[DelayedDef[Def]] =

    val delayedDefs = new mutable.ArrayBuffer[DelayedDef[Def]]

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
  : List[DelayedDef[Def]] =

    defn match
      case fdef: Ast.FunDef =>
        transformFunDef(fdef, Flags.Fun, Effects.Policy.Infer) :: Nil

      case tdef: Ast.TypeDef =>
        transformTypeDef(tdef) :: Nil

      case pdef: Ast.ParamDef =>
        transformParamDef(pdef)

      case pdef: Ast.PatDef =>
        patternTyper.transformPatDef(pdef) :: Nil

      case cdef: Ast.ClassDef =>
        transformClassDef(cdef) :: Nil

      case idef: Ast.InterfaceDef =>
        transformInterfaceDef(idef) :: Nil

      case adef: Ast.AliasDef =>
        transformAliasDef(adef) :: Nil

      case section: Ast.Section =>
        transformSection(section) :: Nil

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
    end match
  end index

  extension (word: Word)
    def adapt(using tt: TargetType, defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars): Word =
      Checker.adapt(word, tt)

  def transform(word: Ast.Word)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
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

      case Ast.InterpolatedString(parts) =>
        transformInterpolatedString(parts, word.span).adapt

      case id: Ast.Ident =>
        transformIdent(id)

      case select: Ast.Select =>
        transformSelect(select)

      case viewAccess: Ast.ViewAccess =>
        transformViewAccess(viewAccess)

      case Ast.TypeAscribe(expr, tpt) =>
        val tpt2 = Checks.eager { transformType(tpt) }
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

      case Ast.TypeApply(fun, targs) => Checks.eager:
        val fun2 =
          given TargetType = TargetType.TypeApply
          transform(fun)
        val targs2 = targs.map(targ => transformType(targ))
        Checker.checkTypeApply(fun2, targs2, word.span).adapt

      case list: Ast.ListLit =>
        val ref = Ident(defn.List_List)(list.span)
        list.addKey(Namer.TypedWord, ref)
        transform(Ast.Apply(list, list.words)(list.span))

      case Ast.BracketApply(subject, args) =>
        val fun = Ast.Select(subject, "get")(subject.span)
        transform(Ast.Apply(fun, args)(word.span))

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

      case _for: Ast.For =>
        transform(Desugaring.desugarFor(_for)).adapt

      case assign: Ast.Assign =>
        transformAssign(assign).adapt

      case patmat: Ast.Match =>
        patternTyper.transformMatch(patmat).adapt

      case caseDef: Ast.CaseDef =>
        patternTyper.transformCaseDef(caseDef).adapt

      case vdef: Ast.ValDef =>
        val vdef2 = transformLocalValDef(vdef)
        sc.define(vdef2.symbol)
        vdef2.adapt

      case adef: Ast.AutoDef =>
        val adef2 = transformLocalAutoDef(adef)
        sc.define(adef2.symbol)
        adef2.adapt

      case fdef: Ast.FunDef => Checks.delayed: // checks after forcing

        val delayedDef = transformFunDef(fdef, Flags.Fun, Effects.Policy.Infer)
        // A function is available for checking its rhs
        sc.define(delayedDef.symbol)
        delayedDef.force().adapt

      case pdef: Ast.PatDef => Checks.delayed: // checks after forcing
        val delayedDef = patternTyper.transformPatDef(pdef)
        // A pattern predicate is available for checking its rhs
        sc.define(delayedDef.symbol)
        delayedDef.force().adapt

      case tdef: Ast.TypeDef => Checks.delayed: // checks after forcing
        val delayedDef = transformTypeDef(tdef)
        // A type definition is available for checking its rhs
        sc.define(delayedDef.symbol)
        delayedDef.force().adapt

      case block: Ast.Block =>
        transformBlock(block)
    }

  def transformIdent(id: Ast.Ident)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars): Word =
    given oob: OutOfBand = new OutOfBand

    val name = id.name

    def tryTermName(): Word =
      val sym = sc.resolveTerm(name, id.pos)
      handlePrefix(sym)

    def handlePrefix(sym: Symbol): Word =
      oob.testKey(Scope.PrefixKey) match
        case Some(prefix) =>
          // Normalize SAST
          val qual = Ident(prefix)(id.span.point)
          Select(qual, sym.name)(id.span)

        case _ =>
          Checker.checkCapture(sym, id.pos)
          Ident(sym)(id.span)

    tt match
      case _: TargetType.Member =>
        sc.resolveTerm(name) match
          case Some(sym) if sym.info.isValueType =>
            // Prefer values
            handlePrefix(sym).adapt

          case _ =>
            sc.resolveContainer(name) match
              case Some(sym) => Ident(sym)(id.span).adapt

              case None =>
                tryTermName().adapt

      case _ =>
        tryTermName().adapt

  def transformSelect(word: Ast.Select)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars): Word =
    val Ast.Select(qual, name) = word

    val qual2 =
      given TargetType = TargetType.Member(name)
      Inference.freshIsolate:
        transform(qual)

    val qualType = qual2.tpe
    def tryMember(isTerm: Boolean): Word =
      val memberOpt =
        if isTerm then qualType.getTermMember(name)
        else qualType.getContainerMember(name)

      memberOpt match
        case Some(tp) =>
          tp match
            case ref: RefType =>
              Checker.checkAccess(ref.symbol, sc.owner, word.span)
            case _ =>

          tp match
            case StaticRef(sym) if !sym.isType =>
              // record field type could be Int
              Ident(sym.dealias)(word.span)

            case _ =>
              Select(qual2, name)(word.span)

        case None =>
          // Error already reported
          errorWord(word.span)

    qualType match
      case StaticRef(sym) if sym.isContainer && tt.isInstanceOf[TargetType.Member] =>
        sym.nameTable.resolveContainer(name) match
          case Some(sym) =>
            Checker.checkAccess(sym, sc.owner, word.span)
            Ident(sym.dealias)(word.span).adapt

          case None =>
            tryMember(isTerm = true).adapt

      case _ =>
        tryMember(isTerm = true).adapt

  /** Transform a view access expression: value.view[ViewType]
    *
    * This uses Adaptation.adaptToView to handle both intrinsic and extension views.
    */
  def transformViewAccess(viewAccess: Ast.ViewAccess)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars): Word =
    val Ast.ViewAccess(value, astViewType) = viewAccess

    // Transform the value
    val value2 =
      given TargetType = TargetType.ValueType
      Inference.freshIsolate:
        transform(value)

    // Transform the view type
    val viewTypeTree = Checks.eager { transformType(astViewType) }
    val viewType = viewTypeTree.tpe

    // Use Adaptation.adaptToView to handle the view access
    Adaptation.adaptToView(value2, viewType) match
      case Adaptation.Result.Success(adaptedWord) =>
        adaptedWord.adapt

      case Adaptation.Result.Failure(trials) =>
        Reporter.error(s"${viewType.show} is not a view of ${value2.tpe.show}", viewAccess.pos)
        errorWord(viewAccess.span).adapt

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
        sc.resolveContainer(name) match
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
        sc.resolve(name, universe) match
          case None =>
            Reporter.error(s"Undefined $universe name: " + name, qualid.pos)
            None

          case res @ Some(_) =>
            assert(!oob.hasKey(Scope.PrefixKey), "Unexpected prefix for param: " + oob.getKey(Scope.PrefixKey))
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

  def transformBlock(block: Ast.Block)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
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
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word = Checks.eager:

    val classTree = transformType(newExpr.classType)

    def instantiateTypeLambda(tparams: List[Symbol]): List[TypeVar]  =
      for tparam <- tparams yield TypeVar(tparam.name, classTree.span)

    val instanceType =
      if classTree.tpe.isTypeLambda then
        classTree.tpe match
          case StaticRef(sym) =>
            val tparams = classTree.tpe.asTypeLambda.tparams
            val tvars = instantiateTypeLambda(tparams)
            val instanceType = AppliedType(sym, tvars)

            // Conditionally apply context instantiation
            Inference.conditionalInstantiate(instanceType, tt)

            instanceType

          case tp =>
            Reporter.error("Unexpected type in new expression: " + tp.show, classTree.pos)
            AnyType

      else
        classTree.tpe

    if !instanceType.isClassType then
      Reporter.error("A class name expected, found = " + classTree.tpe.show, newExpr.classType.pos)
      errorWord(newExpr.span)

    else
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

  /** Handles explicit postfix call syntax f(arg1, arg2, ...) */
  def transformCall(apply: Ast.Apply)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word =

    var fun =
      given TargetType = TargetType.Call
      transform(apply.fun)

    // Auto .apply insertion --- apply can be polymorphic
    //
    // The `.apply` insertion happens at the transform for `Apply`.
    // It ensures that in `Apply(fun, args)` the fun is an ident or select.
    fun.tpe.getTermMember("apply") match
      case Some(tp) if tp.isProcType => fun = fun.select("apply")

      case _ =>

    val funType = fun.tpe

    if funType.isProcType then
      if funType.isPolyType then
        fun = TreeOps.instantiatePoly(funType.asProcType, fun)

      val procType = fun.tpe.asProcType
      val paramSize = procType.paramTypes.size

      // Conditionally apply context instantiation
      Inference.conditionalInstantiate(procType.resultType, tt)

      val preArgTypes = procType.preParamTypes
      if preArgTypes.size != 0 then
        Reporter.error(
          s"The postfix call syntax cannot be used, as the function takes prefix arguments",
          fun.pos)
        errorWord(apply.span)

      else if apply.args.size != paramSize && !procType.hasVararg || apply.args.size < procType.minimumArgs then
        val mod = if procType.hasVararg then "at least " else ""
        val size = if procType.hasVararg then procType.minimumArgs else paramSize
        Reporter.error(
          s"The function expects $mod$size argument(s), found = ${apply.args.size}",
          apply.pos)
        errorWord(apply.span)

      else
        val argsTyped =
          if procType.hasVararg then
            transformVarargs(apply.args, procType.paramTypes, apply.span)
          else
            transformArgs(apply.args, procType.paramTypes)

        // Resolve auto parameters from local scope
        val call = Autos.resolve(fun, argsTyped, apply.span)

        call.adapt

    else
      if !fun.tpe.isError then
        Reporter.error(s"Not a function: " + fun.tpe.show, fun.pos)
      errorWord(apply.span)

  /** Check a dotless call such as `str1 + str2` */
  def transformDotlessCall(call: Ast.InfixOperatorCall)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word =

    val Ast.InfixOperatorCall(obj, meth, arg) = call
    val objWord =
      given TargetType = TargetType.ValueType
      Inference.freshIsolate:
        transform(obj)

    val objType = objWord.tpe
    val objSpan = obj.span

    if objType.isClassInfoType then
      objType.getTermMember(meth.name) match
        case Some(tp) =>
          var fun: Word = Select(objWord, meth.name)(objSpan | meth.span)

          if tp.isProcType then
            val originalProcType = tp.asProcType

            if tp.isPolyType then
              fun = TreeOps.instantiatePoly(originalProcType, fun)

            val procType = fun.tpe.asProcType
            val paramSize = procType.paramTypes.size

            // Conditionally apply context instantiation
            Inference.conditionalInstantiate(procType.resultType, tt)

            if paramSize != 1 then
              Reporter.error(
                s"The method ${meth.name} takes ${paramSize} parameters. The dotless call syntax only supports methods of one parameter",
                meth.span.toPos
              )
              errorWord(meth.span)

            else
              val paramType = procType.paramTypes.head
              val argTyped = transformArg(arg, paramType)
              Autos.resolve(fun, argTyped :: Nil, call.span).adapt
          else
            Reporter.error( s"The member ${meth.name} is not a method", meth.pos)
            errorWord(meth.span)

        case None =>
          Reporter.error( s"Object of the type ${objType.show} does not have member ${meth.name}", objSpan.toPos)
          errorWord(objSpan)
    else
      Reporter.error(s"Object type expected, found = " + objWord.tpe.show, objSpan.toPos)
      errorWord(objSpan)

  /** Handles infix call formed by expression typer `1 + 2` */
  def transformInfixCall(call: Ast.InfixCall)
    (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word =

    val Ast.InfixCall(preArgs, funAst, postArgs) = call

    var fun =
      // infix call should not trigger apply insertion
      given TargetType = TargetType.Call
      transform(funAst)

    if !fun.tpe.isProcType then
      Reporter.error("Expect a function, found = " + fun.tpe.show, funAst.pos)
      return errorWord(call.span)

    if fun.tpe.isPolyType then
      fun = TreeOps.instantiatePoly(fun.tpe.asProcType, fun)

    val procType = fun.tpe.asProcType
    val preParamCount = procType.preParamCount
    val postParamCount = procType.postParamCount

    // Conditionally apply context instantiation
    Inference.conditionalInstantiate(procType.resultType, tt)

    assert(!procType.hasVararg, "Infix call cannot have varargs")

    if preArgs.size != preParamCount then
      Reporter.error(
        s"Function ${fun.show} expects $preParamCount pre arguments, found = ${preArgs.size}",
        fun.pos)
      errorWord(call.span)

    else if postArgs.size != procType.postParamCount then
      Reporter.error(
        s"Function ${fun.show} expects $postParamCount post argument(s), found = ${postArgs.size}",
        fun.pos)
      errorWord(call.span)

    else
      val preArgs2 = transformArgs(preArgs, procType.preParamTypes)
      val postArgs2 =
        if procType.hasVararg then
          transformVarargs(postArgs, procType.postParamTypes, call.span)

        else
          transformArgs(postArgs, procType.postParamTypes)


      Autos.resolve(fun, preArgs2 ++ postArgs2, call.span).adapt

  /** Assumes that the argument count requirement is satisfied */
  def transformArgs
      (args: List[Ast.Word], params: List[Type])
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars)
  : List[Word] =

    for (arg, paramType) <- args.zip(params)
    yield transformArg(arg, paramType)

  def transformArg
      (arg: Ast.Word, paramType: Type)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars)
  : Word =
    if paramType.isFullyInstantiated then
      // Only propagate fully initialized type inside
      given TargetType = TargetType.Known(paramType)
      transform(arg)

    else
      // If paramType is not fully initialized, we cannot use adapters
      given TargetType = TargetType.ValueType
      val argTyped = transform(arg)
      if tvars.tryOrRevert { Subtyping.conforms(argTyped.tpe.widen, paramType) } then
        argTyped
      else
        Reporter.error(s"Expect type ${paramType.show}, found = ${argTyped.tpe.show}", arg.pos)
        errorWord(arg.span)


  /** Assumes that the argument count requirement is satisfied */
  def transformVarargs
      (args: List[Ast.Word], paramTypes: List[Type], span: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars)
  : List[Word] =

    val paramTypesFix :+ paramTypeFlex = paramTypes: @unchecked
    val (argsFix, argsFlex) = args.splitAt(paramTypesFix.size)

    val argsFixTyped = transformArgs(argsFix, paramTypesFix)

    val elementType = paramTypeFlex.stripVarargs

    var lastFlexArg: Word =
      val tapply = Ident(defn.List_empty)(span).appliedToTypes(elementType)
      Apply(tapply, args = Nil, autos = Nil)(span)

    def checkSplice(splice: Ast.Word, args: List[Ast.Word]): Unit =
      if args.size != 1 then
        Reporter.error(".. should be followed by exact one word, found = " + args.size, splice.pos)

      else
        val argTyped = Inference.freshIsolate:
          transformArg(args.head, paramTypeFlex)

        if !argTyped.tpe.isError then
          lastFlexArg = lastFlexArg.select("++").appliedTo(argTyped)

    for arg <- argsFlex do
      arg match
        case Ast.Expr(Ast.Ident("..") :: rest) =>
          checkSplice(arg, rest)

        case Ast.Apply(Ast.Ident(".."), args) =>
          checkSplice(arg, args)

        case _ =>
          val argTyped = transformArg(arg, elementType)
          if !argTyped.tpe.isError then
            lastFlexArg = lastFlexArg.select("+").appliedTo(argTyped)
      end match

    argsFixTyped :+ lastFlexArg

  def transformAssign(assign: Ast.Assign)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): Word =
    val Ast.Assign(lhs, rhs) = assign

    (lhs: @unchecked) match
      case id: Ast.Ident =>
        given oob: OutOfBand = new OutOfBand
        val sym = sc.resolveTerm(id.name, id.pos)

        Checker.checkMutable(sym, id.pos)

        val rhs2 =
          given TargetType = TargetType.Known(sym.info)
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
          Assign(id, rhs2)

      case Ast.Select(qual, name) =>
        val qual2 =
          given TargetType = TargetType.Member(name)
          Inference.freshIsolate:
            transform(qual)

        val qualType = qual2.tpe
        val isObject = qualType.isClassInfoType

        if isObject then
          qualType.getTermMember(name) match
            case Some(tp) =>
              tp match
                case ref: RefType =>
                  Checks.eager:
                    Checker.checkAccess(ref.symbol, sc.owner, lhs.span)

                case _ =>

              val isMutable =
                qualType.isClassInfoType && tp.is[RefType] && tp.as[RefType].symbol.isMutable

              if !isMutable then
                Reporter.error(s"The member $name is immutable", lhs.pos)

              val lhs2 = Select(qual2, name)(lhs.span)

              val rhs2 = Inference.freshIsolate:
                given TargetType = TargetType.Known(tp.widenTermRef)
                transform(rhs)

              FieldAssign(lhs2, rhs2)

            case None =>
              // error already reported
              errorWord(assign.span)

        else
          Reporter.error("Expect an object, found = " + qual2.tpe.show, qual.pos)
          errorWord(assign.span)

      case Ast.BracketApply(subject, args) =>
        val fun = Ast.Select(subject, "set")(subject.span)
        given TargetType = TargetType.VoidType
        Inference.freshIsolate:
          transform(Ast.Apply(fun, args :+ rhs)(assign.span))

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
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : Assign =

    val paramRef = transformParamRef(arg.paramRef)

    val rhs =
      given TargetType =
        if paramRef.tpe.isError then TargetType.ValueType
        else TargetType.Known(paramRef.symbol.info)

      Inference.freshIsolate:
        transform(arg.rhs)

    Assign(paramRef, rhs)

  private def transformInterpolatedString(parts: List[Ast.Word], span: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
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

  private def transformWhile(word: Ast.While)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): Word =
    val Ast.While(cond, body) = word

    val flowScope = new FlowScope(sc)

    val cond2 =
      given FlowScope = flowScope
      given TargetType = TargetType.Known(defn.BoolType)
      Inference.freshIsolate:
        FlowTyper.transformFlow(cond, this)

    val body2 =
      given TargetType = TargetType.VoidType
      given Scope = flowScope.fresh()

      Inference.freshIsolate:
        transform(body)

    While(cond2, body2)(word.span)

  private def transformIf(ifte: Ast.If)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars): Word =
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


  def transformIsExpr(isExpr: Ast.IsExpr)(using defn: Definitions, sc: FlowScope, rp: Reporter, so: Source): Word =
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
     val targetLambdaTypeOpt: Option[LambdaType] =
       tt.knownType.flatMap: tp =>
         if tp.isLambdaType then Some(tp.asLambdaType)
         else tp.getLambdaInterfaceType

     // Check parameter count if target type is known
     if targetLambdaTypeOpt.nonEmpty then
       val expect = targetLambdaTypeOpt.get.params.size
       if expect != params.size then
         Reporter.error(s"Expect a function with $expect parameters, found = ${params.size}", lambda.pos)
         return errorWord(lambda.span)

     val lambdaSym = TermSymbol.create("lambda", Flags.Fun | Flags.Synthetic, Visibility.Default, sc.owner, lambda.pos)
     val lambdaScope = sc.fresh(lambdaSym)

     def inferParamType(i: Int): Type =
       targetLambdaTypeOpt match
         case Some(lambdaType) => lambdaType.params(i)
         case None => TypeVar(params(i).name, params(i).span)

     val paramSyms = Checks.eager:
      for (param, i) <- params.zipWithIndex yield
        val tp = if param.tpt.isEmpty then inferParamType(i) else transformType(param.tpt).tpe
        val paramSym = TermSymbol.create(param.name, tp, Flags.Param, Visibility.Default, lambdaSym, param.pos)
        lambdaScope.define(paramSym)
        paramSym

     val bodyTargetType = targetLambdaTypeOpt match
       case Some(lambdaType) => TargetType.Known(lambdaType.resultType)
       case None => TargetType.ValueType

     val bodyTyped =
       given Scope = lambdaScope
       given TargetType = bodyTargetType
       transform(body)

     /* For closures, the effects stored in the type are different from those
      * raw effects computed from the code due to the capture behavior.
      */
     val receives =
       targetLambdaTypeOpt match
         case Some(lambdaType) => lambdaType.receives
         case None => Nil

     val res = Lambda(lambdaSym, paramSyms, receives, bodyTyped)(lambda.span)

     // Not really useful, but maintain the invariant that each symbol has info
     defn.add(lambdaSym, res.tpe)

     res

  private def transformParamDef(pdef: Ast.ParamDef)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : List[DelayedDef[Def]] =
    assert(pdef.default.isEmpty, "optional context param not desugared: " + pdef)

    val ip = lazyDefn.infoProvider

    // given definitions are lazy
    given Definitions = lazyDefn.value

    val extraFlags = pdef.getKeyOrElse(Desugaring.ExtraFlags)(Flags.empty)
    val flags = Checker.checkModifiers(pdef) | Flags.Context | extraFlags

    val paramSym = TermSymbol.create(pdef.name, flags, Checker.visibility(pdef, sc.owner), sc.owner, pdef.pos)
    ip.addLazy(paramSym, () => transformType(pdef.tpt).tpe)

    val paramDefSast = () =>
      val tpt = TypeTree(paramSym.info)(pdef.tpt.span)
      ParamDef(paramSym, tpt)(pdef.span)

    DelayedDef(paramSym, paramDefSast) :: Nil

  private def transformAliasDef(adef: Ast.AliasDef)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source)
  : DelayedDef[AliasDef] =
    val qualid = adef.qualid

    given ip: InfoProvider = lazyDefn.infoProvider

    val rawFlags = Checker.checkModifiers(adef)

    val kindFlags = adef.kind match
      case Ast.AliasKind.Def => Flags.Fun

      case Ast.AliasKind.Param => Flags.Context

      case Ast.AliasKind.Pattern => Flags.Fun

    val flags = rawFlags | kindFlags | Flags.Alias

    def error(message: String, pos: SourcePosition)(using Definitions): Ident =
      Reporter.error(message, pos)
      val sym = TermSymbol.create(adef.name, ErrorType, Flags.Synthetic, Checker.visibility(adef, sc.owner), sc.owner, qualid.pos)
      Ident(sym)(qualid.span)

    def getTarget(qual: Ast.RefTree, nameTable: NameTable, targetName: String)(using Definitions): Ident =
      adef.kind match
        case Ast.AliasKind.Def =>
          nameTable.resolveTerm(targetName) match
            case Some(sym) =>
              if sym.is(Flags.Alias) then error("Cannot alias another alias", qualid.pos)
              else if sym.isFunction then Ident(sym)(qualid.span)
              else error("The member " + targetName + " is not a function", qualid.pos)

            case _ =>
              error("The prefix does not have a term member " + targetName, qual.pos)

        case Ast.AliasKind.Param =>
          nameTable.resolveTerm(targetName) match
            case Some(sym) =>
              if sym.is(Flags.Alias) then error("Cannot alias another alias", qualid.pos)
              else if sym.is(Flags.Context) then Ident(sym)(qualid.span)
              else error("The member " + targetName + " is not a context parameter", qualid.pos)

            case _ =>
              error("The prefix does not have a term member " + targetName, qual.pos)

        case Ast.AliasKind.Pattern =>
          nameTable.resolvePattern(targetName) match
            case Some(sym) =>
              if sym.is(Flags.Alias) then error("Cannot alias another alias", qualid.pos)
              else if sym.isFunction then Ident(sym)(qualid.span)
              else error("The member " + targetName + " is not a pattern definition", qualid.pos)

            case _ =>
              error("The prefix does not have a pattern member " + targetName, qual.pos)


    lazy val target: Ident =
      given Definitions = lazyDefn.value

      qualid match
        case Ast.Select(qual, name) =>
          val prefix = qual.asInstanceOf[Ast.RefTree]
          Imports.resolveContainer(prefix, sc, lazyDefn.rootNameTable, allowBranch = true) match
            case Some(nameTable) =>
              val target = getTarget(prefix, nameTable, name)

              if !target.symbol.info.isError then
                Checker.checkAccess(target.symbol, sc.owner, target.span)

              target

            case None =>
              // error already reported
              val sym = TermSymbol.create(name, ErrorType, Flags.Synthetic, Visibility.Default, sc.owner, qualid.pos)
              Ident(sym)(qualid.span)
          end match

        case ident =>
          error("A fully qualified name to alias target expected", ident.pos)


    val aliasSym =
      adef.kind match
        case Ast.AliasKind.Def | Ast.AliasKind.Param =>
          TermSymbol.create(adef.name, flags, Checker.visibility(adef, sc.owner), sc.owner, adef.ident.pos)

        case Ast.AliasKind.Pattern =>
          PatternSymbol.create(adef.name, flags, Checker.visibility(adef, sc.owner), sc.owner, adef.ident.pos)

    ip.addLazy(aliasSym, () => StaticRef(target.symbol))

    val aliasDefSast = () =>
      AliasDef(aliasSym, target)(adef.span)

    DelayedDef(aliasSym, aliasDefSast)

  private def transformLocalValDef(vdef: Ast.ValDef)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): ValDef =
    var flags = Checker.checkModifiers(vdef)
    if vdef.mutable then flags = flags | Flags.Mutable

    val sym = TermSymbol.create(vdef.name, flags, Visibility.Default, sc.owner, vdef.ident.pos)

    lazy val givenType: Type = Checks.eager:
      val tpt = transformType(vdef.tpt)
      val tp2 = Checker.checkValueType(tpt.tpe, tpt.pos)
      tp2

    val rhs: Word =
      given Scope = sc.fresh()
      given TargetType =
        if vdef.tpt.isEmpty then TargetType.ValueType
        else TargetType.Known(givenType)

      Inference.freshIsolate:
        transform(vdef.rhs)

    val tp: Type =
      if vdef.tpt.isEmpty then rhs.tpe.widen
      else givenType

    defn.add(sym, tp)

    ValDef(sym, rhs)(vdef.span)

  private def transformLocalAutoDef(adef: Ast.AutoDef)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): ValDef =
    // Auto definitions always have explicit types and are marked with Auto flag
    val flags = Checker.checkModifiers(adef) | Flags.Auto

    val sym = TermSymbol.create(adef.name, flags, Visibility.Default, sc.owner, adef.ident.pos)

    val givenType: Type = Checks.eager:
      val tpt = transformType(adef.tpt)
      val tp2 = Checker.checkValueType(tpt.tpe, tpt.pos)
      tp2

    val rhs: Word =
      given Scope = sc.fresh()
      given TargetType = TargetType.Known(givenType)

      Inference.freshIsolate:
        transform(adef.rhs)

    defn.add(sym, givenType)

    // Auto definitions are transformed to ValDef with Auto flag
    ValDef(sym, rhs)(adef.span)

  def transformTypeParams(tparams: List[Ast.TypeParam])
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : List[TypeSymbol] =
    for tparam <- tparams yield
      val bound =
        if tparam.bound.isEmpty then
          TypeBound(BottomType, AnyType)
        else
          val boundTree = transformType(tparam.bound)
          TypeBound(BottomType, boundTree.tpe)

      // Only support simple-kinded type parameters
      val sym = TypeSymbol.create(Kind.Simple, tparam.name, bound, Flags.Param, Visibility.Default, sc.owner, tparam.pos)
      sc.define(sym)
      sym

  def transformParams(params: List[Ast.Param])
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : List[Symbol] =

    for (param, i) <- params.zipWithIndex yield
      val tpt = transformType(param.tpt, allowPackType = i == params.size - 1)
      val paramSym = TermSymbol.create(param.name, tpt.tpe, Flags.Param, Visibility.Default, sc.owner, param.pos)
      sc.define(paramSym)
      paramSym


  def transformAutos(autos: List[Ast.Auto])
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : List[Symbol] =

    for auto <- autos yield
      val tpt = transformType(auto.tpt, allowPackType = false)
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

  private def transformFunDef(funDef: Ast.FunDef, initialFlags: Flags, policy: Effects.Policy)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : DelayedDef[FunDef] =

    val extraFlags = funDef.getKeyOrElse(Desugaring.ExtraFlags)(Flags.empty)
    val flags = Checker.checkModifiers(funDef) | initialFlags | extraFlags

    val funSym = TermSymbol.create(funDef.name, flags, Checker.visibility(funDef, sc.owner), sc.owner, funDef.ident.pos)
    given Scope = sc.fresh(funSym)

    given defn: Definitions = lazyDefn.value

    if flags.is(Flags.Defer) then
      if funDef.resultType.isEmpty then
        Reporter.error("A deferred definition should have explicit result type", funDef.ident.pos)

      // view accessor have such flags
      if sc.owner.isLocal then
        Reporter.error("A deferred definition should be at top-level", funDef.ident.pos)

    else if Config.explicitReturnType.value && funDef.resultType.isEmpty then
      Reporter.error("This project requires functions to have explicit return type", funDef.ident.pos)

    lazy val tparamSyms =
      transformTypeParams(funDef.tparams)

    lazy val paramSyms =
      tparamSyms
      transformParams(funDef.params)

    lazy val autoSyms =
      tparamSyms
      transformAutos(funDef.autos)

    lazy val candidates =
      funDef.autos.zip(autoSyms).map: (auto, autoSym) =>
        Autos.check(auto.candidates, autoSym.info, this)

    lazy val givenResultType =
      tparamSyms

      assert(!funDef.resultType.isEmpty)
      val resTypeTree = transformType(funDef.resultType)
      Checker.checkValueType(resTypeTree)

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
    lazy val resultType =
      if !funDef.resultType.isEmpty then
        givenResultType
      else
        typedBody.tpe.widen
      end if

    lazy val typedBody =
      paramSyms
      autoSyms

      if flags.is(Flags.Defer) && !flags.is(Flags.Default) then
        // Dummy body deferred function without default implementation
        val dummyBody = Block(Nil)(funDef.body.span)
        if funDef.resultType.isEmpty then dummyBody.encodedAs(defn.UnitType)
        else dummyBody.encodedAs(givenResultType)
      else
        val targetType =
          if !funDef.resultType.isEmpty then
            TargetType.Known(givenResultType)
          else
            TargetType.ValueType

        Inference.freshIsolate:
          given TargetType = targetType
          transform(funDef.body)

    lazy val effectPolicy = transformReceives(funDef.receives, policy)

    /* For object closures, the effects of a method symbol stored in the type is
     * different from those raw effects computed from the code due to the
     * capture behavior.
     */
    val receivesInfo =
      effectPolicy.bound match
        case Some(effs) => effs
        case None => funSym

    def computeInfo(resultType: Type) =
      val candidateSymbols = candidates.map(_._2)

      ProcType(
        tparamSyms, paramSyms.map(_.toNamedInfo), autoSyms.map(_.toNamedInfo), candidateSymbols,
        resultType, receivesInfo, funDef.preParamCount)

    val ip = lazyDefn.infoProvider
    ip.addLazy(funSym, () => computeInfo(resultType), () => computeInfo(ErrorType))

    val typer = () =>
      val candidateTrees = candidates.map(_._1)
      val tpt = TypeTree(resultType)(funDef.resultType.span)
      FunDef(funSym, tparamSyms, paramSyms, autoSyms, candidateTrees, tpt, effectPolicy, typedBody)(funDef.span)

    DelayedDef(funSym, typer)

  private def transformConstructor(funDef: Ast.FunDef, thisSym: Symbol, classSym: Symbol)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : DelayedDef[FunDef] =

    val flags = Flags.Fun | Flags.Method

    val visibility = Checker.visibility(funDef, classSym)
    val funSym = TermSymbol.create(Names.Constructor, flags, visibility, classSym, funDef.ident.pos)
    given ctorScope: Scope = sc.fresh(funSym)

    if funDef.tparams.nonEmpty then
      Reporter.error("Constructor may not take type parameters", funDef.tparams.head.pos)

    given defn: Definitions = lazyDefn.value

    lazy val paramSyms =
      transformParams(funDef.params)

    lazy val autoSyms =
      transformAutos(funDef.autos)

    lazy val candidates =
      funDef.autos.zip(autoSyms).map: (auto, autoSym) =>
        Autos.check(auto.candidates, autoSym.info, this)

    lazy val resultType =
      if !funDef.resultType.isEmpty then
        val resTypeTree = transformType(funDef.resultType)
        val res = resTypeTree.tpe

        if !Subtyping.isEqualType(res, thisSym.info) then
          Reporter.error("The result type of constructor should be the same as the class", funDef.resultType.pos)

      thisSym.info

    def checkBody(stats: List[Ast.Word]): Word =
      val classInfo = classSym.classInfo
      val uninitialized = mutable.Set.from(classInfo.fields)
      val words = new mutable.ArrayBuffer[Word]

      // Create prefixed scope for accessing constructor parameters and class fields
      // Constructor parameters inherited from ctorScope, class fields via prefix, initialized fields added incrementally
      val fieldScope = ctorScope.freshPrefixedScope(prefix = thisSym, owner = funSym)
      given blockScope: Scope = fieldScope.fresh()

      // Process all statements
      for stat <- stats do
        stat match
          case Ast.Assign(lhs @ Ast.Select(qual @ Ast.Ident("this"), name), rhs) =>
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

                  // Add this field to scope for subsequent field initializations
                  fieldScope.define(sym)

                  // make `this` available once all fields are initialized
                  if uninitialized.isEmpty then
                    blockScope.define(thisSym)

              case None =>
                Reporter.error("The field " + name + " does not exist in class " + classSym, lhs.pos)

          case _ =>
            // Regular statement - check with or without `this` depending on initialization state
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

    lazy val typedBody =
      paramSyms
      autoSyms

      funDef.body match
        case Ast.Block(stats) =>
          checkBody(stats)

        case _ =>
          Reporter.error("Constructor body must be a block", funDef.body.pos)
          errorWord(funDef.body.span)

    lazy val effectPolicy = transformReceives(funDef.receives, Effects.Policy.Infer)

    val tparamSyms = Nil
    def computeInfo(resultType: Type) =
      val candidateSymbols = candidates.map(_._2)

      ProcType(
        tparamSyms, paramSyms.map(_.toNamedInfo), autoSyms.map(_.toNamedInfo), candidateSymbols,
        resultType, funSym, funDef.preParamCount)

    val ip = lazyDefn.infoProvider
    ip.addLazy(funSym, () => computeInfo(resultType), () => computeInfo(ErrorType))

    val typer = () =>
      val candidateTrees = candidates.map(_._1)
      val tpt = TypeTree(resultType)(funDef.resultType.span)
      FunDef(funSym, tparamSyms, paramSyms, autoSyms, candidateTrees, tpt, effectPolicy, typedBody)(funDef.span)

    DelayedDef(funSym, typer)

  private def transformTypeDef(tdef: Ast.TypeDef)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : DelayedDef[TypeDef] =

    val flags = Checker.checkModifiers(tdef)
    val kind = Kind.simpleKinded(tdef.tparams.size)
    val typeSym = TypeSymbol.create(kind, tdef.name, flags, Checker.visibility(tdef, sc.owner), sc.owner, tdef.ident.pos)

    given defn: Definitions = lazyDefn.value

    given sc2: Scope = sc.fresh(typeSym)
    lazy val tparamSyms = transformTypeParams(tdef.tparams)

    def computeInfo(): Type =
      // force creation of symbols for type parameters
      tparamSyms

      if tdef.tparams.isEmpty then
        if tdef.rhs.isEmpty then
          if sc.owner == defn.Predef then
            val typeName = tdef.name
            if typeName == "Any" then AnyType
            else if typeName == "Bottom" then BottomType
            else
              // Int, Char, Byte
              TypeBound(BottomType, AnyType)

          else
            TypeBound(BottomType, AnyType)
        else
          val rhsTree = transformType(tdef.rhs)
          val rhs = Checker.checkValueType(rhsTree)

          if TypeOps.hasCyclesInType(typeSym, rhs) then
            Reporter.error("Cycles detected for the type definition " + typeSym, tdef.ident.pos)
            ErrorType
          else
            if tdef.isBound then
              TypeBound(BottomType, rhs)
            else
              rhs

      else
        if tdef.rhs.isEmpty then
          TypeLambda(tparamSyms, TypeBound(BottomType, AnyType), tdef.preParamCount)

        else
          val rhsTree = transformType(tdef.rhs)
          val rhs = Checker.checkValueType(rhsTree)

          if TypeOps.hasCyclesInType(typeSym, rhs) then
            Reporter.error("Cycles detected for the type definition " + typeSym, tdef.ident.pos)
            TypeLambda(tparamSyms, ErrorType, tdef.preParamCount)

          else
            val rhsType =
              if tdef.isBound then TypeBound(BottomType, rhs)
              else rhs

            TypeLambda(tparamSyms, rhsType, tdef.preParamCount)

    end computeInfo

    val errorType = () =>
      if tdef.tparams.isEmpty then ErrorType
      else TypeLambda(tparamSyms, ErrorType, tdef.preParamCount)

    val ip = lazyDefn.infoProvider
    ip.addLazy(typeSym, computeInfo, errorType)

    // check type symbols after completion to allow cycles, type A = A
    val typer = () => TypeDef(typeSym)(tdef.span)

    DelayedDef(typeSym, typer)


  private def transformClassDef(cdef0: Ast.ClassDef)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : DelayedDef[ClassDef] =

    val extraFlags = cdef0.getKeyOrElse(Desugaring.ExtraFlags)(Flags.empty)
    val flags = Checker.checkModifiers(cdef0) | extraFlags | Flags.Class
    val kind = Kind.simpleKinded(cdef0.tparams.size)
    val classSym = TypeSymbol.create(kind, cdef0.name, flags, Checker.visibility(cdef0, sc.owner), sc.owner, cdef0.ident.pos)
    val thisSym = TermSymbol.create("this", Flags.Synthetic, Visibility.Default, classSym, cdef0.ident.pos)

    // Class desugaring now happens in Desugaring.synthesize
    val cdef = Desugaring.desugarClassDef(cdef0, thisSym)

    given paramScope: Scope = sc.fresh(classSym)

    lazy val tparamSyms =
      given Definitions = lazyDefn.value
      transformTypeParams(cdef.tparams)

    val fields = new mutable.ArrayBuffer[Symbol]
    val methods = new mutable.ArrayBuffer[Symbol]

    lazy val classInfo: Type =
      val base = new ClassInfo(classSym, tparamSyms, tparamSyms.map(StaticRef.apply), thisSym, fields.toList, methods.toList)

      if cdef.tparams.isEmpty then base
      else TypeLambda(tparamSyms, base, preParamCount = 0)

    val ip = lazyDefn.infoProvider
    ip.addLazy(classSym, () => classInfo)

    // Add this to scope
    val thisScope = paramScope.fresh()
    thisScope.define(thisSym)
    val shortCutScope = thisScope.freshPrefixedScope(prefix = thisSym, owner = classSym)

    lazy val thisInfo: Type =
      val classRef = StaticRef(classSym)
      if tparamSyms.isEmpty then classRef
      else AppliedType(classSym, tparamSyms.map(StaticRef.apply))

    ip.addLazy(thisSym, () => thisInfo)

    val delayedDefs = new mutable.ArrayBuffer[DelayedDef[FunDef]]

    for vdef <- cdef.vals do
      var flags = Checker.checkModifiers(vdef) | vdef.getKeyOrElse(Desugaring.ExtraFlags)(Flags.empty)
      if vdef.mutable then flags = flags | Flags.Field | Flags.Mutable
      else flags = flags | Flags.Field

      val sym = TermSymbol.create(vdef.name, flags, Checker.visibility(vdef, classSym), classSym, vdef.ident.pos)
      shortCutScope.define(sym)

      def checkType() =
        given defn: Definitions = lazyDefn.value
        val tpt = transformType(vdef.tpt)
        val tp2 = Checker.checkValueType(tpt.tpe, tpt.pos)
        tp2

      if vdef.name == cdef.name then
        Reporter.error("Class name cannot be used as field name", vdef.pos)

      else
        ip.addLazy(sym, () => checkType())
        fields += sym

    for fdef <- cdef.funs do
      given Scope = shortCutScope

      if fdef.preParamCount != 0 then
        Reporter.error("Methods cannot have pre-arguments", fdef.pos)

      val delayedDef =
        if fdef.name == cdef.name then
          // Constructor is checked with outer scope
          given Scope = paramScope
          transformConstructor(fdef, thisSym, classSym)

        else
          transformFunDef(fdef, Flags.Fun | Flags.Method, Effects.Policy.Infer)


      methods += delayedDef.symbol

      // Operator name should not be called directly without a prefix
      if !Naming.isOperator(delayedDef.symbol.name) then
        shortCutScope.define(delayedDef.symbol)

      delayedDefs += delayedDef

    val typer = () =>
      given Definitions = lazyDefn.value

      val funs: List[FunDef] =
        for delayedDef <- delayedDefs.toList yield delayedDef.force()

      ClassDef(classSym, thisSym, tparamSyms, fields.toList, funs)(cdef.span)

    DelayedDef(classSym, typer)

  private def transformInterfaceDef(idef: Ast.InterfaceDef)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : DelayedDef[InterfaceDef] =

    val flags = Checker.checkModifiers(idef) | Flags.Interface
    val kind = Kind.simpleKinded(idef.tparams.size)
    val interfaceSym = TypeSymbol.create(kind, idef.name, flags, Checker.visibility(idef, sc.owner), sc.owner, idef.ident.pos)
    val selfSym = TermSymbol.create("this", Flags.Synthetic, Visibility.Default, interfaceSym, idef.ident.pos)

    given paramScope: Scope = sc.fresh(interfaceSym)

    lazy val tparamSyms =
      given Definitions = lazyDefn.value
      transformTypeParams(idef.tparams)

    val methods = new mutable.ArrayBuffer[Symbol]

    lazy val interfaceInfo: Type =
      // Reuse ClassInfo but with empty fields
      val base = new ClassInfo(interfaceSym, tparamSyms, tparamSyms.map(StaticRef.apply), selfSym, Nil, methods.toList)

      if idef.tparams.isEmpty then base
      else TypeLambda(tparamSyms, base, preParamCount = 0)

    val ip = lazyDefn.infoProvider
    ip.addLazy(interfaceSym, () => interfaceInfo)

    // Add self to scope for use in default method implementations
    val selfScope = paramScope.fresh()
    selfScope.define(selfSym)
    val shortCutScope = selfScope.freshPrefixedScope(prefix = selfSym, owner = interfaceSym)

    lazy val selfInfo: Type =
      val interfaceRef = StaticRef(interfaceSym)
      if tparamSyms.isEmpty then interfaceRef
      else AppliedType(interfaceSym, tparamSyms.map(StaticRef.apply))

    ip.addLazy(selfSym, () => selfInfo)

    val delayedDefs = new mutable.ArrayBuffer[DelayedDef[FunDef]]
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

    val typer = () =>
      given Definitions = lazyDefn.value

      val methodDefs: List[FunDef] =
        for delayedDef <- delayedDefs.toList yield delayedDef.force()

      InterfaceDef(interfaceSym, selfSym, tparamSyms, methodDefs)(idef.span)

    DelayedDef(interfaceSym, typer)

  private def transformSection
      (section: Ast.Section)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : DelayedDef[Section] =

    val flags = Checker.checkModifiers(section) | Flags.Section
    val nameTable = new NameTable
    val sym = ContainerSymbol.create(section.name, nameTable, flags, Checker.visibility(section, sc.owner), sc.owner, section.ident.pos)

    given secScope: Scope = sc.fresh(sym, nameTable)

    val delayedDefs = index(section.defs)
    nameTable.freeze()

    lazy val sast =
      given Definitions = lazyDefn.value
      val defs = for delayed <- delayedDefs.toList yield delayed.force()

      Section(sym, defs)(section.span)

    DelayedDef(sym, () => sast)

  /** Type check type tree
    *
    * Checks must be delayed by using `checks.add`.
    */
  def transformType(tpt: Ast.TypeTree, allowPackType: Boolean = false)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : TypeTree =

    def check(sym: Symbol) =
      if sym == defn.Predef_Pack && !allowPackType then
        Reporter.error(".. not allowed here. It can only be used as the type of the last varargs parameter.", tpt.pos)

    tpt.getKeyOrElse(Namer.TypedTypeTree):
      tpt match
      case Ast.Ident(name) =>
        val sym = sc.resolveType(name, tpt.pos)
        check(sym)
        TypeTree(StaticRef(sym))(tpt.span)

      case Ast.Select(qual, name) =>
        resolveContainer(qual.asInstanceOf[Ast.RefTree]) match
          case Some(sym) =>
            sym.nameTable.resolveType(name) match
              case Some(sym) =>
                check(sym)
                Checker.checkAccess(sym, sc.owner, tpt.span)
                val tp = StaticRef(sym)
                TypeTree(tp)(tpt.span)

              case None =>
                Reporter.error(s"The namespace $sym does not contain the type member $name", qual.pos)
                TypeTree(ErrorType)(tpt.span)

          case _ =>
            TypeTree(ErrorType)(tpt.span)

      case tpt: Ast.ExprType  =>
        exprTyper.transformType(tpt, allowPackType)

      case Ast.RecordType(fields) =>
        val fieldTypes = new mutable.ArrayBuffer[NamedInfo[Type]]
        for field <- fields do
          if fieldTypes.exists(_.name == field.name) then
            Reporter.error("Field " + field.name + " already defined", field.pos)
          else
            val tpt = transformType(field.tpt)
            val tp = Checker.checkValueType(tpt)
            fieldTypes += NamedInfo(field.name, tp)
        end for
        TypeTree(RecordType(fieldTypes.toList))(tpt.span)

      case Ast.UnionType(branches) =>
        val branchTypes = new mutable.ArrayBuffer[Type]
        val classes = mutable.Set.empty[Symbol]
        for branch <- branches do
          val branchType = transformType(branch).tpe
          val branchClasses =
            if branchType.isClassType then
              branchType.asClassInfo.classSymbol :: Nil

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
        val numericTypes = branchTypes.filter(defn.isNumericType)

        if numericTypes.size > 1 then
          val typeNames = numericTypes.map(_.show).mkString(", ")
          Reporter.error(
            s"Union type cannot contain multiple numeric types: ($typeNames)",
            tpt.pos
          )

        val unionType = UnionType(branchTypes.toList)
        TypeTree(unionType)(tpt.span)

      case Ast.DuckType(baseTypeTpt, adapters) =>
        val baseTypeTree = transformType(baseTypeTpt)
        val baseType = baseTypeTree.tpe

        // Check that we have at least one adapter
        if adapters.isEmpty then
          Reporter.error("Duck type must have at least one adapter", tpt.pos)
          TypeTree(ErrorType)(tpt.span)
        else
          // Check and validate adapters - they should convert TO the base type
          lazy val adaptersChecked = Adapters.check(adapters, baseType, this)

          Checks.add { adaptersChecked }

          if adaptersChecked.isEmpty then
            // All adapters were invalid
            TypeTree(baseType)(tpt.span)

          else if baseType.adapters.nonEmpty then
            // Base type already has adapters (e.g., it's a duck type)
            Reporter.error("Duck type base type cannot have adapters", baseTypeTpt.pos)
            TypeTree(baseType)(tpt.span)

          else
            val duckType = DuckType(baseType)(() => adaptersChecked)
            TypeTree(duckType)(tpt.span)

      case Ast.ViewType(baseTypeTpt, views) =>
        val baseTypeTree = transformType(baseTypeTpt)
        val baseType = baseTypeTree.tpe

        // Check that base type is not a ViewType (nested view types are invalid)
        if baseType.extensionViews.nonEmpty then
          Reporter.error(s"Nested view types are not allowed: base type ${baseType.show} is itself a view type", baseTypeTpt.pos)
          TypeTree(baseType)(tpt.span)

        // Check that we have at least one view
        else if views.isEmpty then
          Reporter.error("View type must have at least one view", tpt.pos)
          TypeTree(ErrorType)(tpt.span)

        else
          // Convert AST ViewSpec to SAST ViewSpec
          lazy val viewsChecked: List[ViewSpec] =
            // First, create all view specs (resolve adapters)
            val viewSpecs = views.map: astViewSpec =>
              val viewTypeTree = transformType(astViewSpec.tpe)
              val viewType = viewTypeTree.tpe

              val adapter = astViewSpec.adapter.flatMap: adapterRef =>
                resolveQualid(adapterRef, Universe.Term)

              ViewSpec(viewType, adapter)

            // Then validate all view specs together (checks coherence)
            ViewChecker.checkViewSpecs(viewSpecs, baseType, views)

          Checks.add { viewsChecked }

          val viewType = ViewType(baseType)(() => viewsChecked)
          TypeTree(viewType)(tpt.span)

      case Ast.AppliedType(tctor, targs) =>
        val tctor2 = transformType(tctor, allowPackType)
        val targs2 = for targ <- targs yield transformType(targ, allowPackType = false)
        tctor2.tpe match
          case StaticRef(tctorSym) =>
            if tctor2.tpe == ErrorType || !Checker.checkKind(tctor2, targs2) then
              TypeTree(ErrorType)(tpt.span)
            else
              val tp = AppliedType(tctorSym, targs2.map(_.tpe))
              Checks.add {
                val tl = tctor2.tpe.asTypeLambda
                Checker.checkBounds(tl.tparams, targs2)
              }
              TypeTree(tp)(tpt.span)

          case tp =>
            Reporter.error("A type reference expected, found = " + tp.show, tctor.pos)
            TypeTree(ErrorType)(tpt.span)

      case Ast.FunctionType(paramTypes, resType, receives) =>
        val paramTypes2 =
          for paramType <- paramTypes yield
            val tpt = transformType(paramType)
            Checker.checkValueType(tpt)

        val effs =
          for
            param <- receives
          yield
            transformParamRef(param).symbol

        val resType2 = transformType(resType)
        val resTypeChecked = Checker.checkValueType(resType2)

        val lambdaType = LambdaType(paramTypes2, resTypeChecked, effs)
        TypeTree(lambdaType)(tpt.span)

      case _: Ast.EmptyTypeTree =>
        Reporter.abort("Unexpected empty type tree", tpt.pos)

object Namer:
  /** The typed word associated with an untyped word
    *
    * It is used to avoid re-typing a word.
    */
  val TypedWord = new KeyProps.Key[Word]("Namer.TypedWord")

  val TypedTypeTree = new KeyProps.Key[TypeTree]("Namer.TypedTypeTree")
