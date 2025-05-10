package typing

import ast.Ast
import ast.Desugaring
import ast.Name
import ast.Positions.*

import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*
import sast.Definitions.InfoProvider

import common.Debug
import common.KeyProps

import reporting.Reporter

import Namer.{ DelayedDef, errorWord }
import Inference.*

import scala.collection.mutable

/**
  * The namer handles name resolution and desugaring.
  *
  * It converts ASTs to Semantic ASTs.
  */
class Namer:
  val checker = new Checker(this)
  val patternTyper = PatternTyper(this, checker)
  val inferencer: Inferencer = new UnificationSolver
  val exprTyper = new ExprTyper(this)

  def transform
      (nss: List[Ast.Namespace], rootNameTable: NameTable, predef: NameTable)
      (using defnLazy: Definitions.Lazy, rp: Reporter)
  : List[Namespace] =

    given ip: InfoProvider = defnLazy.infoProvider

    // All namespace are located in the scope
    val rootNamespaceScope = new Scope.RootScope(rootNameTable, owner = null)

    // Predef names are by-default accessible. However, other namespaces are not
    // accessible unless explicitly imported.
    val predefScope: Scope = new Scope.RootScope(predef, owner = null)

    val delayedImports = new mutable.ArrayBuffer[() => Unit]
    val delayedAliases = new mutable.ArrayBuffer[() => Unit]
    val delayedNamespaces = new mutable.ArrayBuffer[DelayedDef[Namespace]]

    for ns <- nss do
      given source: Source = Reporter.source(ns.source)

      val nsSym = resolveNamespace(ns.qualid, isBranch = false)(using rootNamespaceScope)
      val nsInfo = ip.info(nsSym).as[NameTableInfo]

      val importScope: Scope = predefScope.fresh(nsSym)
      val defsScope: Scope = importScope.fresh(nsSym, nsInfo.nameTable)

      val delayedDefs =
        given Scope = defsScope
        index(ns.defs)

      delayedAliases += { () =>
        // handle aliases after indexing members
        for case alias: Ast.AliasDef <- ns.defs do
          Imports.doImport(alias.qualid, defsScope, rootNameTable, isAlias = true)
      }

      val imports = new mutable.ArrayBuffer[Symbol]

      delayedImports += { () =>
        // Make current namespace name available
        importScope.define(nsSym)
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
      given Definitions = defnLazy.value
      for delayedDef <- delayedNamespaces
      yield delayedDef.delayed() <| (delayedDef.symbol.fullName)

    checker.performDelayedChecks() <| ("checker")
    namespaces.toList

  /** Resolve namespace and create intermediate namespace on demand
    *
    * It also checks redefinition of namespace.
    */
  def resolveNamespace
      (qualid: Ast.RefTree, isBranch: Boolean)
      (using sc: Scope, rp: Reporter, so: Source, ip: InfoProvider)
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
        Symbol.create(sym.name, new NameTableInfo, flags, ip(sym).owner, qualid.pos)

    qualid match
      case Ast.Select(qual, name) =>
        assert(qual.isInstanceOf[Ast.RefTree], "Unexpected qualid = " + qualid)
        val nsSym = resolveNamespace(qual.asInstanceOf[Ast.RefTree], isBranch = true)

        assert(nsSym.isNamespace, "Not a namespace " + nsSym)
        val nsInfo = ip.info(nsSym).as[NameTableInfo]

        nsInfo.resolveTerm(name) match
          case Some(sym) => check(sym)

          case None =>
            val flags = if isBranch then Flags.NSpace | Flags.Branch else Flags.NSpace
            val sym = Symbol.create(name, new NameTableInfo, flags, nsSym, qualid.pos)
            nsInfo.define(sym)
            sym

      case Ast.Ident(name) =>
        sc.resolveTerm(name) match
          case None =>
            val flags = if isBranch then Flags.NSpace | Flags.Branch else Flags.NSpace
            val sym = Symbol.create(name, new NameTableInfo, flags, sc.owner, qualid.pos)
            sc.define(sym)
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
      // The name table is shared between NameTableInfo and current scope. This
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
        given TargetType = TargetType.Unknown
        transformFunDef(fdef) :: Nil

      case tdef: Ast.TypeDef =>
        transformTypeDef(tdef) :: Nil

      case pdef: Ast.ParamDef =>
        transformParamDef(pdef)

      case pdef: Ast.PatDef =>
        patternTyper.transformPatDef(pdef) :: Nil

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

  def transform(word: Ast.Word)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    extension (word: Word) def adapt: Word = checker.adapt(word, tt)

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

      case Ast.Ident(name) =>
        val sym = sc.resolveTerm(name, word.pos)
        if sym.isField || sym.isMethod then
          val qual = Ident(sym.owner)(word.span)
          Select(qual, sym.name)(sym.info, word.span).adapt
        else
          checker.checkCapture(sym, word.pos)
          Ident(sym)(word.span).adapt

      case record: Ast.RecordLit =>
        transform(record).adapt

      case Ast.TypeAscribe(expr, tpt) =>
        val tpt2 = transformType(tpt)
        val expr2 =
          given TargetType = TargetType.Known(tpt2.tpe)
          transform(expr)
        Encoded(expr2)(tpt2.tpe, word.span).adapt

      case tag: Ast.Tag =>
        transformTagged(tag, values = Nil).adapt

      case Ast.Select(qual, name) =>
        val qual2 =
          given TargetType = TargetType.TermMember(name)
          transform(qual)

        val qualType = qual2.tpe
        qualType.getTermMember(name) match
          case Some(tp) =>
            tp match
              case TypeRef(sym) if !sym.isField && !sym.isMethod && !sym.isType =>
                Ident(sym)(word.span).adapt

              case _ =>
                Select(qual2, name)(tp, word.span).adapt

          case None =>
            // Error already reported
            errorWord(word.span)

      case lambda: Ast.Lambda =>
        transform(lambda).adapt

      case Ast.Fence(phrase) =>
        transform(phrase)

      case app: Ast.Apply =>
        app.fun match
          case tag: Ast.Tag => transformTagged(tag, app.args)
          case _ => transformCall(app)

      case call: Ast.InfixCall =>
        transformInfixCall(call)

      case call: Ast.DotlessCall =>
        transformDotlessCall(call)

      case Ast.TypeApply(fun, targs) =>
        val fun2 = transform(fun)
        val targs2 = targs.map(transformType)
        checker.checkTypeApply(fun2, targs2).adapt

      case expr: Ast.Expr  =>
        exprTyper.transform(expr)

      case Ast.With(expr, args) =>
        val exprSast = transform(expr)
        val argsSast = for arg <- args yield transform(arg)
        With(exprSast, argsSast)(exprSast.tpe, word.span)

      case Ast.Allow(expr, params) =>
        val exprSast = transform(expr)
        val paramRefs =
          for
            param <- params
          yield
            transformParamRef(param)

        Allow(exprSast, paramRefs)(exprSast.tpe, word.span)

      case ifte: Ast.If =>
        transform(ifte).adapt

      case Ast.While(cond, body) =>
         val cond2 =
           given TargetType = TargetType.Known(defn.BoolType)
           transform(cond)

         val body2 =
           given TargetType = TargetType.Known(VoidType)
           transform(body)

         While(cond2, body2)(word.span).adapt

      case assign: Ast.Assign =>
        transform(assign).adapt

      case patmat: Ast.Match =>
        patternTyper.transformMatch(patmat).adapt

      case vdef: Ast.ValDef =>
        val flags = if vdef.mutable then Flags.Mutable else Flags.empty
        val sym = Symbol.createSymbol(vdef.name, flags, vdef.ident.pos)
        val vdef2 = transformValDef(vdef, sym, sc.owner)
        sc.define(sym)
        vdef2.adapt

      case fdef: Ast.FunDef =>
        val delayedDef = transformFunDef(fdef)
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
        transform(block)

      case obj: Ast.Object =>
        transform(obj).adapt
    }

  def transform(obj: Ast.Object)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): Word =
    val vals = new mutable.ArrayBuffer[ValDef]
    val delayedDefs = new mutable.ArrayBuffer[DelayedDef[FunDef]]

    val thisSym = Symbol.createSymbol("this", Flags.Synthetic, obj.pos)

    // scope for checking member methods
    given sc2: Scope = sc.fresh(thisSym)

    for case vdef: Ast.ValDef <- obj.members do
      val flags = if vdef.mutable then Flags.Field | Flags.Mutable else Flags.Field
      val sym = Symbol.createSymbol(vdef.name, flags, vdef.ident.pos)
      sc2.define(sym)

      // Using the outer scope to check field bodys
      given Scope = sc
      val vdefTyped = transformValDef(vdef, sym, owner = thisSym)
      vals += vdefTyped

    // `this` should not be available in field initialization
    sc2.define(thisSym)

    for case fdef: Ast.FunDef <- obj.members do
      if fdef.preParamCount != 0 then
        Reporter.error("Methods cannot have pre-arguments", fdef.pos)

      given TargetType = TargetType.ObjectMember
      val delayedDef = transformFunDef(fdef)

      // Operator name should not be called directly without a prefix
      if !Name.isOperator(delayedDef.symbol.name) then
        sc2.define(delayedDef.symbol)

      delayedDefs += delayedDef

    // external object type
    val fieldTypes = vals.map(vdef => NamedInfo(vdef.name, vdef.symbol.info)).toList
    val methodTypes = delayedDefs.map(d => NamedInfo(d.symbol.name, TypeRef(d.symbol))).toList
    val mutables = vals.filter(_.isMutable).map(_.name).toList
    val objType = ObjectType(fieldTypes, methodTypes, mutables)

    defn.add(thisSym, sc.owner, objType)

    val defs: List[FunDef] =
      for delayedDef <- delayedDefs.toList yield delayedDef.force()

    Object(thisSym, vals.toList, defs)(objType, obj.span)

  def transform(block: Ast.Block)
    (using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType)
  : Word =

    val phrases = block.phrases
    val words =
      given Scope = sc.fresh()
      for (phrase, i) <- phrases.zipWithIndex yield
        given TargetType =
          if i == phrases.size - 1 then tt
          else TargetType.Known(VoidType)

        transform(phrase)

    if words.isEmpty then
      checker.adapt(Block(Nil)(VoidType, block.span), tt)

    else
      Block(words)(words.last.tpe, block.span)


  def instantiatePoly(polyType: ProcType, fun: Word)(using Definitions, Reporter, Source): Word =
    assert(polyType.tparams.nonEmpty, polyType.show)

    val tvars = for tparam <- polyType.tparams yield TypeVar(tparam.name, this.inferencer)
    val targs = tvars.map(tvar => TypeTree(tvar)(fun.span))
    val tpe = polyType.instantiate(tvars)

    checker.delayedCheck {
      for tvar <- tvars do checker.checkInstantiated(tvar, fun.pos)

      checker.checkBounds(polyType.tparams, targs)
    }

    TypeApply(fun, targs)(tpe, fun.span)

  /** Handles explicit postfix call syntax f(arg1, arg2, ...) */
  def transformCall(apply: Ast.Apply)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    var fun =
      given TargetType = TargetType.Fun(apply.args.size)
      transform(apply.fun)

    // Auto .apply insertion --- apply can be polymorphic
    //
    // The `.apply` insertion happens at the transform for `Apply`.
    // It ensures that in `Apply(fun, args)` the fun is an ident or select.
    if fun.tpe.hasApplyMethod then
      val memberType = fun.tpe.termMember("apply")
      fun = Select(fun, "apply")(memberType, fun.span)

    if fun.tpe.isPolyType then
      fun = instantiatePoly(fun.tpe.asProcType, fun)

    val funType = fun.tpe

    if funType.isProcType then
      val procType = funType.asProcType
      val paramSize = procType.paramTypes.size

      // Always prefer type constraints from outer scope
      tt match
        case TargetType.Known(tp) =>
           Subtyping.conforms(procType.resultType, tp)

        case _ =>

      val preArgTypes = procType.preParamTypes
      if preArgTypes.size != 0 then
        Reporter.error(
          s"The postfix call syntax cannot be used, as the function takes prefix arguments",
          fun.pos)
        errorWord(apply.span)

      else if apply.args.size != paramSize then
        Reporter.error(
          s"The function expects $paramSize arguments, found = ${apply.args.size}",
          apply.pos)
        errorWord(apply.span)

      else
        val argsTyped =
          for (arg, paramType) <- apply.args.zip(procType.paramTypes) yield
            given TargetType = TargetType.Known(paramType)
            transform(arg)

        val word = Apply(fun, argsTyped)(procType.resultType, apply.span)
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

    if objType.isObjectType then
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
              val argTyped =
                given TargetType = TargetType.Known(procType.paramTypes.head)
                transform(arg)

              val word = Apply(fun, argTyped :: Nil)(procType.resultType, call.span)
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

    // Always prefer type constraints from outer scope
    tt match
      case TargetType.Known(tp) =>
         Subtyping.conforms(procType.resultType, tp)

      case _ =>

    if preArgs.size != preParamCount then
      Reporter.error(
        s"Function ${fun.show} expects $preParamCount pre arguments, found = ${preArgs.size}",
        fun.pos)
      errorWord(call.span)

    else if postArgs.size != postParamCount then
      Reporter.error(
        s"Function ${fun.show} expects $postParamCount post arguments, found = ${postArgs.size}",
        fun.pos)
      errorWord(call.span)

    else
      val preArgs2 =
        for (arg, paramType) <- preArgs.zip(procType.preParamTypes) yield
          given TargetType = TargetType.Known(paramType)
          transform(arg)

      val postArgs2 =
        for (arg, paramType) <- postArgs.zip(procType.postParamTypes) yield
          given TargetType = TargetType.Known(paramType)
          transform(arg)

      val word = Apply(fun, preArgs2 ++ postArgs2)(procType.resultType, call.span)
      val rewrite = Rewriting.rewriteShortcutAndOr(word)
      checker.adapt(rewrite, tt)

  def transform(assign: Ast.Assign)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): Word =
    val Ast.Assign(ref, rhs) = assign

    ref match
      case id: Ast.Ident =>
        val sym = sc.resolveTerm(id.name, id.pos)

        checker.checkMutable(sym, id.pos)

        given TargetType = TargetType.Known(sym.info)
        val rhs2 = transform(rhs)

        if sym.isField then
          val qual = Ident(sym.owner)(id.span)
          FieldAssign(qual, sym.name, rhs2)(assign.span)

        else
          val id = Ident(sym)(ref.span)
          checker.checkCapture(sym, id.pos)
          Assign(id, rhs2)(assign.span)

      case Ast.Select(qual, name) =>
        val qual2 =
          given TargetType = TargetType.TermMember(name)
          transform(qual)

        if qual2.tpe.isObjectType then
          val objType = qual2.tpe.asObjectType
          objType.getMemberType(name) match
            case Some(tp) =>
              if !objType.isMutable(name) then
                Reporter.error(s"The field $name is immutable", ref.pos)

              val rhs2 =
                given TargetType = TargetType.Known(tp)
                transform(rhs)

              FieldAssign(qual2, name, rhs2)(assign.span)

            case None =>
              // error already reported
              errorWord(assign.span)

        else
          Reporter.error("Expect an object, found = " + qual2.tpe.show, qual.pos)
          errorWord(assign.span)

  private def transformParamRef(ref: Ast.RefTree)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): Ident =
    val paramRef =
      given TargetType = TargetType.Unknown
      transform(ref)

    val paramSym =
      paramRef.tpe match
        case TypeRef(sym) if sym.isAllOf(Flags.Param | Flags.Context) =>
          sym

        case tp =>
          Reporter.error("A reference to a contextual parameter expected, found = " + tp.show, paramRef.pos)
          Symbol.createSymbol(ref.name, ErrorType, Flags.Synthetic, sc.owner, paramRef.pos)

    Ident(paramSym)(ref.span)

  private def transform(arg: Ast.WithArg)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): WithArg =
    val paramRef = transformParamRef(arg.paramRef)

    val rhsSast =
      given TargetType =
        if paramRef.tpe.isError then TargetType.ValueType
        else TargetType.Known(paramRef.symbol.dealias.info)
      transform(arg.rhs)

    WithArg(paramRef, rhsSast)(arg.span)

  private def transform(ifte: Ast.If)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val Ast.If(cond, thenp, elsep) = ifte

    val cond2 =
      given TargetType = TargetType.Known(defn.BoolType)
      transform(cond)

    val then2 = transform(thenp)
    val else2 = transform(elsep)

    // result type
    val commonType = checker.commonResultType(then2.tpe, else2.tpe, ifte.pos)
    If(cond2, then2, else2)(commonType, ifte.span)

  private def transform(record: Ast.RecordLit)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
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

  private def transform(lambda: Ast.Lambda)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
     val Ast.Lambda(params, body) = lambda

     val targetFunTypeOpt: Option[ProcType] = tt.knownType.flatMap(_.getFunctionApplyType)

     if targetFunTypeOpt.nonEmpty then
       val expect = targetFunTypeOpt.get.paramCount
       if expect != params.size then
         Reporter.error(s"Expect a function with $expect parameters, found = ${params.size}", lambda.pos)
         return errorWord(lambda.span)

     // Each object has a self symbol
     val thisSym = Symbol.createSymbol("this", Flags.Synthetic, lambda.pos)

     val funSym = Symbol.createSymbol("apply", Flags.Method | Flags.Synthetic, lambda.pos)
     val lambdaScope = sc.fresh(funSym)

     val tvars = new mutable.ArrayBuffer[(TypeVar, Ast.Param)]

     def inferParamType(i: Int): Type =
       targetFunTypeOpt match
         case Some(funType) => funType.paramTypes(i)
         case None =>
           val tvar = TypeVar(params(i).name, this.inferencer)
           tvars += tvar -> params(i)
           tvar

     val ctxParams =
       for funType <- targetFunTypeOpt yield funType.receives.getOrElse(Nil)


     val paramSyms =
      for (param, i) <- params.zipWithIndex yield
        val tp = if param.tpt.isEmpty then inferParamType(i) else transformType(param.tpt).tpe
        val paramSym = Symbol.createSymbol(param.name, tp, Flags.Param, funSym, param.pos)
        lambdaScope.define(paramSym)
        paramSym

     val bodyTargetType = targetFunTypeOpt match
       case Some(funType) => TargetType.Known(funType.resultType)
       case None => TargetType.ValueType

     val bodyTyped =
       given Scope = lambdaScope
       given TargetType = bodyTargetType
       transform(body)

     // Provide type info for the function symbol
     val procType = ProcType(tparams = Nil, paramSyms.map(_.toNamedInfo), bodyTyped.tpe, ctxParams, preParamCount = 0)
     defn.add(funSym, thisSym, procType)

     for (tvar, param) <- tvars do
       checker.checkInstantiated(tvar, param.pos)

     val tparamSyms = Nil
     val tpt = TypeTree(bodyTyped.tpe)(body.span)
     val funDef = FunDef(funSym, tparamSyms, paramSyms, tpt, bodyTyped)(lambda.span)
     val objType = ObjectType(fields = Nil, methods = NamedInfo("apply", procType) :: Nil, mutableFields = Nil)

     defn.add(thisSym, sc.owner, objType)

     Object(thisSym, vals = Nil, defs = funDef :: Nil)(objType, lambda.span)


  private def transformParamDef(pdef: Ast.ParamDef)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source)
  : List[DelayedDef[Def]] =

    val ip = lazyDefn.infoProvider

    // given definitions are lazy
    given Definitions = lazyDefn.value

    var flags = Flags.Param | Flags.Context
    if pdef.default.nonEmpty then
      flags = flags | Flags.Default

    val paramSym = Symbol.createSymbol(pdef.name, flags, pdef.pos)
    ip.addLazy(paramSym, sc.owner, () => transformType(pdef.tpt).tpe)

    val paramDefSast = () =>
      val tpt = TypeTree(paramSym.info)(pdef.tpt.span)
      ParamDef(paramSym, tpt)(pdef.span)

    val delayedParamDef = DelayedDef(paramSym, paramDefSast)

    pdef.default match
      case Some(rhs) =>
        /* Desugaring for an optional context parameter
         *
         *    <Context> <Default> param a: T
         *
         *    <Default> fun a$default = rhs
         */

        val defaultFunSym = Symbol.createSymbol(pdef.name + "$default", Flags.Fun | Flags.Default | Flags.Synthetic, pdef.pos)

        val funInfo = () =>
          ProcType(
            tparams = Nil, params = Nil, resultType = paramSym.info,
            receives = None, preParamCount = 0)

        ip.addLazy(defaultFunSym, sc.owner, funInfo)

        val defaultFunDefSast = () =>
          given Scope = sc.fresh(defaultFunSym)
          given TargetType = TargetType.Known(paramSym.info)
          val body = transform(rhs)
          val tpt = TypeTree(paramSym.info)(pdef.tpt.span)
          FunDef(defaultFunSym, tparams = Nil, params = Nil, tpt, body)(rhs.span)

        DelayedDef(defaultFunSym, defaultFunDefSast) :: delayedParamDef :: Nil

      case None =>
        delayedParamDef :: Nil

  private def transformValDef(vdef: Ast.ValDef, sym: Symbol, owner: Symbol)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): ValDef =
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
      if vdef.tpt.isEmpty then rhs.tpe else givenType

    defn.add(sym, owner, tp)

    ValDef(sym, rhs)(vdef.span)

  private def transformFunDef(funDef: Ast.FunDef)
    (using
      lazyDefn: Definitions.Lazy | Definitions, sc: Scope, rp: Reporter,
      so: Source, tt: TargetType)
  : DelayedDef[FunDef] =

    val flags = if tt == TargetType.ObjectMember then Flags.Method else Flags.Fun

    val funSym = Symbol.createSymbol(funDef.name, flags, funDef.ident.pos)
    given funScope: Scope = sc.fresh(funSym)

    given Definitions = lazyDefn match
      case lazyDefn: Definitions.Lazy => lazyDefn.value
      case defn: Definitions => defn

    lazy val tparamSyms =
      for tparam <- funDef.tparams yield
        val bound =
          if tparam.bound.isEmpty then
            TypeBound(BottomType, AnyType)
          else
            val boundTree = transformType(tparam.bound)
            TypeBound(BottomType, boundTree.tpe)

        val sym = Symbol.createSymbol(tparam.name, bound, Flags.Type | Flags.Param, funSym, tparam.pos)
        funScope.define(sym)
        sym

    lazy val paramSyms =
      tparamSyms

      for param <- funDef.params yield
        val tpt = transformType(param.tpt)
        val paramSym = Symbol.createSymbol(param.name, tpt.tpe, Flags.Param, funSym, param.pos)
        funScope.define(paramSym)
        paramSym

    lazy val givenResultType =
      tparamSyms

      assert(!funDef.resultType.isEmpty)
      val resTypeTree = transformType(funDef.resultType)
      checker.delayedCheck { checker.checkValueType(resTypeTree) }
      resTypeTree.tpe

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
        typedBody.tpe
      end if

    lazy val typedBody =
      paramSyms

      val targetType =
        if !funDef.resultType.isEmpty then
          TargetType.Known(givenResultType)
        else
          TargetType.ValueType

      given TargetType = targetType
      transform(funDef.body)

    lazy val ctxParams = funDef.receives.map: params =>
      for
        param <- params
      yield
        transformParamRef(param).symbol

    def computeInfo(resultType: Type) =
        ProcType(tparamSyms, paramSyms.map(_.toNamedInfo), resultType, ctxParams, funDef.preParamCount)

    lazyDefn match
      case lazyDefn: Definitions.Lazy =>
        val ip = lazyDefn.infoProvider
        ip.addLazy(funSym, sc.owner,  () => computeInfo(resultType), () => computeInfo(ErrorType))

      case defn: Definitions =>
        defn.addLazy(funSym, sc.owner,  () => computeInfo(resultType), () => computeInfo(ErrorType))

    val typer = () =>
      val tpt = TypeTree(resultType)(funDef.resultType.span)
      FunDef(funSym, tparamSyms, paramSyms, tpt, typedBody)(funDef.span)

    DelayedDef(funSym, typer)

  private def transformTypeDef(tdef: Ast.TypeDef)
      (using lazyDefn: Definitions.Lazy | Definitions, sc: Scope, rp: Reporter, so: Source)
  : DelayedDef[TypeDef] =

    val flags = Flags.Type
    val typeSym = Symbol.createSymbol(tdef.name, flags, tdef.ident.pos)

    given Definitions = lazyDefn match
      case lazyDefn: Definitions.Lazy => lazyDefn.value
      case defn: Definitions => defn

    given sc2: Scope = sc.fresh(typeSym)
    lazy val tparamSyms =
      for tparam <- tdef.tparams yield
        val bound =
          if tparam.bound.isEmpty then
            TypeBound(BottomType, AnyType)
          else
            val boundTree = transformType(tparam.bound)
            TypeBound(BottomType, boundTree.tpe)

        val sym = Symbol.createSymbol(tparam.name, bound, Flags.Type | Flags.Param, typeSym, tparam.pos)
        sc2.define(sym)
        sym

    def computeInfo(): Type =
      // force creation of symbols for type parameters
      tparamSyms

      if tdef.tparams.isEmpty then
        if tdef.rhs.isEmpty then
          if sc.owner.fullName == "stk.Predef" then
            val typeName = tdef.name
            if typeName == "Any" then AnyType
            else if typeName == "Bottom" then BottomType
            else
              // Int, Char, Byte
              TypeBound(BottomType, AnyType)

          else
            TypeBound(BottomType, AnyType)
        else
          val rhs = transformType(tdef.rhs)
          checker.delayedCheck { checker.checkValueType(rhs) }

          if tdef.isBound then
            TypeBound(BottomType, rhs.tpe)
          else
            rhs.tpe

      else
        val rhs = transformType(tdef.rhs)
        checker.delayedCheck { checker.checkValueType(rhs) }

        val rhsType =
          if tdef.isBound then TypeBound(BottomType, rhs.tpe)
          else rhs.tpe

        TypeLambda(tparamSyms, rhsType)

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

  private def transformSection(section: Ast.Section)
      (using lazyDefn: Definitions.Lazy, sc: Scope, rp: Reporter, so: Source)
  : DelayedDef[Section] =

    given Definitions = lazyDefn.value

    val sym = Symbol.createSymbol(section.name, Flags.Section, section.ident.pos)

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

      new NameTableInfo(nameTable)
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

        val sym = Symbol.createSymbol(tparam.name, bound, Flags.Type | Flags.Param, sc.owner, tparam.pos)
        defScope.define(sym)
        sym

    val paramSyms =
      for param <- ddef.params yield
        val tpt = transformType(param.tpt)
        val paramSym = Symbol.createSymbol(param.name, tpt.tpe, Flags.Param, sc.owner, param.pos)
        defScope.define(paramSym)
        paramSym

    val resultType =
      assert(!ddef.resultType.isEmpty)
      val resTypeTree = transformType(ddef.resultType)
      checker.delayedCheck { checker.checkValueType(resTypeTree) }
      resTypeTree.tpe

    val ctxParams = ddef.receives.map: params =>
      for
        param <- params
      yield
        transformParamRef(param).symbol

    val finalType =
      ProcType(tparamSyms, paramSyms.map(_.toNamedInfo), resultType, ctxParams, preParamCount = 0)


    TypeTree(finalType)(ddef.span)

  /** Type check type tree
    *
    * Checks must be delayed by using `checker.delayedCheck`.
    */
  def transformType(tpt: Ast.TypeTree)(using defn: Definitions, sc: Scope, rp: Reporter, so: Source): TypeTree =
    tpt match
      case Ast.Ident(name) =>
        sc.resolveType(name) match
          case Some(sym) =>
            TypeTree(TypeRef(sym))(tpt.span)

          case None =>
            Reporter.error("Unknown type " + tpt, tpt.pos)
            TypeTree(ErrorType)(tpt.span)

      case Ast.Select(qual, name) =>
        val qual2 =
          given TargetType = TargetType.Unknown
          transform(qual)

        qual2.tpe match
          case TypeRef(sym) if sym.isContainer =>
            val nsInfo = sym.dealiasedInfo.as[NameTableInfo]
            nsInfo.resolveType(name) match
              case Some(sym) =>
                val tp = TypeRef(sym)
                TypeTree(tp)(tpt.span)

              case None =>
                Reporter.error(s"The namespace $sym does not contain the type member $name", qual.pos)
                TypeTree(ErrorType)(tpt.span)

          case tp =>
            TypeTree(ErrorType)(tpt.span)

      case Ast.RecordType(fields) =>
        val fieldTypes = new mutable.ArrayBuffer[NamedInfo[Type]]
        for field <- fields do
          if fieldTypes.exists(_.name == field.name) then
            Reporter.error("Field " + field.name + " already defined", field.pos)
          else
            val tpt = transformType(field.tpt)
            checker.delayedCheck { checker.checkValueType(tpt) }
            fieldTypes += NamedInfo(field.name, tpt.tpe)
        end for
        TypeTree(RecordType(fieldTypes.toList))(tpt.span)

      case Ast.TagType(tag, params) =>
        val paramInfos = new mutable.ArrayBuffer[NamedInfo[Type]]
        for param <- params yield
          if paramInfos.exists(_.name == param.name) then
            Reporter.error("Parameter " + param.name + " already defined", param.pos)

          val tpt = transformType(param.tpt)
          checker.delayedCheck { checker.checkValueType(tpt) }
          paramInfos += NamedInfo(param.name, tpt.tpe)
        end for
        TypeTree(TagType(tag.name, paramInfos.toList))(tpt.span)

      case Ast.ObjectType(members) =>
        val fieldTypes = new mutable.ArrayBuffer[NamedInfo[Type]]
        val mutableFields = new mutable.ArrayBuffer[String]
        val methodTypes = new mutable.ArrayBuffer[NamedInfo[Type]]

        members.foreach: member =>
          if fieldTypes.exists(_.name == member.name) || methodTypes.exists(_.name == member.name) then
            Reporter.error("Member " + member.name + " already defined", member.pos)
          else
            member match
              case methodDecl: Ast.FunDef =>
                val memberTypeTree = transformMethodDecl(methodDecl)
                methodTypes += NamedInfo(member.name, memberTypeTree.tpe)
              case vdef: Ast.ValDef =>
                if vdef.mutable then mutableFields += vdef.name
                val fieldTypeTree = transformType(vdef.tpt)
                fieldTypes += NamedInfo(vdef.name, fieldTypeTree.tpe)

        val tp = ObjectType(fieldTypes.toList, methodTypes.toList, mutableFields.toList)
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
        val tctor2 = transformType(tctor)
        val targs2 = for targ <- targs yield transformType(targ)
        checker.delayedCheck { checker.checkBounds(tctor2, targs2) }
        TypeTree(AppliedType(tctor2.tpe, targs2.map(_.tpe)))(tpt.span)

      case Ast.FunctionType(paramTypes, resType, receives) =>
        var i = 0
        val paramTypes2 =
          for paramType <- paramTypes yield
            val tpt = transformType(paramType)
            checker.delayedCheck { checker.checkValueType(tpt) }
            val namedInfo = NamedInfo("param" + i, tpt.tpe)
            i = i+1
            namedInfo

        val ctxParams =
          for
            param <- receives
          yield
            transformParamRef(param).symbol

        val resType2 = transformType(resType)
        checker.delayedCheck { checker.checkValueType(resType2) }
        val applyType = ProcType(tparams = Nil, paramTypes2, resType2.tpe, Some(ctxParams), preParamCount = 0)
        val objType = ObjectType(fields = Nil, methods = NamedInfo("apply", applyType) :: Nil, mutableFields = Nil)
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

  class DelayedDef[+T](val symbol: Symbol, val delayed: () => T):
    private lazy val definition: T = delayed()
    def force()(using Definitions): T =
      symbol.info // force symbol
      definition
