import scala.collection.mutable
import scala.annotation.constructorOnly

import Sast.*
import Types.*
import Flags.*
import Symbols.*
import Positions.*
import Namer.{ Scope, DelayedDef }
import Inference.*

/**
  * The namer handles name resolution and desugaring.
  *
  * It converts ASTs to Semantic ASTs.
  */
class Namer(@constructorOnly reporter: Reporter):
  val checker = new Checker
  val inferencer: Inferencer = new UnificationSolver
  val exprTyper = new ExprTyper(this, checker, inferencer)

  /** Handles cyclic definitions */
  val nonCyclicTypeProvider = new NamerUtils.ValueTypeProvider(using reporter)

  def createPredefScope()(using Reporter): Scope =
    val rootScope = new Scope.RootScope()

    // Predefined type names
    rootScope.define(Predef.Int)
    rootScope.define(Predef.Bool)
    rootScope.define(Predef.Void)

    // Predefined term names
    for sym <- Predef.allSymbols do
      rootScope.define(sym)

    // Prepare scope for user-defined namespaces
    rootScope

  def transform(nss: List[Ast.Namespace])(using rp: Reporter): List[Namespace] =
    // All namespace are located in the scope
    val allNamespaces = new Scope.RootScope()

    // Predef names are by-default accessible. However, other namespaces are not
    // accessible unless explicitly imported.
    val predefScope: Scope = createPredefScope()

    val delayedNamespaces = new mutable.ArrayBuffer[() => Namespace]

    for ns <- nss do
      given source: Source = Reporter.source(ns.source)

      val importScope: Scope = predefScope.fresh()
      val defsScope: Scope = importScope.fresh()

      val nsSym = resolveNamespace(ns.qualid, isBranch = false)(using allNamespaces)
      val nsInfo = nsSym.info.as[NamespaceInfo]

      val delayedDefs = index(ns.defs, nsInfo)(using defsScope)

      val force = () =>
        // Make current namespace name available
        importScope.define(nsSym)
        // handle imports after indexing members
        val imports = new mutable.ArrayBuffer[Symbol]
        for imp <- ns.imports do
          // TODO: what about type names?
          given Scope = allNamespaces
          val sym = resolveGlobal(imp.qualid, isType = false)

          if sym.isAllOf(Flags.NSpace | Flags.Branch) then
            rp.error("Only a concrete namespace can be imported", imp.pos)

          imports += sym
          // TODO: abstract scope and better error position for duplicate imports
          importScope.define(sym)

        val defs = for delayed <- delayedDefs.toList yield delayed.force()
        Namespace(nsSym, ns.fullName, imports.toList, defs)(ns.span)

      delayedNamespaces += force
    end for

    val namespaces = delayedNamespaces.map(_.apply())
    checker.performDelayedChecks()
    namespaces.toList

  /** Resolve namespace and create intermediate namespace on demand
    *
    * It also checks redefinition of namespace.
    */
  def resolveNamespace(qualid: Ast.RefTree, isBranch: Boolean)(using sc: Scope, rp: Reporter, so: Source): Symbol =
    def check(sym: Symbol): Symbol =
      val name = sym.name
      val file = sym.sourcePos.source.file
      if sym.isAllOf(Flags.NSpace) then
        if isBranch && !sym.isAllOf(Flags.Branch) then
          rp.error(s"The $name is already defined as a namespace in $file", qualid.pos)
          sym

        else if !isBranch then
          // leaf namespace should not exist
          if sym.isAllOf(Flags.Branch) then
            rp.error(s"The namespace $name is already defined as a branch name in $file", qualid.pos)
          else
            rp.error(s"The namespace $name is already defined in $file", qualid.pos)

          sym

        else
          sym

      else
        rp.error(s"The $name is already defined as a member in $file", qualid.pos)
        Symbol.createNamespaceSymbol(sym.name, new NamespaceInfo, qualid.pos, isBranch)

    qualid match
      case Ast.Select(qual, name) =>
        assert(qual.isInstanceOf[Ast.RefTree], "Unexpected qualid = " + qualid)
        val sym = resolveNamespace(qual.asInstanceOf[Ast.RefTree], isBranch = true)

        assert(sym.isNamespace, "Not a namespace " + sym)
        val nsInfo = sym.info.as[NamespaceInfo]

        nsInfo.resolveTerm(name) match
          case Some(sym) => check(sym)

          case None =>
            val sym = Symbol.createNamespaceSymbol(name, new NamespaceInfo, qualid.pos, isBranch)
            nsInfo.define(sym)
            sym

      case Ast.Ident(name) =>
        sc.resolve(name, isType = false) match
          case None =>
            val sym = Symbol.createNamespaceSymbol(name, new NamespaceInfo, qualid.pos, isBranch)
            sc.define(sym)
            sym

          case Some(sym) => check(sym)


  /** Resolve a global */
  def resolveGlobal(qualid: Ast.RefTree, isType: Boolean)(using sc: Scope, rp: Reporter, so: Source): Symbol =
    qualid match
      case Ast.Select(qual, name) =>
        val sym = resolveGlobal(qual.asInstanceOf[Ast.RefTree], isType = false)

        if sym.isNamespace then
          val nsInfo = sym.info.as[NamespaceInfo]

          nsInfo.resolveTerm(name) match
            case Some(sym) => sym

            case None =>
              rp.error(s"Member named $name not found in the namespace ${sym.name}", qualid.pos)
              Symbol.createFunSymbol(name, ErrorType, pos = qualid.pos)

        else
          if !sym.info.isError then
            rp.error("Not a namespace, only a namespace can be selected", qual.pos)
          Symbol.createFunSymbol(name, ErrorType, pos = qualid.pos)

      case Ast.Ident(name) =>
        sc.resolve(name, isType) match
          case Some(sym) => sym
          case None =>
            rp.error(s"The name $name is not found", qualid.pos)
            Symbol.createFunSymbol(name, ErrorType, pos = qualid.pos)

  private def index(defs: List[Ast.Def], nsInfo: NamespaceInfo)(using sc: Scope, rp: Reporter, so: Source): List[DelayedDef[Def]] =
    val delayedDefs = new mutable.ArrayBuffer[DelayedDef[Def]]

    for defn <- defs do
      val delayedDef = index(defn)
      // Need to add to both given that the name can be access in two different
      // ways in the current context.
      nsInfo.define(delayedDef.symbol)
      sc.define(delayedDef.symbol)
      delayedDefs += delayedDef

    delayedDefs.toList

  private def index(defn: Ast.Def)(using sc: Scope, rp: Reporter, so: Source): DelayedDef[Def] =
    defn match
      case vdef: Ast.ValDef =>
        transform(vdef)

      case funDef: Ast.FunDef =>
        transform(funDef)

      case tdef: Ast.TypeDef =>
        transform(tdef)
    end match
  end index

  private def checkCapture(sym: Symbol, span: Span)(using sc: Scope, rp: Reporter, so: Source): Unit =
    if sym.isAllOf(Flags.Val | Flags.Mutable | Flags.Local) then
      // check no capture of mutable local vars
      val ownerFunOpt = sc.owningFunctionOf(sym)
      val curFunOpt = sc.owningFunction
      if ownerFunOpt != curFunOpt then
        Reporter.error("Cannot capture local mutable variable " + sym.name, span.toPos)

  def transform(block: Ast.Block)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val phrases = block.phrases
    var sc2 = sc
    val words =
      for (phrase, i) <- phrases.zipWithIndex yield
        sc2 = sc2.fresh()
        val tt2 =
          if i == phrases.size - 1 then tt
          else TargetType.Known(VoidType)
        transform(phrase)(using sc2, rp, so, tt2)

    if words.isEmpty then Phrase(Nil)(VoidType, block.span)
    else Phrase(words)(words.last.tpe, block.span)

  def transform(phrase: Ast.Phrase)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    extension (word: Word) def check: Word = checker.adapt(word, tt)

    phrase match
      case word: Ast.Word =>
        transform(word)

      case ifte: Ast.If =>
        transform(ifte)

      case Ast.While(cond, body) =>
         val cond2 = transform(cond)(using sc, rp, so, TargetType.Known(BoolType))
         val body2 = transform(body)(using sc, rp, so, TargetType.Known(VoidType))
         While(cond2, body2)(phrase.span).check

      case Ast.Assign(id, words) =>
        val sym = sc.resolve(id.name, id.span)

        checker.checkMutable(sym, id.span)
        checkCapture(sym, id.span)

        given TargetType = TargetType.Known(sym.info)
        val rhs = transform(words)
        Assign(sym, rhs)(phrase.span).check

      case patmat: Ast.Match =>
        transform(patmat)

      case vdef: Ast.ValDef =>
        val delayedDef = transform(vdef)
        val vdef2 = delayedDef.force()
        // a val is not available for checking its rhs
        sc.define(delayedDef.symbol)
        vdef2.check

      case fdef: Ast.FunDef =>
        val delayedDef = transform(fdef)
        // A function is available for checking its rhs
        sc.define(delayedDef.symbol)
        delayedDef.force().check

      case tdef: Ast.TypeDef =>
        val delayedDef = transform(tdef)
        // A type definition is available for checking its rhs
        sc.define(delayedDef.symbol)
        delayedDef.force().check

  def transform(word: Ast.Word)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    extension (word: Word) def adapt: Word = checker.adapt(word, tt)

    word match
      case Ast.IntLit(v)  =>
        IntLit(v)(word.span).adapt

      case Ast.BoolLit(v) =>
        BoolLit(v)(word.span).adapt

      case Ast.Ident(name) =>
        val sym = sc.resolve(name, word.span)
        checkCapture(sym, word.span)
        val id = Ident(sym)(word.span)
        val autoApplied =
          if sym.isFunction && sym.info.isProcType then
            val procType = sym.info.asProcType
            if procType.params.isEmpty then
              Apply(id, args = Nil)(procType.resultType, id.span)
            else
              id
          else
            id

        autoApplied.adapt

      case record: Ast.RecordLit =>
        transform(record).adapt

      case variant: Ast.Variant =>
        transform(variant).adapt

      case Ast.Select(qual, name) =>
        val qual2 =
          given TargetType = TargetType.TermMember(name)
          transform(qual)

        qual2.tpe match
          case TypeRef(sym) if sym.isNamespace =>
            val nsInfo = sym.info.as[NamespaceInfo]
            nsInfo.resolveTerm(name) match
              case Some(sym) =>
                Ident(sym)(word.span).adapt

              case None =>
                Reporter.error(s"The namespace $sym does not contain the member $name", word.pos)
                Phrase(Nil)(ErrorType, word.span)

          case tp =>
            if tp.isRecordType then
              val tp = qual2.tpe.asRecordType.fieldType(name)
              Select(qual2, name)(tp, word.span).adapt
            else
              Phrase(Nil)(ErrorType, word.span)

      case lambda: Ast.Lambda =>
        transform(lambda).adapt

      case Ast.TypeApply(fun, targs) =>
        val fun2 = transform(fun)
        val targs2 = targs.map(transformType)
        checker.checkTypeApply(fun2, targs2).adapt

      case expr: Ast.Expr  =>
        exprTyper.transform(expr)

      case block: Ast.Block =>
        transform(block)

  private def transform(ifte: Ast.If)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val Ast.If(cond, thenp, elsep) = ifte
    val cond2 = transform(cond)(using sc, rp, so, TargetType.Known(BoolType))
    val then2 = transform(thenp)
    val else2 = transform(elsep)

    // adapt result type
    val commonType = checker.commonResultType(then2.tpe, else2.tpe, ifte.span)
    val then3 = checker.adapt(then2, commonType)
    val else3 = checker.adapt(else2, commonType)
    If(cond2, then3, else3)(commonType, ifte.span)

  private def transform(record: Ast.RecordLit)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
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

  private def transform(variant: Ast.Variant)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val Ast.Variant(tag, values, typ) = variant

    val unionType =
      if typ.isEmpty then tt.knownType.getOrElse(AnyType)
      else transformType(typ).tpe
    val tagTypesOpt = checker.tagTypes(tag, unionType, typ.span)

    tagTypesOpt match
      case Some(tagTypes) =>
        if tagTypes.size != values.size then
          Reporter.error(s"Expect ${tagTypes.size} args, found = ${values.size}", tag.pos)

        val values2 =
          for (value, tp) <- values.zip(tagTypes) yield
            given TargetType = TargetType.Known(tp)
            transform(value)

        // encode variants as records
        val tagIndex = unionType.asUnionType.tagIndex(tag.name)
        val encodedValue = Desugaring.encodeVariant(tagIndex, values2, tagTypes, tag.span, variant.span)
        Encoded(encodedValue)(unionType)

      case None =>
        Phrase(Nil)(ErrorType, variant.span)


  private def transform(patmat: Ast.Match)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val sc2 = sc.fresh()

    val Ast.Match(scrutinee, cases) = patmat
    val scrutinee2 = transform(scrutinee)(using sc, rp, so, TargetType.ValueType)

    val scrutType = scrutinee2.tpe
    val scrutSym = Symbol.createValueSymbol("scrutinee", scrutType, Flags.Local, scrutinee2.pos)
    val scrutIdent = Ident(scrutSym)(scrutinee.span)
    val bind = ValDef(scrutSym, scrutinee2)(scrutinee.span)
    sc2.define(scrutSym)

    val allTags = if scrutType.isUnionType then scrutType.asUnionType.tags else Nil

    def subtractPattern(tags: List[String], pat: Ast.Pattern): List[String] =
      if tags.isEmpty then
        Reporter.error("The case is unreachable", pat.pos)
        Nil
      else pat match
        case Ast.Wildcard() => Nil
        case Ast.TagPat(Ast.Ident(name), _) =>
          if tags.contains(name) then
            tags.filter(_ != name)
          else
            if allTags.contains(name) then
              Reporter.error("The case is unreachable", pat.pos)
            tags

    def transformCases(cases: List[Ast.Case], resType: Type, tagsRest: List[String]): Word =
      cases match
        case caseDef :: rest =>
          val tagsRest2 = subtractPattern(tagsRest, caseDef.pat)
          transform(scrutIdent, caseDef, resType, tp => transformCases(rest, tp, tagsRest2))(using sc2)

        case Nil =>
          if tagsRest.nonEmpty then
            Reporter.error("Unmatched case(s): " + tagsRest.mkString(", "), scrutIdent.pos)
          // abort
          val abort = Ident(Predef.abort)(scrutIdent.span)
          val args = IntLit(1)(scrutIdent.span) :: Nil
          val app = Apply(abort, args)(BottomType, patmat.span)
          checker.adapt(app, resType)

      end match

    val body = transformCases(cases, BottomType, allTags)
    Phrase(bind :: body :: Nil)(body.tpe, patmat.span)

  private def transform
      (scrut: Ident, caseDef: Ast.Case, resType: Type, cont: Type => Word)
      (using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =

    val caseScope = sc.fresh()

    val Ast.Case(pat, body) = caseDef
    val scrutSpan = scrut.span
    val scrutType = scrut.tpe

    pat match
      case Ast.Wildcard() =>
        val body2 = transform(body)(using caseScope)
        val commonType = checker.commonResultType(body2.tpe, resType, body2.span)
        val elsep = cont(commonType)
        checker.adapt(body2, elsep.tpe)

      case Ast.TagPat(tag, bindings) =>
        val tagTypesOpt = checker.tagTypes(tag, scrutType, scrutSpan)
        val tagTypes = tagTypesOpt.getOrElse(Nil)

        if tagTypesOpt.isEmpty then
          cont(BottomType)

        else if tagTypes.size != bindings.size then
          Reporter.error(s"The tag takes ${tagTypes.size} arguments, found = ${bindings.size}", tag.pos)
          cont(BottomType)

        else
          val encodeType = Desugaring.encodeUnionType(tagTypes)
          val encodedScrut = Encoded(scrut)(encodeType)

          val vals = mutable.ArrayBuffer.empty[ValDef]
          for (binding, i) <- bindings.zipWithIndex do
            val arg = Desugaring.selectVariantArg(encodedScrut, i, binding.span)
            val sym = Symbol.createValueSymbol(binding.name, arg.tpe, Flags.Local, arg.pos)
            vals += ValDef(sym, arg)(binding.span)
            caseScope.define(sym)

          val tagIndex =
            if tagTypesOpt.isEmpty then -1
            else scrutType.asUnionType.tagIndex(tag.name)

          val cond = Desugaring.testVariantTag(encodedScrut, tagIndex, tag.span)
          val body2 = transform(body)(using caseScope, rp, so, tt)
          val commonType = checker.commonResultType(body2.tpe, resType, body2.span)
          val elsep = cont(commonType)
          val commonType2 = checker.commonResultType(body2.tpe, elsep.tpe, body2.span)
          val adapted = checker.adapt(body2, commonType2)

          val body3 = Phrase(vals.toList :+ adapted)(adapted.tpe, caseDef.span)
          If(cond, body3, elsep)(body3.tpe, caseDef.span)

  private def transform(lambda: Ast.Lambda)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
     val Ast.Lambda(params, body) = lambda

     val targetFunTypeOpt: Option[FunctionType] = tt.knownType.flatMap: tp =>
       if tp.isFunctionType then
         Some(tp.asFunctionType)
       else
         None

     if targetFunTypeOpt.nonEmpty then
       val expect = targetFunTypeOpt.get.paramCount
       if expect != params.size then
         Reporter.error(s"Expect a function with $expect parameters, found = ${params.size}", lambda.pos)
         return Phrase(words = Nil)(ErrorType, lambda.span)

     val funSym = Symbol.createFunSymbol("anon", this.nonCyclicTypeProvider, Flags.Local, lambda.pos)
     val lambdaScope = sc.fresh(funSym)

     val tvars = new mutable.ArrayBuffer[(TypeVar, Ast.Param)]

     def inferParamType(i: Int): Type =
       targetFunTypeOpt match
         case Some(funType) => funType.paramTypes(i)
         case None =>
           val tvar = TypeVar(params(i).name, this.inferencer)
           tvars += tvar -> params(i)
           tvar

     val paramSyms =
      for (param, i) <- params.zipWithIndex yield
        val tp = if param.typ.isEmpty then inferParamType(i) else transformType(param.typ).tpe
        val paramSym = Symbol.createParamSymbol(param.name, tp, param.pos)
        lambdaScope.define(paramSym)
        paramSym

     val bodyTargetType = targetFunTypeOpt match
       case Some(funType) => TargetType.Known(funType.resultType)
       case None => TargetType.ProperType

     val bodyTyped = transform(body)(using lambdaScope, rp, so, bodyTargetType)

     // Provide type info for the function symbol
     val procType = ProcType(paramSyms.map(_.toNamedInfo), bodyTyped.tpe, preParamCount = 0)
     this.nonCyclicTypeProvider.addProvider(funSym, () => procType)

     for (tvar, param) <- tvars do
       checker.checkInstantiated(tvar, param.span)

     val tparamSyms = Nil
     val funDef = FunDef(funSym, tparamSyms, paramSyms, bodyTyped)(locals = Nil, captures = Nil, lambda.span)
     val lambdaType = procType.toFunType
     val ref = Ident(funSym)(lambda.span)
     Phrase(funDef :: ref :: Nil)(lambdaType, lambda.span)

  private def transform(vdef: Ast.ValDef)(using sc: Scope, rp: Reporter, so: Source): DelayedDef[ValDef] =
    var flags: Flags = Flags.empty
    if vdef.mutable then
      flags = flags | Flags.Mutable

    if sc.isLocalScope then
      flags = flags | Flags.Local

    val sym = Symbol.createValueSymbol(vdef.name, this.nonCyclicTypeProvider, flags, vdef.ident.pos)

    lazy val givenType: Type =
      val tpt = transformType(vdef.typ)
      val tp2 = checker.checkValueType(tpt.tpe, tpt.span)
      tp2

    val rhs: Word =
      given Scope = sc.fresh(sym)
      given TargetType =
        if vdef.typ.isEmpty then TargetType.ValueType
        else TargetType.Known(givenType)
      transform(vdef.rhs)

    def computeType(): Type =
      if vdef.typ.isEmpty then rhs.tpe else givenType

    this.nonCyclicTypeProvider.addProvider(sym, computeType)

    val typer = () => ValDef(sym, rhs)(vdef.span)
    DelayedDef(sym, typer)

  private def transform(funDef: Ast.FunDef)(using sc: Scope, rp: Reporter, so: Source): DelayedDef[FunDef] =
    var flags: Flags = Flags.empty
    if sc.isLocalScope then
      flags = flags | Flags.Local

    val sym = Symbol.createFunSymbol(funDef.name, this.nonCyclicTypeProvider, flags, funDef.ident.pos)
    val funScope = sc.fresh(sym)

    lazy val tparamSyms =
      for tparam <- funDef.tparams yield
        lazy val bound =
          if tparam.bound.isEmpty then
            TypeBound(BottomType, AnyType)
          else
            val boundTree = transformType(tparam.bound)(using funScope)
            TypeBound(BottomType, boundTree.tpe)

        val infoProvider: InfoProvider = (sym: Symbol) => bound
        val sym = Symbol.createTypeParamSymbol(tparam.name, infoProvider, tparam.pos)
        funScope.define(sym)
        sym

    lazy val paramSyms =
      tparamSyms

      for param <- funDef.params yield
        val tpt = transformType(param.typ)(using funScope)
        val paramSym = Symbol.createParamSymbol(param.name, tpt.tpe, param.pos)
        funScope.define(paramSym)
        paramSym

    lazy val givenResultType =
      tparamSyms

      assert(!funDef.resType.isEmpty)
      val resTypeTree = transformType(funDef.resType)(using funScope)
      checker.delayedCheck { checker.checkVoidOrValueType(resTypeTree) }
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
      if !funDef.resType.isEmpty then
        givenResultType
      else
        typedBody.tpe
      end if

    lazy val typedBody =
      paramSyms

      val targetType =
        if !funDef.resType.isEmpty then
          TargetType.Known(givenResultType)
        else
          TargetType.ProperType

      given Scope = funScope
      given TargetType = targetType
      transform(funDef.body)

    def computeInfo(resultType: Type) =
      val procType = ProcType(paramSyms.map(_.toNamedInfo), resultType, funDef.preParamCount)
      if tparamSyms.isEmpty then
        procType
      else
        val tparamRefs = tparamSyms.zipWithIndex.map: (tparamSym, i) =>
          TypeParamRef(tparamSym.name, i)
        val substs = tparamSyms.zip(tparamRefs).toMap
        val tparamInfos = tparamSyms.map(tparam => NamedInfo(tparam.name, tparam.info.as[TypeBound]))
        val rawType = PolyType(tparamInfos, procType)
        TypeOps.substSymbols(rawType, substs)

    this.nonCyclicTypeProvider.addProvider(sym, () => computeInfo(resultType), () => computeInfo(ErrorType))

    val typer = () =>
      FunDef
        (sym, tparamSyms, paramSyms, typedBody)
        (locals = Nil, captures = Nil, funDef.span)

    DelayedDef(sym, typer)

  private def transform(tdef: Ast.TypeDef)(using sc: Scope, rp: Reporter, so: Source): DelayedDef[TypeDef] =
    val sym = Symbol.createTypeSymbol(tdef.name, this.nonCyclicTypeProvider, tdef.ident.pos)

    val sc2 = sc.fresh(sym)
    val tparamSyms =
      for tparam <- tdef.tparams yield
        lazy val bound =
          if tparam.bound.isEmpty then
            TypeBound(BottomType, AnyType)
          else
            val boundTree = transformType(tparam.bound)(using sc2)
            TypeBound(BottomType, boundTree.tpe)

        val infoProvider: InfoProvider = (sym: Symbol) => bound
        val sym = Symbol.createTypeParamSymbol(tparam.name, infoProvider, tparam.pos)
        sc2.define(sym)
        sym

    def computeInfo(): Type =
      if tdef.tparams.isEmpty then
        val rhs = transformType(tdef.rhs)
        checker.delayedCheck { checker.checkValueType(rhs) }
        rhs.tpe
      else
        val tparamRefs = tparamSyms.zipWithIndex.map: (tparamSym, i) =>
          TypeParamRef(tparamSym.name, i)
        val subst = tparamSyms.zip(tparamRefs).toMap

        val rhs = transformType(tdef.rhs)(using sc2)
        checker.delayedCheck { checker.checkValueType(rhs) }
        val tparamInfos = tparamSyms.map(tparam => NamedInfo(tparam.name, tparam.info.as[TypeBound]))
        val rawType = TypeLambda(tparamInfos, rhs.tpe)
        TypeOps.substSymbols(rawType, subst)
    end computeInfo

    this.nonCyclicTypeProvider.addProvider(sym, computeInfo)

    // check type symbols after completion to allow cycles, type A = A
    val typer = () => TypeDef(sym)(tdef.span)

    DelayedDef(sym, typer)

  /** Type check type tree
    *
    * Checks must be delayed by using `checker.delayedCheck`.
    */
  private def transformType(tpt: Ast.TypeTree)(using sc: Scope, rp: Reporter, so: Source): TypeTree =
    tpt match
      case Ast.Ident(name) =>
        sc.resolve(name, isType = true) match
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
          case TypeRef(sym) if sym.isNamespace =>
            val nsInfo = sym.info.as[NamespaceInfo]
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
            val tpt = transformType(field.typ)
            checker.delayedCheck { checker.checkValueType(tpt) }
            fieldTypes += NamedInfo(field.name, tpt.tpe)
        end for
        TypeTree(RecordType(fieldTypes.toList))(tpt.span)

      case Ast.UnionType(branches) =>
        val branchTypes = new mutable.ArrayBuffer[NamedInfo[List[NamedInfo[Type]]]]
        for branch <- branches do
          if branchTypes.exists(_.name == branch.name) then
            Reporter.error("Branch " + branch.name + " already defined", branch.pos)
          else
            val paramInfos = new mutable.ArrayBuffer[NamedInfo[Type]]
            for param <- branch.params yield
              if paramInfos.exists(_.name == param.name) then
                Reporter.error("Parameter " + param.name + " already defined", param.pos)

              val tpt = transformType(param.typ)
              checker.delayedCheck { checker.checkValueType(tpt) }
              paramInfos += NamedInfo(param.name, tpt.tpe)

            branchTypes += NamedInfo(branch.name, paramInfos.toList)
        end for
        TypeTree(UnionType(branchTypes.toList))(tpt.span)

      case Ast.AppliedType(tctor, targs) =>
        val tctor2 = transformType(tctor)
        val targs2 = for targ <- targs yield transformType(targ)
        checker.delayedCheck { checker.checkBounds(tctor2, targs2) }
        TypeTree(AppliedType(tctor2.tpe, targs2.map(_.tpe)))(tpt.span)

      case Ast.FunctionType(paramTypes, resType) =>
        val paramTypes2 =
          for paramType <- paramTypes yield
            val tpt = transformType(paramType)
            checker.delayedCheck { checker.checkValueType(tpt) }
            tpt.tpe

        val resType2 = transformType(resType)
        checker.delayedCheck { checker.checkValueType(resType2) }

        TypeTree(FunctionType(paramTypes2, resType2.tpe))(tpt.span)

      case _: Ast.EmptyTypeTree =>
        Reporter.abort("Unexpected empty type tree", tpt.pos)

object Namer:
  val errorSymbol = Symbol.createFunSymbol("error", ErrorType, pos = null)

  def transform(using reporter: Reporter): List[Ast.Namespace] => List[Namespace] =
    new Namer(reporter).transform

  private class DelayedDef[+T <: Def](val symbol: Symbol, delayed: () => T):
    private lazy val definition: T = delayed()
    def force(): T =
      symbol.info // force symbol
      definition

  enum Scope:
    case RootScope()
    case NestedScope(outer: Scope)(val allOwners: List[Symbol])

    private val termNames: mutable.Map[String, Symbol] = mutable.Map.empty
    private val typeNames: mutable.Map[String, Symbol] = mutable.Map.empty

    /** All owners of the current scope
      *
      * A owner can be either a function or a value definition.
      */
    private def owners: List[Symbol] =
      this match
        case ns: NestedScope => ns.allOwners
        case _ => Nil

    def isLocalScope = owners.nonEmpty

    /** Find the owning function of a term symbol */
    def owningFunctionOf(sym: Symbol): Option[Symbol] =
      if termNames.get(sym.name) == Some(sym) then this.owningFunction
      else
        this match
          case NestedScope(outer) => outer.owningFunctionOf(sym)
          case _ => None

    def owningFunction: Option[Symbol] =
      owners.find(owner => owner.isFunction)

    def fresh(): Scope =
      new Scope.NestedScope(this)(owners)

    def fresh(owner: Symbol): Scope =
      new Scope.NestedScope(this)(owner :: owners)

    private def getTable(isType: Boolean) =
      if isType then typeNames else termNames

    def resolve(name: String, isType: Boolean): Option[Symbol] =
      val table = getTable(isType)
      table.get(name) match
        case None =>
          this match
            case NestedScope(outer) => outer.resolve(name, isType)
            case _ => None

        case res  => res

    def resolve(name: String, span: Span, isType: Boolean = false)(using Reporter, Source): Symbol =
      resolve(name, isType) match
        case Some(sym) => sym
        case None =>
          Reporter.error("Undefined identifier " + name, span.toPos)
          errorSymbol

    def define(sym: Symbol)(using Reporter): Unit =
      val table = getTable(sym.isType)
      table.get(sym.name) match
        case None =>
          table(sym.name) = sym

        case Some(sym) =>
          Reporter.error(sym.name + " is already bound", sym.sourcePos)
