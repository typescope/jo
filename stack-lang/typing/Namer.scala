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

      case _: Ast.DataDef | _: Ast.EnumDef  =>
        Reporter.error("[Internal Error] Data definition should have been desugared", defn.pos)
        Nil

      case vdef: Ast.ValDef =>
        Reporter.error("Unexpected top-level value definitions", vdef.pos)
        Nil
    end match
  end index

  extension (word: Word)
    def adapt(using tt: TargetType, defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars): Word =
      Checker.adapt(word, tt)

  def transform(word: Ast.Word)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word =

    Debug.trace(s"Typing ${word.show}, owner = ${sc.owner}, tt = ${tt.show}", (_: Word).show, enable = false) {
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

      case Ast.InterpolatedString(parts) =>
        transformInterpolatedString(parts, word.span).adapt

      case id: Ast.Ident =>
        transformIdent(id)

      case select: Ast.Select =>
        transformSelect(select)

      case record: Ast.RecordLit =>
        transformRecord(record).adapt

      case Ast.TypeAscribe(expr, tpt) =>
        val tpt2 = Checks.eager { transformType(tpt) }
        val expr2 =
          given TargetType = TargetType.Known(tpt2.tpe)
          transform(expr)
        Encoded(Block(expr2 :: Nil)(word.span))(tpt2.tpe).adapt

      case lambda: Ast.Lambda =>
        transformLambda(lambda).adapt

      case Ast.Fence(phrase) =>
        given Scope = sc.fresh()
        transform(phrase)

      case app: Ast.Apply =>
        transformCall(app)

      case newExpr: Ast.New =>
        transformNew(newExpr)

      case call: Ast.InfixCall =>
        transformInfixCall(call)

      case call: Ast.DotlessCall =>
        transformDotlessCall(call)

      case Ast.TypeApply(fun, targs) => Checks.eager:
        val fun2 =
          given TargetType = TargetType.TypeApply
          transform(fun)
        val targs2 = targs.map(targ => transformType(targ))
        Checker.checkTypeApply(fun2, targs2, word.span).adapt

      case list: Ast.ListLit =>
        val ref = Ident(defn.List_List)(list.span)
        list.addKey(Namer.TypedWord, ref)
        transform(Ast.Apply(list, list.words, Nil)(list.span))

      case Ast.BracketApply(subject, args) =>
        val fun = Ast.Select(subject, "get")(subject.span)
        transform(Ast.Apply(fun, args, Nil)(word.span))

      case expr: Ast.Expr  =>
        exprTyper.transform(expr)

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

      case Ast.While(cond, body) =>
         val cond2 =
           given TargetType = TargetType.Known(defn.BoolType)
           transform(cond)

         val body2 =
           given TargetType = TargetType.VoidType
           Inference.freshIsolate:
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

      case obj: Ast.Object =>
        transformObject(obj).adapt
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

  def resolveQualid(qualid: Ast.RefTree, universe: Universe)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): Option[Symbol] =
    qualid match
      case Ast.Ident(name) =>
        given oob: OutOfBand = new OutOfBand
        val res = sc.resolve(name, universe)
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

  def transformObject(obj: Ast.Object)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType)
  : Word = Checks.delayed:

    val delayedDefs = new mutable.ArrayBuffer[DelayedDef[ValDef | FunDef]]

    val thisSym = TermSymbol.create("this", Flags.Synthetic, Visibility.Default, sc.owner, obj.pos)

    // The scope only contains `this`
    // `this` should not be available in field initialization
    val thisScope = sc.fresh()
    thisScope.define(thisSym)

    // scope for checking member methods
    given sc2: Scope = thisScope.freshPrefixedScope(prefix = thisSym, owner = thisSym)

    for member <- obj.members do
      member match
        case vdef: Ast.ValDef =>

          var flags = Checker.checkModifiers(vdef) | Flags.Field
          if vdef.mutable then flags = flags | Flags.Mutable

          // Using the outer scope to check field bodies
          given Scope = sc

          def givenType: Type =
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
            if vdef.tpt.isEmpty then rhs.tpe.widen else givenType

          val sym = TermSymbol.create(vdef.name, tp, flags, Visibility.Default, thisSym, vdef.ident.pos)
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
          if !Naming.isOperator(delayedDef.symbol.name) then
            sc2.define(delayedDef.symbol)

          delayedDefs += delayedDef

      end match
    end for

    val thisRef = StaticRef(thisSym)
    val mutables = delayedDefs.filter(_.symbol.isMutable).map(_.symbol.name).toList

    lazy val selfType =
      val memberTypes = delayedDefs.map: d =>
        NamedInfo(d.symbol.name, MemberRef(thisRef, d.symbol))

      ObjectType(memberTypes.toList, mutables)

    defn.addLazy(thisSym, () => selfType)

    val members: List[ValDef | FunDef] =
      for delayedDef <- delayedDefs.toList yield delayedDef.force()

    val objectType = ObjectType(members.map(_.symbol.toNamedInfo), mutables)

    Object(thisSym, members)(objectType, obj.span)

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

    def instantiateTypeLambda(tparams: List[Symbol])(using Definitions, Reporter): List[TypeVar]  =
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

          assert(refType.isProcType, "ProcType expected for constructor, found = " + refType.info)
          val procType = refType.asProcType

          assert(procType.tparams.isEmpty, "Constructor should not take type parameters, found = " + procType)

          val span = classTree.span
          val newInstance = New(TypeTree(instanceType)(span))(span)

          newExpr.addKey(Namer.TypedWord, newInstance)
          val ctorSelect = Ast.Select(newExpr, Names.Constructor)(span)
          val ctorCall = Ast.Apply(ctorSelect, newExpr.args, Nil)(newExpr.span)
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
    fun.tpe.getSingleMethodType match
      case Some(NamedInfo(name, procType)) =>
        fun = Select(fun, name)(fun.span)

      case _ =>
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
            transformVarargs(apply.args, procType.paramTypes, procType.adapters, apply.span)
          else
            transformArgs(apply.args, procType.paramTypes, procType.adapters)

        // Transform having bindings into local variables
        val call =
          if apply.havingBindings.isEmpty then
            Autos.resolve(fun, argsTyped, havings = Nil, apply.span)
          else
            transformHavingCall(fun, argsTyped, apply.havingBindings, apply.span)

        Rewriting.rewrite(call).adapt

    else
      if !fun.tpe.isError then
        Reporter.error(s"Not a function: " + fun.tpe.show, fun.pos)
      errorWord(apply.span)

  /** Transform having clause by lifting bindings to local variables */
  def transformHavingCall(fun: Word, args: List[Word], havingBindings: List[Ast.HavingBinding], span: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word = Checks.eager:
    // Transform each having binding
    val havingSyms = new scala.collection.mutable.ArrayBuffer[Symbol]
    val havingDefs = new scala.collection.mutable.ArrayBuffer[ValDef]

    for binding <- havingBindings do
      // Transform the type
      val tpt = transformType(binding.tpe, allowPackType = false)

      // Transform the value
      given TargetType = TargetType.Known(tpt.tpe, Adaptation.NoAdapter)
      val value = Inference.freshIsolate:
        transform(binding.value)

      // If the value is already an Ident, use its symbol directly
      // Otherwise, create a synthetic local symbol
      value match
        case Ident(sym) =>
          havingSyms += sym

        case _ =>
          val havingSym = TermSymbol.create(
            "havingCand",
            tpt.tpe,
            Flags.empty,
            Visibility.Default,
            sc.owner,
            binding.span.toPos
          )
          havingSyms += havingSym
          havingDefs += ValDef(havingSym, value)(binding.span)

    // Resolve autos with the having symbols
    val call = Autos.resolve(fun, args, havingSyms.toList, span)

    // If there are no definitions, just return the call
    // Otherwise wrap in a block with the having definitions
    if havingDefs.isEmpty then
      call
    else
      Block(havingDefs.toList :+ call)(span)

  /** Check a dotless call such as `str1 + str2` */
  def transformDotlessCall(call: Ast.DotlessCall)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word =

    val Ast.DotlessCall(obj, meth, arg) = call
    val objWord =
      given TargetType = TargetType.ValueType
      Inference.freshIsolate:
        transform(obj)

    val objType = objWord.tpe
    val objSpan = obj.span

    if objType.isObjectType || objType.isClassInfoType then
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
              val argTyped = transformArg(arg, paramType, procType.adapters.head)
              Autos.resolve(fun, argTyped :: Nil, havings = Nil, call.span).adapt
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

    if fun.tpe.isPolyType then
      fun = TreeOps.instantiatePoly(fun.tpe.asProcType, fun)

    assert(fun.tpe.isProcType, "Expect function type, found = " + fun.tpe)

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
      val preArgs2 = transformArgs(preArgs, procType.preParamTypes, procType.adapters.take(procType.preParamCount))
      val postArgs2 =
        if procType.hasVararg then
          transformVarargs(postArgs, procType.postParamTypes, procType.adapters.drop(procType.preParamCount), call.span)

        else
          transformArgs(postArgs, procType.postParamTypes, procType.adapters.drop(procType.preParamCount))


      val callTyped = Autos.resolve(fun, preArgs2 ++ postArgs2, havings = Nil, call.span)
      Rewriting.rewrite(callTyped).adapt

  /** Assumes that the argument count requirement is satisfied */
  def transformArgs
      (args: List[Ast.Word], params: List[Type], adapters: List[List[Symbol | String]] = Nil)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars)
  : List[Word] =

    val paddedAdapters = adapters.padTo(params.size, Nil)
    for ((arg, paramType), adapterList) <- args.zip(params).zip(paddedAdapters)
    yield transformArg(arg, paramType, adapterList)

  def transformArg
      (arg: Ast.Word, paramType: Type, adapters: List[Symbol | String])
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars)
  : Word =
      val adapter = Adaptation.createSimpleAdapter(adapters, sc.owner)
      transformArg(arg, paramType, adapter)

  def transformArg
      (arg: Ast.Word, paramType: Type, adapter: Adaptation.Adapter)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars)
  : Word =
    if paramType.isFullyInstantiated then
      given TargetType = TargetType.Known(paramType, adapter)
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
      (args: List[Ast.Word], paramTypes: List[Type], adapters: List[List[Symbol | String]], span: Span)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tvars: TypeVars)
  : List[Word] =

    val paramTypesFix :+ paramTypeFlex = paramTypes: @unchecked
    val adaptersFix :+ adaptersFlex = adapters: @unchecked
    val (argsFix, argsFlex) = args.splitAt(paramTypesFix.size)

    val argsFixTyped = transformArgs(argsFix, paramTypesFix, adaptersFix)

    val elementType = paramTypeFlex match
      case AppliedType(tctor, tp :: Nil) if tctor == defn.Predef_Pack =>
        tp

      case tp =>
        Reporter.error("[internal error] Invalid vararg type: " + tp.show, span.toPos)
        AnyType

    var lastFlexArg: Word =
      val tapply = Ident(defn.List_empty)(span).appliedToTypes(elementType)
      Apply(tapply, args = Nil, autos = Nil)(span)

    def checkSplice(splice: Ast.Word, args: List[Ast.Word]): Unit =
      if args.size != 1 then
        Reporter.error(".. should be followed by exact one word, found = " + args.size, splice.pos)

      else
        val listType = AppliedType(defn.List_type, elementType :: Nil)
        val adapter = Adaptation.createVarargSpliceAdapter(adaptersFlex, sc.owner)
        val argTyped = transformArg(args.head, listType, adapter)

        lastFlexArg = lastFlexArg.select("++").appliedTo(argTyped)

    for arg <- argsFlex do
      arg match
        case Ast.Expr(Ast.Ident("..") :: rest) =>
          checkSplice(arg, rest)

        case Ast.Apply(Ast.Ident(".."), args, _) =>
          checkSplice(arg, args)

        case _ =>
          val argTyped = transformArg(arg, elementType, adaptersFlex)

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
        val isObject = qualType.isObjectType || qualType.isClassInfoType

        if isObject then
          qualType.getTermMember(name) match
            case Some(tp) =>
              tp match
                case ref: RefType =>
                  Checks.eager:
                    Checker.checkAccess(ref.symbol, sc.owner, lhs.span)

                case _ =>

              val isMutable =
                qualType.isObjectType && qualType.asObjectType.isMutable(name)
                || qualType.isClassInfoType && tp.is[RefType] && tp.as[RefType].symbol.isMutable

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
          transform(Ast.Apply(fun, args :+ rhs, Nil)(assign.span))

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
            val adapter = Adaptation.createSimpleAdapter(defn.stringInterpolationAdapters, sc.owner)
            given TargetType = TargetType.Known(defn.StringType, adapter)
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

  private def transformIf(ifte: Ast.If)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars): Word =
    val Ast.If(cond, thenp, elsep) = ifte

    val cond2 =
      given TargetType = TargetType.Known(defn.BoolType)
      Inference.freshIsolate:
        transform(cond)

    val then2 = transform(thenp)

    val else2 = transform(elsep)

    // result type
    val commonType = Checker.commonResultType(then2.tpe, else2.tpe, ifte.pos)
    If(cond2, then2, else2)(commonType, ifte.span)

  def transformRecord(record: Ast.RecordLit)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word =

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
    RecordLit(fields)(record.span)

  def transformLambda(lambda: Ast.Lambda)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType, tvars: TypeVars)
  : Word =

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
     val thisSym = TermSymbol.create("this", Flags.Synthetic, Visibility.Default, sc.owner, lambda.pos)

     val funSym = TermSymbol.create(funName, Flags.Fun | Flags.Method | Flags.Synthetic, Visibility.Default, thisSym, lambda.pos)
     val lambdaScope = sc.fresh(funSym)

     val selfType = ObjectType(NamedInfo(funName, MemberRef(StaticRef(thisSym), funSym)) :: Nil, mutableFields = Nil)
     defn.add(thisSym, selfType)

     def inferParamType(i: Int): Type =
       targetFunTypeOpt match
         case Some(NamedInfo(_, funType)) => funType.paramTypes(i)
         case None => TypeVar(params(i).name, params(i).span)

     val effectPolicy = targetFunTypeOpt match
       case Some(NamedInfo(_, funType)) => Effects.Policy.Capture(except = funType.receives)
       case None => Effects.Policy.Capture(except = Nil)

     val paramSyms = Checks.eager:
      for (param, i) <- params.zipWithIndex yield
        val tp = if param.tpt.isEmpty then inferParamType(i) else transformType(param.tpt).tpe
        val paramSym = TermSymbol.create(param.name, tp, Flags.Param, Visibility.Default, funSym, param.pos)
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
       Nil, paramSyms.map(_.toNamedInfo), paramSyms.map(_ => Nil), Nil, Nil, resultType,
       receivesInfo, 0)

     defn.add(funSym, procType)

     val tparamSyms = Nil
     val autoSyms = Nil
     val tpt = TypeTree(resultType)(body.span.point)
     val funDef = FunDef(funSym, tparamSyms, paramSyms, paramSyms.map(_ => Nil), autoSyms, autoSyms.map(_ => Nil), tpt, effectPolicy, bodyTyped)(lambda.span)
     val objType = ObjectType(NamedInfo(funName, procType) :: Nil, mutableFields = Nil)

     Object(thisSym, funDef :: Nil)(objType, lambda.span)


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
      val autoSym = TermSymbol.create(auto.name, tpt.tpe, Flags.Param, Visibility.Default, sc.owner, auto.pos)
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

    lazy val adapters =
      funDef.params.zip(paramSyms).map: (param, paramSym) =>
        Adapters.check(param.adapters, paramSym.info, this)

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
    val receivesInfo = () =>
      effectPolicy.bound match
        case Some(effs) => effs
        case None => defn.receives(funSym)

    def computeInfo(resultType: Type) =
      val adapterSymbols = adapters.map(l => l.map {
        case ParamAdapter.Function(symbol) => symbol
        case ParamAdapter.Member(name) => name
      })

      val candidateSymbols = candidates.map(_._2)

      ProcType(
        tparamSyms, paramSyms.map(_.toNamedInfo), adapterSymbols, autoSyms.map(_.toNamedInfo), candidateSymbols,
        resultType, receivesInfo, funDef.preParamCount)

    val ip = lazyDefn.infoProvider
    ip.addLazy(funSym, () => computeInfo(resultType), () => computeInfo(ErrorType))

    val typer = () =>
      val candidateTrees = candidates.map(_._1)
      val tpt = TypeTree(resultType)(funDef.resultType.span)
      FunDef(funSym, tparamSyms, paramSyms, adapters, autoSyms, candidateTrees, tpt, effectPolicy, typedBody)(funDef.span)

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
          case assign @ Ast.Assign(lhs @ Ast.Select(qual @ Ast.Ident("this"), name), rhs) =>
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
        tparamSyms, paramSyms.map(_.toNamedInfo), paramSyms.map(_ => Nil), autoSyms.map(_.toNamedInfo), candidateSymbols,
        resultType, () => defn.receives(funSym), funDef.preParamCount)

    val ip = lazyDefn.infoProvider
    ip.addLazy(funSym, () => computeInfo(resultType), () => computeInfo(ErrorType))

    val typer = () =>
      val candidateTrees = candidates.map(_._1)
      val tpt = TypeTree(resultType)(funDef.resultType.span)
      FunDef(funSym, tparamSyms, paramSyms, paramSyms.map(_ => Nil), autoSyms, candidateTrees, tpt, effectPolicy, typedBody)(funDef.span)

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

    val flags = Checker.checkModifiers(cdef0) | Flags.Class
    val kind = Kind.simpleKinded(cdef0.tparams.size)
    val classSym = TypeSymbol.create(kind, cdef0.name, flags, Checker.visibility(cdef0, sc.owner), sc.owner, cdef0.ident.pos)
    val thisSym = TermSymbol.create("this", Flags.Synthetic, Visibility.Default, classSym, cdef0.ident.pos)

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

  private def transformMethodDecl(ddef: Ast.FunDef)
      (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, ck: Checks)
  : TypeTree =

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
        val sym = TypeSymbol.create(Kind.Simple, tparam.name, bound, Flags.Param, Visibility.Default, sc.owner, tparam.pos)
        defScope.define(sym)
        sym

    val paramSyms =
      for param <- ddef.params yield
        val tpt = transformType(param.tpt)
        val paramSym = TermSymbol.create(param.name, tpt.tpe, Flags.Param, Visibility.Default, sc.owner, param.pos)
        defScope.define(paramSym)
        paramSym

    val adapters =
      for (param, paramSym) <- ddef.params.zip(paramSyms) yield
        Adapters.check(param.adapters, paramSym.info, this)

    val autoSyms =
      for auto <- ddef.autos yield
        val tpt = transformType(auto.tpt)
        val autoSym = TermSymbol.create(auto.name, tpt.tpe, Flags.Param, Visibility.Default, sc.owner, auto.pos)
        defScope.define(autoSym)
        autoSym

    val candidates =
      ddef.autos.zip(autoSyms).map: (auto, autoSym) =>
        Autos.check(auto.candidates, autoSym.info, this)

    val resultType =
      assert(!ddef.resultType.isEmpty)
      val resTypeTree = transformType(ddef.resultType)
      Checker.checkValueType(resTypeTree)

    val effs =
      for
        param <- ddef.receives.getOrElse(Nil)
      yield
        transformParamRef(param).symbol

    val adapterSymbols = adapters.map(l => l.map {
      case ParamAdapter.Function(symbol) => symbol
      case ParamAdapter.Member(name) => name
    })

    val finalType =
      val candidateSymbols = candidates.map(_._2)
      ProcType(
        tparamSyms,
        paramSyms.map(_.toNamedInfo),
        adapterSymbols,
        autoSyms.map(_.toNamedInfo),
        candidateSymbols,
        resultType,
        () => effs,
        0
      )

    TypeTree(finalType)(ddef.span)

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
            val tp = Checker.checkValueType(tpt)
            fieldTypes += NamedInfo(field.name, tp)
        end for
        TypeTree(RecordType(fieldTypes.toList))(tpt.span)

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
                Checker.checkModifiers(methodDecl)

                val memberTypeTree = transformMethodDecl(methodDecl)
                memberTypes += NamedInfo(member.name, memberTypeTree.tpe)

              case vdef: Ast.ValDef =>
                // TODO: specialize the check
                Checker.checkModifiers(vdef)

                if vdef.mutable then mutableFields += vdef.name
                val fieldTypeTree = transformType(vdef.tpt)
                memberTypes += NamedInfo(vdef.name, fieldTypeTree.tpe)

        val tp = ObjectType(memberTypes.toList, mutableFields.toList)
        TypeTree(tp)(tpt.span)

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
        val unionType = UnionType(branchTypes.toList)
        TypeTree(unionType)(tpt.span)

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
        var i = 0
        val paramTypes2 =
          for paramType <- paramTypes yield
            val tpt = transformType(paramType)
            val tp = Checker.checkValueType(tpt)
            val namedInfo = NamedInfo("param" + i, tp)
            i = i + 1
            namedInfo

        val effs =
          for
            param <- receives
          yield
            transformParamRef(param).symbol

        val resType2 = transformType(resType)
        val resTypeChecked = Checker.checkValueType(resType2)

        val autoTypes = Nil
        val applyType = ProcType(Nil, paramTypes2, paramTypes2.map(_ => Nil), autoTypes, Nil, resTypeChecked, () => effs, 0)
        val objType = ObjectType(NamedInfo("apply", applyType) :: Nil, mutableFields = Nil)
        TypeTree(objType)(tpt.span)

      case _: Ast.EmptyTypeTree =>
        Reporter.abort("Unexpected empty type tree", tpt.pos)

object Namer:
  /** The typed word associated with an untyped word
    *
    * It is used to avoid re-typing a word.
    */
  val TypedWord = new KeyProps.Key[Word]("Namer.TypedWord")

  val TypedTypeTree = new KeyProps.Key[TypeTree]("Namer.TypedTypeTree")
