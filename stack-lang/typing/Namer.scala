package typing

import ast.{ Trees => Ast }
import ast.Desugaring
import ast.Name
import ast.Positions.*

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import common.Debug
import common.KeyProps
import common.OutOfBand

import reporting.Reporter

import Namer.{ DelayedDef, errorWord }
import Inference.*

import scala.collection.mutable

/**
  * The namer handles name resolution and desugaring.
  *
  * It converts ASTs to Semantic ASTs.
  *
  * This phase only deals with type checking. Effect inference are only hooked
  * but not performed.
  *
  * It is important to NOT trigger effect inference and effect check during type
  * checking.
  */
class Namer:
  val checker = new Checker(this)
  val patternTyper = PatternTyper(this, checker)
  val inferencer: Inferencer = new UnificationSolver
  val exprTyper = new ExprTyper(this)
  val autoResolver = new Autos(this)

  def transform
      (nss: List[Ast.Namespace], rootNameTable: NameTable, predef: NameTable)
      (using defnLazy: Definitions.Lazy, rp: Reporter)
  : List[Namespace] =

    given ip: InfoProvider = defnLazy.infoProvider

    val delayedImports = new mutable.ArrayBuffer[() => Unit]
    val delayedAliases = new mutable.ArrayBuffer[() => Unit]
    val delayedNamespaces = new mutable.ArrayBuffer[DelayedDef[Namespace]]

    for ns <- nss do
      given source: Source = Reporter.source(ns.source)

      val nsSym = resolveNamespace(ns.qualid, rootNameTable, isBranch = false)
      val memberTable = ip.info(nsSym).as[ContainerInfo].nameTable

      val topScope = new Scope.RootScope(new NameTable, nsSym)
      // Make current namespace name available
      topScope.define(nsSym)

      // Predef names are by-default accessible. However, other namespaces are
      // not accessible unless explicitly imported.
      val predefScope = topScope.fresh(nsSym, predef)

      val importScope: Scope = predefScope.fresh()
      val defsScope: Scope = importScope.fresh(nsSym, memberTable)

      val delayedDefs =
        given Scope = defsScope
        index(ns.defs)

      delayedAliases += { () =>
        // handle aliases after indexing members
        for case alias: Ast.AliasDef <- ns.defs do
          Imports.doImport(alias.qualid, defsScope, rootNameTable, isAlias = true)

        // No more members allowed after handling aliasing
        memberTable.freeze()
      }

      val imports = new mutable.ArrayBuffer[Symbol]

      delayedImports += { () =>
        // handle imports after indexing members
        for imp <- ns.imports do
          imports ++= Imports.doImport(imp.qualid, importScope, rootNameTable, isAlias = false)
      }

      delayedNamespaces += DelayedDef(nsSym, { () =>
        given Definitions = defnLazy.value
        val defs = for delayed <- delayedDefs.toList yield delayed.force()
        Namespace(nsSym, imports.toList, defs)(ns.span)
      })
    end for

    // Aliasing will ignore members that are alised
    //
    // Explicit aliasing another alised definition is an error.
    delayedAliases.foreach(_.apply())
    delayedImports.foreach(_.apply())

    val namespaces =
      for delayedDef <- delayedNamespaces
      yield delayedDef.delayed() <| delayedDef.symbol.sourcePos.source.file

    checker.performDelayedChecks() <| "checker"
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
        rp.error(s"The $name is already defined as a member at $pos", qualid.pos)
        val flags = if isBranch then Flags.NSpace | Flags.Branch else Flags.NSpace
        val dummyNamespace = Symbol.createSymbol(sym.name, flags, qualid.pos)
        ip.add(dummyNamespace, ip(sym).owner, new ContainerInfo(new NameTable))
        dummyNamespace

    qualid match
      case Ast.Select(qual, name) =>
        assert(qual.isInstanceOf[Ast.RefTree], "Unexpected qualid = " + qualid)
        val nsSym = resolveNamespace(qual.asInstanceOf[Ast.RefTree], rootNameTable, isBranch = true)

        assert(nsSym.isNamespace, "Not a namespace " + nsSym)
        val nameTable = ip.info(nsSym).as[ContainerInfo].nameTable

        nameTable.resolveTerm(name) match
          case Some(sym) => check(sym)

          case None =>
            val flags = if isBranch then Flags.NSpace | Flags.Branch else Flags.NSpace
            val sym = Symbol.createSymbol(name, flags, qualid.pos)
            ip.add(sym, nsSym, new ContainerInfo(new NameTable))
            nameTable.define(sym)
            sym

      case Ast.Ident(name) =>
        rootNameTable.resolveTerm(name) match
          case None =>
            val flags = if isBranch then Flags.NSpace | Flags.Branch else Flags.NSpace
            val sym = Symbol.createSymbol(name, flags, qualid.pos)
            rootNameTable.define(sym)
            ip.add(sym, owner = null, new ContainerInfo(new NameTable))
            sym

          case Some(sym) => check(sym)


  private def index
      (defs: List[Ast.Def])
      (using defnLazy: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source)
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
      (using defnLazy: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source)
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

      case adef: Ast.AliasDef =>
        // Handled in namespace and sections specially
        Nil

      case section: Ast.Section =>
        transformSection(section) :: Nil

      case _: Ast.DataDef | _: Ast.EnumDef  =>
        Reporter.error("[Internal Error] Data definition should have be desugared", defn.pos)
        Nil

      case vdef: Ast.ValDef =>
        Reporter.error("Unexpected top-level value definitions", vdef.pos)
        Nil
    end match
  end index

  extension (word: Word)
    def adapt(using tt: TargetType, defn: Definitions, sc: Scope, rp: Reporter, so: Source): Word =
      checker.adapt(word, tt)

  def transform(word: Ast.Word)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    Debug.trace(s"Typing ${word.show}", (_: Word).show, enable = false) {
    word.testKey(Namer.TypedWord) match
    case Some(typedWord) => typedWord.adapt
    case None =>
      word match
      case Ast.IntLit(v)  =>
        Literal(Constant.Int(v.toInt))(defn.IntType, word.span).adapt

      case Ast.CharLit(v) =>
        Literal(Constant.Int(v.toInt))(defn.CharType, word.span).adapt

      case Ast.BoolLit(v) =>
        Literal(Constant.Bool(v))(defn.BoolType, word.span).adapt

      case Ast.StringLit(v) =>
        Literal(Constant.String(v))(defn.StringType, word.span).adapt

      case ref: Ast.RefTree =>
        transformRefTree(ref).adapt

      case record: Ast.RecordLit =>
        transformRecord(record).adapt

      case Ast.TypeAscribe(expr, tpt) =>
        val tpt2 = transformType(tpt)
        val expr2 =
          given TargetType = TargetType.Known(tpt2.tpe)
          transform(expr)
        Encoded(Block(expr2 :: Nil)(expr2.tpe, word.span))(tpt2.tpe).adapt

      case tag: Ast.Tag =>
        transformTagged(tag, values = Nil).adapt

      case lambda: Ast.Lambda =>
        transformLambda(lambda).adapt

      case Ast.Fence(phrase) =>
        given Scope = sc.fresh()
        transform(phrase)

      case app: Ast.Apply =>
        app.fun match
          case tag: Ast.Tag => transformTagged(tag, app.args)
          case _ => transformCall(app)

      case newExpr: Ast.New =>
        transformNew(newExpr)

      case call: Ast.InfixCall =>
        transformInfixCall(call)

      case call: Ast.DotlessCall =>
        transformDotlessCall(call)

      case Ast.TypeApply(fun, targs) =>
        val fun2 =
          given TargetType = TargetType.TypeApply
          transform(fun)
        val targs2 = targs.map(targ => transformType(targ))
        checker.checkTypeApply(fun2, targs2).adapt

      case list: Ast.ListLit =>
        val ref = Ident(defn.List_List)(list.span)
        list.addKey(Namer.TypedWord, ref)
        transform(Ast.Apply(list, list.words)(list.span))

      case Ast.BracketApply(subject, args) =>
        val fun = Ast.Select(subject, "get")(subject.span)
        transform(Ast.Apply(fun, args)(word.span))

      case expr: Ast.Expr  =>
        exprTyper.transform(expr)

      case Ast.With(expr, args) =>
        val exprSast = transform(expr)
        val argsSast = for arg <- args yield transformWithArg(arg)
        With(exprSast, argsSast)(exprSast.tpe)

      case Ast.Allow(expr, params) =>
        val exprSast = transform(expr)
        val paramRefs =
          for
            param <- params
          yield
            transformParamRef(param)

        Allow(exprSast, paramRefs)(exprSast.tpe)

      case ifte: Ast.If =>
        transformIf(ifte).adapt

      case Ast.While(cond, body) =>
         val cond2 =
           given TargetType = TargetType.Known(defn.BoolType)
           transform(cond)

         val body2 =
           given TargetType = TargetType.VoidType
           transform(body)

         While(cond2, body2)(word.span).adapt

      case assign: Ast.Assign =>
        transformAssign(assign).adapt

      case patmat: Ast.Match =>
        patternTyper.transformMatch(patmat).adapt

      case vdef: Ast.ValDef =>
        val vdef2 = transformLocalValDef(vdef)
        sc.define(vdef2.symbol)
        vdef2.adapt

      case fdef: Ast.FunDef =>
        val delayedDef = transformFunDef(fdef, Flags.Fun, Effects.Policy.Infer)
        // A function is available for checking its rhs
        sc.define(delayedDef.symbol)
        delayedDef.force().adapt

      case pdef: Ast.PatDef =>
        val delayedDef = patternTyper.transformPatDef(pdef)
        // A pattern predicate is available for checking its rhs
        sc.define(delayedDef.symbol)
        delayedDef.force().adapt

      case tdef: Ast.TypeDef =>
        val delayedDef = transformTypeDef(tdef)
        // A type definition is available for checking its rhs
        sc.define(delayedDef.symbol)
        delayedDef.force().adapt

      case block: Ast.Block =>
        transformBlock(block)

      case obj: Ast.Object =>
        transformObject(obj).adapt
    }


  def transformRefTree(word: Ast.RefTree)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): Word =
    word match
      case Ast.Ident(name) =>
        given oob: OutOfBand = new OutOfBand
        val sym = sc.resolveTerm(name, word.pos)
        oob.testKey(Scope.PrefixKey) match
          case Some(prefix) =>
            // Normalize SAST
            val qual = Ident(prefix)(word.span.point)
            Select(qual, sym.name)(qual.tpe.termMember(sym.name), word.span)

          case _ =>
            checker.checkCapture(sym, word.pos)
            Ident(sym)(word.span)

      case Ast.Select(qual, name) =>
        val qual2 =
          given TargetType = TargetType.TermMember(name)
          transform(qual)

        val qualType = qual2.tpe
        qualType.getTermMember(name) match
          case Some(tp) =>
            tp match
              case StaticRef(sym) if !sym.isType =>
                // record field type could be Int
                Ident(sym)(word.span)

              case _ =>
                Select(qual2, name)(tp, word.span)

          case None =>
            // Error already reported
            errorWord(word.span)

  def transformObject(obj: Ast.Object)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val delayedDefs = new mutable.ArrayBuffer[DelayedDef[ValDef | FunDef]]

    val thisSym = Symbol.createSymbol("this", Flags.Synthetic, obj.pos)

    // The scope only contains `this`
    // `this` should not be available in field initialization
    val thisScope = sc.fresh()
    thisScope.define(thisSym)

    // scope for checking member methods
    given sc2: Scope = thisScope.freshPrefixedScope(prefix = thisSym, owner = thisSym)

    for member <- obj.members do
      member match
        case vdef: Ast.ValDef =>

          var flags = checker.checkModifiers(vdef) | Flags.Field
          if vdef.mutable then flags = flags | Flags.Mutable

          // Using the outer scope to check field bodies
          given Scope = sc

          def givenType: Type =
            val tpt = transformType(vdef.tpt)
            val tp2 = checker.checkValueType(tpt.tpe, tpt.pos)
            tp2

          val rhs: Word =
            given Scope = sc.fresh()
            given TargetType =
              if vdef.tpt.isEmpty then TargetType.ValueType
              else TargetType.Known(givenType)
            transform(vdef.rhs)

          val tp: Type =
            if vdef.tpt.isEmpty then rhs.tpe.widen else givenType

          val sym = Symbol.createSymbol(vdef.name, tp, flags, thisSym, vdef.ident.pos)
          sc2.define(sym)

          delayedDefs += DelayedDef(sym, () => ValDef(sym, rhs)(vdef.span))

        case fdef: Ast.FunDef =>
          if fdef.preParamCount != 0 then
            Reporter.error("Methods cannot have pre-arguments", fdef.pos)

          val defaultPolicy = Effects.Policy.InferCapture

          val effectPolicy =
            tt.knownType match
              case Some(tp) if tp.isObjectType =>
                tp.getTermMember(fdef.name) match
                  case Some(tp) =>
                    if tp.isProcType then
                      Effects.Policy.Capture(except = tp.asProcType.receives)
                    else
                      Reporter.error("Expect type " + tp.show + ", found a method", fdef.pos)
                      defaultPolicy

                  case _ =>
                    defaultPolicy

              case _ =>
                defaultPolicy

          val delayedDef = transformFunDef(fdef, Flags.Method | Flags.Fun, effectPolicy)

          // Operator name should not be called directly without a prefix
          if !Name.isOperator(delayedDef.symbol.name) then
            sc2.define(delayedDef.symbol)

          delayedDefs += delayedDef

      end match
    end for

    val memberTypes = delayedDefs.map(d => NamedInfo(d.symbol.name, MemberRef(StaticRef(thisSym), d.symbol))).toList
    val mutables = delayedDefs.filter(_.symbol.isMutable).map(_.symbol.name).toList
    val objectType = ObjectType(memberTypes, mutables)

    defn.add(thisSym, sc.owner, objectType)

    val members: List[ValDef | FunDef] =
      for delayedDef <- delayedDefs.toList yield delayedDef.force()

    Object(thisSym, members)(objectType, obj.span)

  def transformBlock(block: Ast.Block)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType)
  : Word =

    val phrases = block.phrases
    val words =
      given Scope = sc.fresh()
      for (phrase, i) <- phrases.zipWithIndex yield
        given TargetType =
          if i == phrases.size - 1 then tt
          else TargetType.VoidType

        transform(phrase)

    if words.isEmpty then
      checker.adapt(Block(Nil)(VoidType, block.span), tt)

    else
      Block(words)(words.last.tpe, block.span)


  def instantiatePoly(polyType: ProcType, fun: Word)(using Definitions, Reporter, Source): Word =
    assert(polyType.tparams.nonEmpty, polyType.show)

    val span = fun.span.endPoint
    val tvars = for tparam <- polyType.tparams yield TypeVar(tparam.name, this.inferencer)
    val targs = tvars.map(tvar => TypeTree(tvar)(span))
    val tpe = polyType.instantiate(tvars)

    checker.delayedCheck {
      for tvar <- tvars do checker.checkInstantiated(tvar, fun.pos)

      checker.checkBounds(polyType.tparams, targs)
    }

    TypeApply(fun, targs)(tpe)

  /** Handles new Foo[T](arg1, arg2, ...) */
  def transformNew(newExpr: Ast.New)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val classTree = transformType(newExpr.classRef)
    var targsTree = for targ <- newExpr.targs yield transformType(targ)

    def instantiateTypeLambda(tparams: List[Symbol])(using Definitions, Reporter): List[TypeVar]  =
      val tvars = for tparam <- tparams yield TypeVar(tparam.name, this.inferencer)

      checker.delayedCheck {
        val span = newExpr.classRef.span.endPoint

        for tvar <- tvars do checker.checkInstantiated(tvar, span.toPos)

        val targs = tvars.map(tvar => TypeTree(tvar)(span))
        checker.checkBounds(tparams, targs)
      }

      tvars

    if !classTree.tpe.isTypeRef then
      Reporter.error("A class name expected, found = " + newExpr.classRef.name, newExpr.classRef.pos)
      errorWord(newExpr.span)

    else if targsTree.nonEmpty && !checker.checkKind(classTree, targsTree) then
      errorWord(newExpr.span)

    else
      val classRef = classTree.tpe.as[RefType]
      val classSym = classRef.symbol
      val instanceType =
        if targsTree.nonEmpty then
          AppliedType(classRef, targsTree.map(_.tpe))

        else if classSym.info.isTypeLambda then
          val tvars = instantiateTypeLambda(classSym.info.asTypeLambda.tparams)
          val span = classTree.span.endPoint
          targsTree = tvars.map(tvar => TypeTree(tvar)(span))
          AppliedType(classRef, tvars)

        else
          classRef

      // Always prefer type constraints from outer scope if present
      for tp <- tt.knownType do Subtyping.conforms(instanceType, tp)

      instanceType.getTermMember(Name.Constructor) match
        case None =>
          Reporter.error("The class cannot be instantiated as it does not have a constructor.", newExpr.pos)
          errorWord(newExpr.span)

        case Some(tp) =>
          assert(tp.is[RefType], "TermRef expected for class member, found = " + tp)
          val refType = tp.as[RefType]

          assert(refType.isProcType, "ProcType expected for constructor, found = " + refType.info)
          val procType = refType.asProcType

          assert(procType.tparams.isEmpty, "Constructor should not take type parameters, found = " + procType)

          val span = if targsTree.isEmpty then classTree.span else classTree.span | targsTree.last.span
          val newInstance = New(Ident(classSym)(classTree.span), targsTree)(instanceType)

          newExpr.addKey(Namer.TypedWord, newInstance)
          val ctorSelect = Ast.Select(newExpr, Name.Constructor)(span)
          val ctorCall = Ast.Apply(ctorSelect, newExpr.args)(newExpr.span)
          transformCall(ctorCall)

  /** Handles explicit postfix call syntax f(arg1, arg2, ...) */
  def transformCall(apply: Ast.Apply)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    var fun =
      given TargetType = TargetType.Fun(apply.args.size)
      transform(apply.fun)

    // Auto .apply insertion --- apply can be polymorphic
    //
    // The `.apply` insertion happens at the transform for `Apply`.
    // It ensures that in `Apply(fun, args)` the fun is an ident or select.
    fun.tpe.getSingleMethodType match
      case Some(NamedInfo(name, procType)) =>
        fun = Select(fun, name)(procType, fun.span)

      case _ =>
        fun.tpe.getTermMember("apply") match
          case Some(tp) if tp.isProcType => fun = fun.select("apply")

          case _ =>

    if fun.tpe.isPolyType then
      fun = instantiatePoly(fun.tpe.asProcType, fun)

    val funType = fun.tpe

    if funType.isProcType then
      val procType = funType.asProcType
      val paramSize = procType.paramTypes.size

      // Always prefer type constraints from outer scope if present
      for tp <- tt.knownType do Subtyping.conforms(procType.resultType, tp)

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

        val autos = autoResolver.derive(procType, apply.span)
        val word = Apply(fun, argsTyped, autos)(procType.resultType)
        checker.checkUnpackUsage(word, tt)
        val desugared = Rewriting.rewriteShortcutAndOr(word)
        checker.adapt(desugared, tt)
    else
      if !fun.tpe.isError then
        Reporter.error(s"Not a function: " + fun.tpe.show, fun.pos)
      errorWord(apply.span)

  /** Check a dotless call such as `str1 + str2` */
  def transformDotlessCall(call: Ast.DotlessCall)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val Ast.DotlessCall(obj, meth, arg) = call
    val objWord =
      given TargetType = TargetType.ValueType
      transform(obj)

    val objType = objWord.tpe
    val objSpan = obj.span

    if objType.isObjectType || objType.isClassType then
      objType.getTermMember(meth.name) match
        case Some(tp) =>
          var fun: Word = Select(objWord, meth.name)(tp, objSpan | meth.span)

          if tp.isPolyType then
            fun = instantiatePoly(tp.asProcType, fun)

          val funType = fun.tpe

          if funType.isProcType then
            val procType = funType.asProcType
            val paramSize = procType.paramTypes.size
            if paramSize != 1 then
              Reporter.error(
                s"The method ${meth.name} takes ${paramSize} parameters. The dotless call syntax only supports methods of one parameter",
                meth.span.toPos)
              errorWord(meth.span)
            else

              // Always prefer type constraints from outer scope if present
              for tp <- tt.knownType do Subtyping.conforms(procType.resultType, tp)

              val argTyped =
                given TargetType = TargetType.Known(procType.paramTypes.head)
                transform(arg)

              val autos = autoResolver.derive(procType, call.span)
              val word = Apply(fun, argTyped :: Nil, autos)(procType.resultType)
              checker.adapt(word, tt)
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
  def transformInfixCall(call: Ast.InfixCall)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val Ast.InfixCall(preArgs, funAst, postArgs) = call

    var fun =
      // infix call should not trigger apply insertion
      given TargetType = TargetType.Unknown
      transform(funAst)

    if fun.tpe.isPolyType then
      fun = instantiatePoly(fun.tpe.asProcType, fun)

    assert(fun.tpe.isProcType, "Expect function type, found = " + fun.tpe)

    val procType = fun.tpe.asProcType
    val preParamCount = procType.preParamCount
    val postParamCount = procType.postParamCount

    assert(!procType.hasVararg, "Infix call cannot have varargs")

    // Always prefer type constraints from outer scope if present
    for tp <- tt.knownType do Subtyping.conforms(procType.resultType, tp)

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

      val autos = autoResolver.derive(procType, call.span)
      val word = Apply(fun, preArgs2 ++ postArgs2, autos)(procType.resultType)
      checker.checkUnpackUsage(word, tt)
      val rewrite = Rewriting.rewriteShortcutAndOr(word)
      checker.adapt(rewrite, tt)

  /** Assumes that the argument count requirement is satisfied */
  def transformArgs
      (args: List[Ast.Word], params: List[Type])
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : List[Word] =

    for (arg, paramType) <- args.zip(params) yield
      given TargetType = TargetType.Known(paramType)
      transform(arg)

  /** Assumes that the argument count requirement is satisfied */
  def transformVarargs
      (args: List[Ast.Word], paramTypes: List[Type], span: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : List[Word] =

    val paramTypesFix :+ paramTypeFlex = paramTypes: @unchecked
    val (argsFix, argsFlex) = args.splitAt(paramTypesFix.size)

    val argsFixTyped = transformArgs(argsFix, paramTypesFix)

    val elementType = paramTypeFlex match
      case AppliedType(tctor, tp :: Nil) if tctor.refers(defn.Predef_Pack) =>
        tp

      case tp =>
        Reporter.error("[internal error] Invalid vararg type: " + tp.show, span.toPos)
        AnyType

    val argsFlexTyped =
      for arg <- argsFlex yield
        val tref = StaticRef(defn.Internal_PackElemType)
        given TargetType = TargetType.Known(AppliedType(tref, elementType :: Nil))
        transform(arg)

    val emptyList =
      val tt = TargetType.Known(paramTypeFlex)
      checker.adapt(Ident(defn.List_empty)(span), tt)

    val lastFlexArg =
      if rp.hasErrors then
        emptyList
      else
        argsFlexTyped.foldLeft(emptyList): (acc, arg) =>
          arg match
            case Apply(fun, arg :: Nil, _) if fun.refers(defn.Predef_dotdot) =>
              acc.select("++").appliedTo(arg)

            case _ =>
              acc.select("+").appliedTo(arg)
          end match

    argsFixTyped :+ lastFlexArg

  def transformAssign(assign: Ast.Assign)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): Word =
    val Ast.Assign(lhs, rhs) = assign

    (lhs: @unchecked) match
      case id: Ast.Ident =>
        given oob: OutOfBand = new OutOfBand
        val sym = sc.resolveTerm(id.name, id.pos)

        checker.checkMutable(sym, id.pos)

        val rhs2 =
          given TargetType = TargetType.Known(sym.info)
          transform(rhs)

        if sym.isField then
          // Normalize SAST
          val qual = Ident(oob.getKey(Scope.PrefixKey))(id.span)
          val lhs2 = Select(qual, sym.name)(MemberRef(qual.tpe, sym), lhs.span)
          FieldAssign(lhs2, rhs2)

        else
          val id = Ident(sym)(lhs.span)
          checker.checkCapture(sym, id.pos)
          Assign(id, rhs2)

      case Ast.Select(qual, name) =>
        val qual2 =
          given TargetType = TargetType.TermMember(name)
          transform(qual)

        val qualType = qual2.tpe
        val isObject = qualType.isObjectType || qualType.isClassType

        if isObject then
          qualType.getTermMember(name) match
            case Some(tp) =>
              val isMutable =
                qualType.isObjectType && qualType.asObjectType.isMutable(name)
                || qualType.isClassType && tp.is[RefType] && tp.as[RefType].symbol.isMutable

              if !isMutable then
                Reporter.error(s"The member $name is immutable", lhs.pos)

              val lhs2 = Select(qual2, name)(tp, lhs.span)

              val rhs2 =
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
        transform(Ast.Apply(fun, args :+ rhs)(assign.span))

  private def transformParamRef(ref: Ast.RefTree)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): Ident =
    val paramRef =
      given TargetType = TargetType.Unknown
      transform(ref)

    val paramSym =
      paramRef.tpe match
        case StaticRef(sym) if sym.isAllOf(Flags.Param | Flags.Context) =>
          sym

        case tp =>
          Reporter.error("A reference to a contextual parameter expected, found = " + tp.show, paramRef.pos)
          Symbol.createSymbol(ref.name, ErrorType, Flags.Synthetic, sc.owner, paramRef.pos)

    Ident(paramSym)(ref.span)

  private def transformWithArg(arg: Ast.WithArg)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): Assign =
    val paramRef = transformParamRef(arg.paramRef)

    val rhs =
      given TargetType =
        if paramRef.tpe.isError then TargetType.ValueType
        else TargetType.Known(paramRef.symbol.dealias.info)

      transform(arg.rhs)

    Assign(paramRef, rhs)


  private def transformIf(ifte: Ast.If)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val Ast.If(cond, thenp, elsep) = ifte

    val cond2 =
      given TargetType = TargetType.Known(defn.BoolType)
      transform(cond)

    val then2 = transform(thenp)
    val else2 = transform(elsep)

    // result type
    val commonType = checker.commonResultType(then2.tpe, else2.tpe, ifte.pos)
    If(cond2, then2, else2)(commonType, ifte.span)

  private def transformRecord(record: Ast.RecordLit)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val Ast.RecordLit(namedArgs) = record
    val namedArgs2 = new mutable.ArrayBuffer[(String, Word)]

    val knownTypeOpt = tt.knownType
    def targetFieldType(field: Ast.Ident): TargetType =
      knownTypeOpt match
        case Some(tp) if tp.isRecordType =>
          tp.asRecordType.getFieldType(field.name) match
            case Some(fieldType) =>
              TargetType.Known(fieldType)

            case None =>
              // TODO: report unused field
              // Reporter.error("Unused field " + field.name, field.pos)
              TargetType.ValueType

        case _ =>
          TargetType.ValueType

    for Ast.NamedArg(id, rhs) <- namedArgs do
      if namedArgs2.exists(_._1 == id.name) then
        Reporter.error("Arg " + id.name + " already defined", id.pos)
      else
        given TargetType = targetFieldType(id)
        val rhs2 = transform(rhs)
        namedArgs2 += id.name -> rhs2
    end for
    val fields = namedArgs2.toList
    val tpe = RecordType(fields.map { case (k, v) => NamedInfo(k, v.tpe) })
    RecordLit(fields)(tpe, record.span)

  def transformTagged(tag: Ast.Tag, values: List[Ast.Word])(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val tagName = tag.name.name
    val span =
      if values.isEmpty then tag.span
      else tag.span | values.last.span

    val pos = span.toPos

    val tagStringLit = StringLit(tagName)(tag.span)

    def check(tagType: TagType, resType: Type): Word =
      val paramTypes = tagType.paramTypes
      if paramTypes.size != values.size then
        Reporter.error(s"Expect ${paramTypes.size} args, found = ${values.size}", pos)

      val values2 =
        for (value, tp) <- values.zip(paramTypes) yield
          given TargetType = TargetType.Known(tp)
          transform(value)

      TaggedLit(tagStringLit, values2)(tagType, span)

    tt.knownType match
      case Some(tp) if !tp.is[TypeVar] =>
        if tp.isUnionType then
          val unionType = tp.asUnionType
          if !unionType.hasTag(tagName) then
            Reporter.error(s"The tag $tagName does not exist in union type ${unionType.show}", pos)
            errorWord(tag.span)
          else
            Encoded(check(unionType.tagType(tagName), unionType))(unionType)

        else if tp.isTagType then
          val tagType = tp.asTagType
          if tagType.tag == tagName then
            check(tp.asTagType, tp)
          else
            Reporter.error(s"Expect tag ${tagType.tag}, found = $tagName", tag.pos)
            errorWord(tag.span)

        else
          Reporter.error(s"Expect union type or tag type, found = ${tp.show}", pos)
          errorWord(tag.span)

      case _ =>
        val values2 =
          for value <- values yield
            given TargetType = TargetType.ValueType
            transform(value)

        val argTypes = values2.map(_.tpe)
        val tagType = TagType.from(tagName, argTypes)

        TaggedLit(tagStringLit, values2)(tagType, span)

  private def transformLambda(lambda: Ast.Lambda)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
     val Ast.Lambda(params, body) = lambda

     val targetFunTypeOpt: Option[NamedInfo[ProcType]] = tt.knownType.flatMap(_.getSingleMethodType)

     if targetFunTypeOpt.nonEmpty then
       val expect = targetFunTypeOpt.get.info.paramCount
       if expect != params.size then
         Reporter.error(s"Expect a function with $expect parameters, found = ${params.size}", lambda.pos)
         return errorWord(lambda.span)

     val funName = targetFunTypeOpt match
       case Some(NamedInfo(name, _)) => name
       case _ => "apply"

     // Each object has a self symbol
     val thisSym = Symbol.createSymbol("this", Flags.Synthetic, lambda.pos)

     val funSym = Symbol.createSymbol(funName, Flags.Fun | Flags.Method | Flags.Synthetic, lambda.pos)
     val lambdaScope = sc.fresh(funSym)

     val tvars = new mutable.ArrayBuffer[(TypeVar, Ast.Param)]

     def inferParamType(i: Int): Type =
       targetFunTypeOpt match
         case Some(NamedInfo(_, funType)) => funType.paramTypes(i)
         case None =>
           val tvar = TypeVar(params(i).name, this.inferencer)
           tvars += tvar -> params(i)
           tvar

     val effectPolicy = targetFunTypeOpt match
       case Some(NamedInfo(_, funType)) => Effects.Policy.Capture(except = funType.receives)
       case None => Effects.Policy.Capture(except = Nil)

     val paramSyms =
      for (param, i) <- params.zipWithIndex yield
        val tp = if param.tpt.isEmpty then inferParamType(i) else transformType(param.tpt).tpe
        val paramSym = Symbol.createSymbol(param.name, tp, Flags.Param, funSym, param.pos)
        lambdaScope.define(paramSym)
        paramSym

     val bodyTargetType = targetFunTypeOpt match
       case Some(NamedInfo(_, funType)) => TargetType.Known(funType.resultType)
       case None => TargetType.ValueType

     val bodyTyped =
       given Scope = lambdaScope
       given TargetType = bodyTargetType
       transform(body)

     val resultType = bodyTyped.tpe.widen

     /* For closures, the effects of a method symbol stored in the type is
      * different from those raw effects computed from the code due to the
      * capture behavior.
      */
     val receivesInfo = () =>
       effectPolicy.bound match
         case Some(effs) => effs
         case None => defn.receives(funSym)

     // Provide type info for the function symbol
     val procType = ProcType(
       tparams = Nil, paramSyms.map(_.toNamedInfo), autos = Nil, resultType,
       receivesInfo, preParamCount = 0)

     defn.add(funSym, thisSym, procType)

     for (tvar, param) <- tvars do
       checker.checkInstantiated(tvar, param.pos)

     val tparamSyms = Nil
     val autoSyms = Nil
     val tpt = TypeTree(bodyTyped.tpe)(body.span.point)
     val funDef = FunDef(funSym, tparamSyms, paramSyms, autoSyms, tpt, effectPolicy, bodyTyped)(lambda.span)
     val objType = ObjectType(NamedInfo(funName, procType) :: Nil, mutableFields = Nil)

     defn.add(thisSym, sc.owner, objType)

     Object(thisSym, funDef :: Nil)(objType, lambda.span)


  private def transformParamDef(pdef: Ast.ParamDef)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source)
  : List[DelayedDef[Def]] =
    assert(pdef.default.isEmpty, "optional context param not desugared: " + pdef)

    val ip = lazyDefn.infoProvider

    // given definitions are lazy
    given Definitions = lazyDefn.value

    var flags = checker.checkModifiers(pdef) | Flags.Param | Flags.Context

    if pdef.hasKey(Desugaring.DefaultContextParam) then
      flags |= Flags.Default

    val paramSym = Symbol.createSymbol(pdef.name, flags, pdef.pos)
    ip.addLazy(paramSym, sc.owner, () => transformType(pdef.tpt).tpe)

    val paramDefSast = () =>
      val tpt = TypeTree(paramSym.info)(pdef.tpt.span)
      ParamDef(paramSym, tpt)(pdef.span)

    DelayedDef(paramSym, paramDefSast) :: Nil

  private def transformLocalValDef(vdef: Ast.ValDef)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): ValDef =
    var flags = checker.checkModifiers(vdef)
    if vdef.mutable then flags = flags | Flags.Mutable

    val sym = Symbol.createSymbol(vdef.name, flags, vdef.ident.pos)

    lazy val givenType: Type =
      val tpt = transformType(vdef.tpt)
      val tp2 = checker.checkValueType(tpt.tpe, tpt.pos)
      tp2

    val rhs: Word =
      given Scope = sc.fresh()
      given TargetType =
        if vdef.tpt.isEmpty then TargetType.ValueType
        else TargetType.Known(givenType)
      transform(vdef.rhs)

    val tp: Type =
      if vdef.tpt.isEmpty then rhs.tpe.widen
      else givenType

    defn.add(sym, sc.owner, tp)

    ValDef(sym, rhs)(vdef.span)

  def transformTypeParams(tparams: List[Ast.TypeParam])
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : List[TypeSymbol] =
    for tparam <- tparams yield
      val bound =
        if tparam.bound.isEmpty then
          TypeBound(BottomType, AnyType)
        else
          val boundTree = transformType(tparam.bound)
          TypeBound(BottomType, boundTree.tpe)

      // Only support simple-kinded type parameters
      val sym = TypeSymbol.createSymbol(Kind.Simple, tparam.name, bound, Flags.Param, sc.owner, tparam.pos)
      sc.define(sym)
      sym

  def transformParams(params: List[Ast.Param])
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : List[Symbol] =

    for (param, i) <- params.zipWithIndex yield
      val tpt = transformType(param.tpt, allowPackType = i == params.size - 1)
      val paramSym = Symbol.createSymbol(param.name, tpt.tpe, Flags.Param, sc.owner, param.pos)
      sc.define(paramSym)
      paramSym

  def transformAutos(autos: List[Ast.Param])
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source)
  : List[Symbol] =

    for auto <- autos yield
      val tpt = transformType(auto.tpt, allowPackType = false)
      val autoSym = Symbol.createSymbol(auto.name, tpt.tpe, Flags.Param | Flags.Auto, sc.owner, auto.pos)
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
      (using lazyDefn: Definitions.Lazy | Definitions, sc: Scope, rp: Reporter, so: Source)
  : DelayedDef[FunDef] =

    val flags = checker.checkModifiers(funDef) | initialFlags

    val funSym = Symbol.createSymbol(funDef.name, flags, funDef.ident.pos)
    given funScope: Scope = sc.fresh(funSym)

    given defn: Definitions = lazyDefn match
      case lazyDefn: Definitions.Lazy => lazyDefn.value
      case defn: Definitions => defn

    lazy val tparamSyms =
      transformTypeParams(funDef.tparams)

    lazy val paramSyms =
      tparamSyms
      transformParams(funDef.params)

    lazy val autoSyms =
      tparamSyms
      transformAutos(funDef.autos)

    lazy val givenResultType =
      tparamSyms

      assert(!funDef.resultType.isEmpty)
      val resTypeTree = transformType(funDef.resultType)
      checker.checkValueType(resTypeTree)

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

      val targetType =
        if !funDef.resultType.isEmpty then
          TargetType.Known(givenResultType)
        else
          TargetType.ValueType

      given TargetType = targetType
      transform(funDef.body)

    lazy val effectPolicy = transformReceives(funDef.receives, policy)

    /* For object closures, the effects of a method symbol stored in the type is
     * different from those raw effects computed from the code due to the
     * capture behavior.
     */
    val receivesInfo = () =>
      effectPolicy.bound match
        case Some(effs) => effs
        case None => defn.receives(funSym)

    def computeInfo(resultType: Type) =
      ProcType(
        tparamSyms, paramSyms.map(_.toNamedInfo), autoSyms.map(_.toNamedInfo),
        resultType, receivesInfo, funDef.preParamCount)

    lazyDefn match
      case lazyDefn: Definitions.Lazy =>
        val ip = lazyDefn.infoProvider
        ip.addLazy(funSym, sc.owner,  () => computeInfo(resultType), () => computeInfo(ErrorType))

      case defn: Definitions =>
        defn.addLazy(funSym, sc.owner,  () => computeInfo(resultType), () => computeInfo(ErrorType))

    val typer = () =>
      val tpt = TypeTree(resultType)(funDef.resultType.span)
      FunDef(funSym, tparamSyms, paramSyms, autoSyms, tpt, effectPolicy, typedBody)(funDef.span)

    DelayedDef(funSym, typer)

  private def transformConstructor(funDef: Ast.FunDef, thisSym: Symbol, classSym: Symbol)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source)
  : DelayedDef[FunDef] =

    val flags = Flags.Fun | Flags.Constructor

    val funSym = Symbol.createSymbol(Name.Constructor, flags, funDef.ident.pos)
    given funScope: Scope = sc.fresh(funSym)

    if funDef.tparams.nonEmpty then
      Reporter.error("Constructor may not take type parameters", funDef.tparams.head.pos)

    given defn: Definitions = lazyDefn.value

    lazy val paramSyms =
      transformParams(funDef.params)

    lazy val autoSyms =
      transformAutos(funDef.autos)

    lazy val resultType =
      if !funDef.resultType.isEmpty then
        val resTypeTree = transformType(funDef.resultType)
        val res = resTypeTree.tpe

        if !Subtyping.isEqualType(res, thisSym.info) then
          Reporter.error("The result type of constructor should be the same as the class", funDef.resultType.pos)

      thisSym.info

    def checkBody(stats: List[Ast.Word], record: Ast.RecordLit): Word =
      val classInfo = classSym.classInfo
      val initialized = new mutable.ArrayBuffer[Symbol]
      val words = new mutable.ArrayBuffer[Word]

      for stat <- stats do
        given TargetType = TargetType.VoidType
        words += transform(stat)

      for arg @ Ast.NamedArg(id, rhs) <- record.args yield
        StaticRef(thisSym).getTermMember(id.name) match
          case Some(tp) =>
            assert(tp.is[RefType], "class member should be RefType, found = " + tp)

            given TargetType = TargetType.Known(tp.widenTermRef)
            val sym = tp.as[RefType].symbol
            if initialized.contains(sym) then
              Reporter.error("The field " + id.name + " already initialized", id.pos)

            else
              val lhs = Select(Ident(thisSym)(id.span), id.name)(tp, id.span)
              words += FieldAssign(lhs, transform(rhs))
              initialized += sym

          case None =>
            Reporter.error("The field " + id.name + " does not exist in class " + classSym, id.pos)
      end for

      val uninit = classInfo.fields.toSet -- initialized.toSeq
      if uninit.nonEmpty then
        val names = uninit.map(_.name).mkString(", ")
        Reporter.error("Uninitialized field(s): " + names, funDef.pos)

      val thisIdent = Ident(thisSym)(record.span.endPoint)
      val body = (words :+ thisIdent).toList
      Block(body)(thisSym.info, funDef.body.span)

    lazy val typedBody =
      paramSyms
      autoSyms

      funDef.body match
        case rec: Ast.RecordLit =>
          checkBody(Nil, rec)

        case Ast.Block(stats :+ (rec: Ast.RecordLit)) =>
          checkBody(stats, rec)

        case _ =>
          Reporter.error("The last phrase of a constructor should be a record literal", funDef.body.pos)
          errorWord(funDef.body.span)

    lazy val effectPolicy = transformReceives(funDef.receives, Effects.Policy.Infer)

    val tparamSyms = Nil
    def computeInfo(resultType: Type) =
      ProcType(
        tparamSyms, paramSyms.map(_.toNamedInfo), autoSyms.map(_.toNamedInfo),
        resultType, () => defn.receives(funSym), funDef.preParamCount)

    val ip = lazyDefn.infoProvider
    ip.addLazy(funSym, sc.owner,  () => computeInfo(resultType), () => computeInfo(ErrorType))

    val typer = () =>
      val tpt = TypeTree(resultType)(funDef.resultType.span)
      FunDef(funSym, tparamSyms, paramSyms, autoSyms, tpt, effectPolicy, typedBody)(funDef.span)

    DelayedDef(funSym, typer)

  private def transformTypeDef(tdef: Ast.TypeDef)
      (using lazyDefn: Definitions.Lazy | Definitions, sc: Scope, rp: Reporter, so: Source)
  : DelayedDef[TypeDef] =

    val flags = checker.checkModifiers(tdef) | Flags.Type
    val kind = Kind.simpleKinded(tdef.tparams.size)
    val typeSym = new TypeSymbol(kind, tdef.name, flags, tdef.ident.pos)

    given defn: Definitions = lazyDefn match
      case lazyDefn: Definitions.Lazy => lazyDefn.value
      case defn: Definitions => defn

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
          val rhs = checker.checkValueType(rhsTree)

          if tdef.isBound then
            TypeBound(BottomType, rhs)
          else
            rhs

      else
        if tdef.rhs.isEmpty then
          TypeLambda(tparamSyms, TypeBound(BottomType, AnyType), tdef.preParamCount)

        else
          val rhsTree = transformType(tdef.rhs)
          val rhs = checker.checkValueType(rhsTree)

          val rhsType =
            if tdef.isBound then TypeBound(BottomType, rhs)
            else rhs

          TypeLambda(tparamSyms, rhsType, tdef.preParamCount)

    end computeInfo

    lazyDefn match
      case lazyDefn: Definitions.Lazy =>
        val ip = lazyDefn.infoProvider
        ip.addLazy(typeSym, sc.owner, computeInfo)

      case defn: Definitions =>
        defn.addLazy(typeSym, sc.owner, computeInfo)

    // check type symbols after completion to allow cycles, type A = A
    val typer = () => TypeDef(typeSym)(tdef.span)

    DelayedDef(typeSym, typer)


  private def transformClassDef(cdef: Ast.ClassDef)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source)
  : DelayedDef[ClassDef] =

    val flags = checker.checkModifiers(cdef) | Flags.Type | Flags.Class
    val kind = Kind.simpleKinded(cdef.tparams.size)
    val classSym = new TypeSymbol(kind, cdef.name, flags, cdef.ident.pos)
    val thisSym = Symbol.createSymbol("this", Flags.Synthetic, cdef.ident.pos)

    given defn: Definitions = lazyDefn.value

    given paramScope: Scope = sc.fresh(classSym)

    lazy val tparamSyms = transformTypeParams(cdef.tparams)

    val fields = new mutable.ArrayBuffer[Symbol]
    val methods = new mutable.ArrayBuffer[Symbol]

    lazy val classInfo: Type =
      val base = new ClassInfo(classSym, tparamSyms, tparamSyms.map(StaticRef.apply), thisSym, fields.toList, methods.toList)

      if cdef.tparams.isEmpty then base
      else TypeLambda(tparamSyms, base, preParamCount = 0)

    val ip = lazyDefn.infoProvider
    ip.addLazy(classSym, sc.owner, () => classInfo)

    // Add this to scope
    val thisScope = paramScope.fresh()
    thisScope.define(thisSym)
    val shortCutScope = thisScope.freshPrefixedScope(prefix = thisSym, owner = classSym)

    lazy val thisInfo: Type =
      val classRef = StaticRef(classSym)
      if tparamSyms.isEmpty then classRef
      else AppliedType(classRef, tparamSyms.map(StaticRef.apply))

    ip.addLazy(thisSym, classSym, () => thisInfo)

    val delayedDefs = new mutable.ArrayBuffer[DelayedDef[FunDef]]

    for case vdef: Ast.ValDef <- cdef.members do
      var flags = checker.checkModifiers(vdef)
      if vdef.mutable then flags = flags | Flags.Field | Flags.Mutable
      else flags = flags | Flags.Field

      val sym = Symbol.createSymbol(vdef.name, flags, vdef.ident.pos)
      shortCutScope.define(sym)

      def checkType() =
        val tpt = transformType(vdef.tpt)
        val tp2 = checker.checkValueType(tpt.tpe, tpt.pos)
        tp2

      if vdef.name == cdef.name then
        Reporter.error("Class name cannot be used as field name", vdef.pos)

      else
        ip.addLazy(sym, classSym, () => checkType())
        fields += sym

    for case fdef: Ast.FunDef <- cdef.members do
      given Scope = shortCutScope

      if fdef.preParamCount != 0 then
        Reporter.error("Methods cannot have pre-arguments", fdef.pos)

      val delayedDef =
        if fdef.name == cdef.name then
          // Constructor is checked with outer scope
          given Scope = paramScope
          transformConstructor(fdef, thisSym, classSym)

        else
          // Prefer the lazy version to avoid forcing
          given Definitions.Lazy = lazyDefn
          transformFunDef(fdef, Flags.Fun | Flags.Method, Effects.Policy.Infer)


      methods += delayedDef.symbol

      // Operator name should not be called directly without a prefix
      if !Name.isOperator(delayedDef.symbol.name) then
        shortCutScope.define(delayedDef.symbol)

      delayedDefs += delayedDef

    val typer = () =>
      val funs: List[FunDef] =
        for delayedDef <- delayedDefs.toList yield delayedDef.force()

      ClassDef(classSym, thisSym, tparamSyms, fields.toList, funs)(cdef.span)

    DelayedDef(classSym, typer)

  private def transformSection(section: Ast.Section)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source)
      : DelayedDef[Section] =

    given Definitions = lazyDefn.value

    val flags = checker.checkModifiers(section) | Flags.Section
    val sym = Symbol.createSymbol(section.name, flags, section.ident.pos)

    val nameTable = new NameTable
    given secScope: Scope = sc.fresh(sym, nameTable)

    val delayedDefs = index(section.defs)

    lazy val sast =
      val defs = for delayed <- delayedDefs.toList yield delayed.force()

      Section(sym, defs)(section.span)

    val ip = lazyDefn.infoProvider
    ip.addLazy(sym, sc.owner, () => {
      given InfoProvider = ip
      for case alias: Ast.AliasDef <- section.defs do
        Imports.doImport(alias.qualid, secScope, lazyDefn.rootNameTable, isAlias = true)

      new ContainerInfo(nameTable.freeze())
    })

    DelayedDef(sym, () => sast)

  private def transformMethodDecl(ddef: Ast.FunDef)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): TypeTree =
    given defScope: Scope = sc.fresh()

    if ddef.preParamCount != 0 then
      Reporter.error("Methods cannot have pre-arguments", ddef.pos)

    val tparamSyms =
      for tparam <- ddef.tparams yield
        val bound =
          if tparam.bound.isEmpty then
            TypeBound(BottomType, AnyType)
          else
            val boundTree = transformType(tparam.bound)
            TypeBound(BottomType, boundTree.tpe)

        // Only support simple kinded type parameters
        val sym = TypeSymbol.createSymbol(Kind.Simple, tparam.name, bound, Flags.Param, sc.owner, tparam.pos)
        defScope.define(sym)
        sym

    val paramSyms =
      for param <- ddef.params yield
        val tpt = transformType(param.tpt)
        val paramSym = Symbol.createSymbol(param.name, tpt.tpe, Flags.Param, sc.owner, param.pos)
        defScope.define(paramSym)
        paramSym

    val autoSyms =
      for auto <- ddef.autos yield
        val tpt = transformType(auto.tpt)
        val autoSym = Symbol.createSymbol(auto.name, tpt.tpe, Flags.Param | Flags.Auto, sc.owner, auto.pos)
        defScope.define(autoSym)
        autoSym

    val resultType =
      assert(!ddef.resultType.isEmpty)
      val resTypeTree = transformType(ddef.resultType)
      checker.checkValueType(resTypeTree)

    val effs =
      for
        param <- ddef.receives.getOrElse(Nil)
      yield
        transformParamRef(param).symbol

    val finalType =
      ProcType(tparamSyms, paramSyms.map(_.toNamedInfo), autoSyms.map(_.toNamedInfo), resultType, () => effs, preParamCount = 0)


    TypeTree(finalType)(ddef.span)

  /** Type check type tree
    *
    * Checks must be delayed by using `checker.delayedCheck`.
    */
  def transformType(tpt: Ast.TypeTree, allowPackType: Boolean = false)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): TypeTree =
    def check(sym: Symbol) =
      if sym == defn.Predef_Pack && !allowPackType then
        Reporter.error(".. not allowed here. It can only be used as the type of the last varargs parameter.", tpt.pos)

    tpt.getKeyOrElse(Namer.TypedTypeTree):
      tpt match
      case Ast.Ident(name) =>
        sc.resolveType(name) match
          case Some(sym) =>
            check(sym)
            TypeTree(StaticRef(sym))(tpt.span)

          case None =>
            Reporter.error("Unknown type " + tpt, tpt.pos)
            TypeTree(ErrorType)(tpt.span)

      case Ast.Select(qual, name) =>
        val qual2 =
          given TargetType = TargetType.TypeMember(name)
          transform(qual)

        qual2.tpe match
          case StaticRef(sym) if sym.isContainer =>
            val nsInfo = sym.dealias.info.as[ContainerInfo]
            nsInfo.resolveType(name) match
              case Some(sym) =>
               check(sym)
                val tp = StaticRef(sym)
                TypeTree(tp)(tpt.span)

              case None =>
                Reporter.error(s"The namespace $sym does not contain the type member $name", qual.pos)
                TypeTree(ErrorType)(tpt.span)

          case tp =>
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
            val tp = checker.checkValueType(tpt)
            fieldTypes += NamedInfo(field.name, tp)
        end for
        TypeTree(RecordType(fieldTypes.toList))(tpt.span)

      case Ast.TagType(tag, params) =>
        val paramInfos = new mutable.ArrayBuffer[NamedInfo[Type]]
        for param <- params yield
          if paramInfos.exists(_.name == param.name) then
            Reporter.error("Parameter " + param.name + " already defined", param.pos)

          val tpt = transformType(param.tpt)
          val tp = checker.checkValueType(tpt)
          paramInfos += NamedInfo(param.name, tp)
        end for
        TypeTree(TagType(tag.name, paramInfos.toList))(tpt.span)

      case Ast.ObjectType(members) =>
        val memberTypes = new mutable.ArrayBuffer[NamedInfo[Type]]
        val mutableFields = new mutable.ArrayBuffer[String]

        members.foreach: member =>
          if memberTypes.exists(_.name == member.name) then
            Reporter.error("Member " + member.name + " already defined", member.pos)
          else
            member match
              case methodDecl: Ast.FunDef =>
                // TODO: specialize the check
                checker.checkModifiers(methodDecl)

                val memberTypeTree = transformMethodDecl(methodDecl)
                memberTypes += NamedInfo(member.name, memberTypeTree.tpe)

              case vdef: Ast.ValDef =>
                // TODO: specialize the check
                checker.checkModifiers(vdef)

                if vdef.mutable then mutableFields += vdef.name
                val fieldTypeTree = transformType(vdef.tpt)
                memberTypes += NamedInfo(vdef.name, fieldTypeTree.tpe)

        val tp = ObjectType(memberTypes.toList, mutableFields.toList)
        TypeTree(tp)(tpt.span)

      case Ast.UnionType(branches) =>
        val branchTypes = new mutable.ArrayBuffer[Type]
        val tags = mutable.Set.empty[String]
        for branch <- branches do
          val branchType = transformType(branch).tpe
          val branchTags =
            if branchType.isTagType then
              branchTypes += branchType
              branchType.asTagType.tag :: Nil
            else if branchType.isUnionType then
              branchTypes += branchType
              branchType.asUnionType.tags
            else
              Reporter.error("Only tag type or union type allowed inside a union type, found = " + branchType.show, branch.pos)
              Nil

          for tag <- branchTags do
            if tags.exists(_ == tag) then
              Reporter.error("Branch " + tag + " already defined", branch.pos)
            else
              tags += tag

        end for
        val unionType = UnionType(branchTypes.toList)
        TaggedEncoding.checkUnionType(unionType, tpt.pos)
        TypeTree(unionType)(tpt.span)

      case Ast.AppliedType(tctor, targs) =>
        val tctor2 = transformType(tctor, allowPackType)
        val targs2 = for targ <- targs yield transformType(targ, allowPackType = false)
        if tctor2.tpe == ErrorType || !checker.checkKind(tctor2, targs2) then
          TypeTree(ErrorType)(tpt.span)
        else
          val tp = AppliedType(tctor2.tpe, targs2.map(_.tpe))
          checker.delayedCheck {
            val tl = tctor2.tpe.asTypeLambda
            checker.checkBounds(tl.tparams, targs2)
          }
          TypeTree(tp)(tpt.span)

      case Ast.FunctionType(paramTypes, resType, receives) =>
        var i = 0
        val paramTypes2 =
          for paramType <- paramTypes yield
            val tpt = transformType(paramType)
            val tp = checker.checkValueType(tpt)
            val namedInfo = NamedInfo("param" + i, tp)
            i = i + 1
            namedInfo

        val effs =
          for
            param <- receives
          yield
            transformParamRef(param).symbol

        val resType2 = transformType(resType)
        val resTypeChecked = checker.checkValueType(resType2)

        val autoTypes = Nil
        val applyType = ProcType(tparams = Nil, paramTypes2, autoTypes, resTypeChecked, () => effs, preParamCount = 0)
        val objType = ObjectType(NamedInfo("apply", applyType) :: Nil, mutableFields = Nil)
        TypeTree(objType)(tpt.span)

      case _: Ast.EmptyTypeTree =>
        Reporter.abort("Unexpected empty type tree", tpt.pos)

object Namer:
  def errorWord(span: Span) = Block(words = Nil)(ErrorType, span)

  /** The typed word associated with an untyped word
    *
    * It is used to avoid re-typing a word.
    */
  val TypedWord = new KeyProps.Key[Word]("Namer.TypedWord")

  val TypedTypeTree = new KeyProps.Key[TypeTree]("Namer.TypedTypeTree")

  class DelayedDef[+T](val symbol: Symbol, val delayed: () => T):
    private lazy val definition: T = delayed()
    def force()(using Definitions): T =
      symbol.info // force symbol
      definition
