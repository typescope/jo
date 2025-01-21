package typing

import ast.Ast
import ast.Positions.*

import sast.*
import sast.Flags.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import common.Debug

import parsing.Parser
import reporting.Reporter

import Namer.{ Scope, DelayedDef }
import Inference.*

import scala.collection.mutable
import scala.annotation.constructorOnly

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

  def transform(nss: List[Ast.Namespace], rootNameTable: NameTable, predef: NameTable)(using rp: Reporter): List[Namespace] =
    // All namespace are located in the scope
    val rootNamespaceScope = new Scope.RootScope(rootNameTable, owner = null)

    // Predef names are by-default accessible. However, other namespaces are not
    // accessible unless explicitly imported.
    val predefScope: Scope = new Scope.RootScope(predef, owner = null)

    val delayedImports = new mutable.ArrayBuffer[() => Unit]
    val delayedNamespaces = new mutable.ArrayBuffer[() => Namespace]

    for ns <- nss do
      given source: Source = Reporter.source(ns.source)

      val nsSym = resolveNamespace(ns.qualid, isBranch = false)(using rootNamespaceScope)
      val nsInfo = nsSym.info.as[NameTableInfo]

      val importScope: Scope = predefScope.fresh(nsSym)
      val defsScope: Scope = importScope.fresh(nsSym, nsInfo.nameTable)

      val delayedDefs =
        given TargetType = TargetType.NamespaceMember
        index(ns.defs)(using defsScope)

      val imports = new mutable.ArrayBuffer[Symbol]

      delayedImports += { () =>
        // Make current namespace name available
        importScope.define(nsSym)
        // handle imports after indexing members
        for imp <- ns.imports do
          // TODO: what about type names? Import both
          given Scope = rootNamespaceScope
          val sym = resolveGlobal(imp.qualid, isType = false)

          if sym.isAllOf(Flags.NSpace | Flags.Branch) then
            rp.error("Only a concrete namespace can be imported", imp.pos)

          imports += sym
          // TODO: abstract scope and better error position for duplicate imports
          importScope.define(sym)
      }

      delayedNamespaces += { () =>
        val defs = for delayed <- delayedDefs.toList yield delayed.force()
        Namespace(nsSym, imports.toList, defs)(ns.span)
      }
    end for

    delayedImports.foreach(_.apply())
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
      val pos = sym.sourcePos
      if sym.isNamespace then
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
        Symbol.createNamespaceSymbol(sym.name, new NameTableInfo, sym.owner, qualid.pos, isBranch)

    qualid match
      case Ast.Select(qual, name) =>
        assert(qual.isInstanceOf[Ast.RefTree], "Unexpected qualid = " + qualid)
        val nsSym = resolveNamespace(qual.asInstanceOf[Ast.RefTree], isBranch = true)

        assert(nsSym.isNamespace, "Not a namespace " + nsSym)
        val nsInfo = nsSym.info.as[NameTableInfo]

        nsInfo.resolveTerm(name) match
          case Some(sym) => check(sym)

          case None =>
            val sym = Symbol.createNamespaceSymbol(name, new NameTableInfo, nsSym, qualid.pos, isBranch)
            nsInfo.define(sym)
            sym

      case Ast.Ident(name) =>
        sc.resolve(name, isType = false) match
          case None =>
            val sym = Symbol.createNamespaceSymbol(name, new NameTableInfo, sc.owner, qualid.pos, isBranch)
            sc.define(sym)
            sym

          case Some(sym) => check(sym)


  /** Resolve a global */
  def resolveGlobal(qualid: Ast.RefTree, isType: Boolean)(using sc: Scope, rp: Reporter, so: Source): Symbol =
    qualid match
      case Ast.Select(qual, name) =>
        val sym = resolveGlobal(qual.asInstanceOf[Ast.RefTree], isType = false)

        if sym.isNamespace then
          val nsInfo = sym.info.as[NameTableInfo]

          nsInfo.resolveTerm(name) match
            case Some(sym) => sym

            case None =>
              rp.error(s"Member named $name not found in the namespace ${sym.name}", qualid.pos)
              Symbol.createFunSymbol(name, ErrorType, sym, pos = qualid.pos)

        else
          if !sym.info.isError then
            rp.error("Not a namespace, only a namespace can be selected", qual.pos)
          Symbol.createFunSymbol(name, ErrorType, sym, pos = qualid.pos)

      case Ast.Ident(name) =>
        sc.resolve(name, isType) match
          case Some(sym) => sym
          case None =>
            rp.error(s"The name $name is not found", qualid.pos)
            Symbol.createNamespaceSymbol(name, new NameTableInfo, sc.owner, pos = qualid.pos, isBranch = false)

  def resolvePath(path: String, isType: Boolean)(using sc: Scope): Symbol =
    resolvePath(path.split("\\.").toList, isType) match
      case Some(sym) =>
        sym

      case None =>
        throw new Exception("Not found required symbol: " + path)

  def resolvePath(parts: List[String], isType: Boolean)(using sc: Scope): Option[Symbol] =
    (parts: @unchecked) match
      case name :: Nil => sc.resolve(name, isType)

      case name :: rest =>
        sc.resolve(name, isType = false).flatMap: sym =>
          if sym.isNamespace then
            val nameTable = sym.info.as[NameTableInfo].nameTable
            NameTable.resolvePath(nameTable, rest, isType)
          else
            None

  private def index(defs: List[Ast.Def])(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): List[DelayedDef[Def]] =
    val delayedDefs = new mutable.ArrayBuffer[DelayedDef[Def]]

    for defn <- defs do
      val delayedDef = index(defn)
      // The name table is shared between NameTableInfo and current scope. This
      // way, by entering once the name can be access in two different ways in
      // the current context.
      sc.define(delayedDef.symbol)
      delayedDefs += delayedDef

    delayedDefs.toList

  private def index(defn: Ast.Def)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): DelayedDef[Def] =
    defn match
      case vdef: Ast.ValDef =>
        transform(vdef)

      case fdef: Ast.FunDef =>
        transformFunDef(fdef)

      case tdef: Ast.TypeDef =>
        transform(tdef)

      case pdef: Ast.Param =>
        val infoProvider: InfoProvider = sym => transformType(pdef.typ).tpe
        val sym = Symbol.createValueSymbol(pdef.name, infoProvider, Flags.Param | Flags.Context, sc.owner, pdef.pos)
        val defSast = () =>
          val tpt = TypeTree(sym.info)(pdef.typ.span)
          ParamDef(sym, tpt)(pdef.span)
        DelayedDef(sym, defSast)
    end match
  end index

  def transform(word: Ast.Word)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    extension (word: Word) def adapt: Word = checker.adapt(word, tt)

    word match
      case Ast.IntLit(v)  =>
        Literal(Constant.Int(v.toInt))(PrimType.Int, word.span).adapt

      case Ast.CharLit(v) =>
        Literal(Constant.Int(v.toInt))(PrimType.Char, word.span).adapt

      case Ast.BoolLit(v) =>
        Literal(Constant.Bool(v))(PrimType.Bool, word.span).adapt

      case Ast.StringLit(v) =>
        // TODO: use full name `stk.Predef.String`
        val stringSym = resolvePath("String", isType = true)
        Literal(Constant.String(v))(TypeRef(stringSym), word.span).adapt

      case Ast.Ident(name) =>
        val sym = sc.resolve(name, word.pos)
        if sym.isField || sym.isMethod then
          val qual = Ident(sym.owner)(word.span)
          Select(qual, sym.name)(sym.info, word.span).adapt
        else
          checker.checkCapture(sym, word.pos)
          Ident(sym)(word.span).adapt

      case record: Ast.RecordLit =>
        transform(record).adapt

      case variant: Ast.Variant =>
        transform(variant).adapt

      case Ast.Select(qual, name) =>
        val qual2 =
          given TargetType = TargetType.TermMember(name)
          transform(qual)

        qual2.tpe.getTermMember(name) match
          case Some(tp) =>
            tp match
              case TypeRef(sym) if !sym.isField && !sym.isMethod && !sym.isType =>
                Ident(sym)(word.span).adapt

              case _ =>
                Select(qual2, name)(tp, word.span).adapt

          case None =>
            // Error already reported
            // Reporter.error(s"The prefix does not contain the member $name", qual.pos)
            Block(Nil)(ErrorType, word.span)

      case lambda: Ast.Lambda =>
        transform(lambda).adapt

      case Ast.Fence(phrase) =>
        transform(phrase)

      case Ast.TypeApply(fun, targs) =>
        val fun2 = transform(fun)
        val targs2 = targs.map(transformType)
        checker.checkTypeApply(fun2, targs2).adapt

      case expr: Ast.Expr  =>
        exprTyper.transform(expr)

      case Ast.With(expr, args, only) =>
        val exprSast = transform(expr)
        val argsSast = for arg <- args yield transform(arg)
        With(exprSast, argsSast, only)(exprSast.tpe, word.span)

      case Ast.DefaultParam(paramRef, default) =>
        val paramRefTyped = transformParamRef(paramRef)
        val defaultTyped =
          given TargetType =
            if paramRefTyped.tpe.isError then TargetType.ValueType
            else TargetType.Known(paramRefTyped.symbol.info)
          transform(default)

        DefaultParam(paramRefTyped, defaultTyped)(paramRefTyped.tpe, word.span).adapt

      case ifte: Ast.If =>
        transform(ifte)

      case Ast.While(cond, body) =>
         val cond2 = transform(cond)(using sc, rp, so, TargetType.Known(PrimType.Bool))
         val body2 = transform(body)(using sc, rp, so, TargetType.Known(VoidType))
         While(cond2, body2)(word.span).adapt

      case assign: Ast.Assign =>
        transform(assign).adapt

      case patmat: Ast.Match =>
        transform(patmat)

      case vdef: Ast.ValDef =>
        val delayedDef = transform(vdef)
        val vdef2 = delayedDef.force()
        // a val is not available for checking its rhs
        sc.define(delayedDef.symbol)
        vdef2.adapt

      case fdef: Ast.FunDef =>
        val delayedDef = transformFunDef(fdef)
        // A function is available for checking its rhs
        sc.define(delayedDef.symbol)
        delayedDef.force().adapt

      case tdef: Ast.TypeDef =>
        val delayedDef = transform(tdef)
        // A type definition is available for checking its rhs
        sc.define(delayedDef.symbol)
        delayedDef.force().adapt

      case block: Ast.Block =>
        transform(block)

      case obj: Ast.Object =>
        transform(obj).adapt

  def transform(obj: Ast.Object)(using sc: Scope, rp: Reporter, so: Source): Word =
    val nameTable = new NameTable
    val vals = new mutable.ArrayBuffer[ValDef]
    val delayedDefs = new mutable.ArrayBuffer[DelayedDef[FunDef]]

    val infoProvider: InfoProvider = sym =>
      val fieldTypes = vals.map(vdef => NamedInfo(vdef.name, vdef.symbol.info)).toList
      val methodTypes = delayedDefs.map(d => NamedInfo(d.symbol.name, TypeRef(d.symbol))).toList
      val mutables = vals.filter(_.isMutable).map(_.name).toList
      ObjectType(fieldTypes, methodTypes, mutables)

    val thisSym = Symbol.createValueSymbol("this", infoProvider, sc.owner, obj.pos)

    // scope for checking member methods
    val sc2 = sc.fresh(thisSym, nameTable)


    for case vdef: Ast.ValDef <- obj.members do
      given Scope = sc2
      given TargetType = TargetType.ObjectMember
      val vdefTyped = transform(vdef).force()
      sc2.define(vdefTyped.symbol)
      vals += vdefTyped

    // `this` should not be available in field initialization
    sc2.define(thisSym)

    for case fdef: Ast.FunDef <- obj.members do
      given Scope = sc2
      given TargetType = TargetType.ObjectMember
      val delayedDef = transformFunDef(fdef)
      sc2.define(delayedDef.symbol)
      delayedDefs += delayedDef

    val defs: List[FunDef] =
      for delayedDef <- delayedDefs.toList yield delayedDef.force()

    // external object type
    val fieldTypes = vals.map(vdef => NamedInfo(vdef.name, vdef.symbol.info)).toList
    val methodTypes = delayedDefs.map(d => NamedInfo(d.symbol.name, TypeRef(d.symbol))).toList
    val mutables = vals.filter(_.isMutable).map(_.name).toList
    val objType = ObjectType(fieldTypes, methodTypes, mutables)

    Object(thisSym, vals.toList, defs)(objType, obj.span)

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

    if words.isEmpty then Block(Nil)(VoidType, block.span)
    else Block(words)(words.last.tpe, block.span)

  def transform(assign: Ast.Assign)(using sc: Scope, rp: Reporter, so: Source): Word =
    val Ast.Assign(ref, rhs) = assign

    ref match
      case id: Ast.Ident =>
        val sym = sc.resolve(id.name, id.pos)

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
              Block(Nil)(ErrorType, assign.span)

        else
          Reporter.error("Expect an object, found = " + qual2.tpe.show, qual.pos)
          Block(Nil)(ErrorType, assign.span)

  private def transformParamRef(ref: Ast.RefTree)(using sc: Scope, rp: Reporter, so: Source): Ident =
    val paramRef =
      given TargetType = TargetType.Unknown
      transform(ref)

    val paramSym =
      paramRef.tpe match
        case TypeRef(sym) if sym.isAllOf(Flags.Param | Flags.Context) =>
          sym

        case tp =>
          Reporter.error("A reference to a contextual parameter expected, found = " + tp.show, paramRef.pos)
          Symbol.createFunSymbol(ref.name, ErrorType, sc.owner, paramRef.pos)

    Ident(paramSym)(ref.span)

  private def transform(arg: Ast.WithArg)(using sc: Scope, rp: Reporter, so: Source): WithArg =
    val paramRef = transformParamRef(arg.paramRef)

    val rhsSast =
      given TargetType =
        if paramRef.tpe.isError then TargetType.ValueType
        else TargetType.Known(paramRef.symbol.info)
      transform(arg.rhs)

    WithArg(paramRef, rhsSast)(arg.span)

  private def transform(ifte: Ast.If)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val Ast.If(cond, thenp, elsep) = ifte
    val cond2 = transform(cond)(using sc, rp, so, TargetType.Known(PrimType.Bool))
    val then2 = transform(thenp)
    val else2 = transform(elsep)

    // adapt result type
    val commonType = checker.commonResultType(then2.tpe, else2.tpe, ifte.pos)
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
        Block(Nil)(ErrorType, variant.span)


  private def transform(patmat: Ast.Match)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
    val sc2 = sc.fresh()

    val Ast.Match(scrutinee, cases) = patmat
    val scrutinee2 = transform(scrutinee)(using sc, rp, so, TargetType.ValueType)

    val scrutType = scrutinee2.tpe
    val scrutSym = Symbol.createValueSymbol("scrutinee", scrutType, sc.owner, scrutinee2.pos)
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
          // TODO: use full name `stk.Predef.abort`
          val abortSym = resolvePath("abort", isType = false)
          val stringSym = resolvePath("String", isType = true)
          val abort = Ident(abortSym)(scrutIdent.span)
          val arg = Literal(Constant.String("Unhandled match at " + scrutIdent.pos))(TypeRef(stringSym), scrutIdent.span)
          val app = Apply(abort, arg :: Nil)(BottomType, patmat.span)
          checker.adapt(app, resType)

      end match

    val body = transformCases(cases, BottomType, allTags)
    Block(bind :: body :: Nil)(body.tpe, patmat.span)

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
        val commonType = checker.commonResultType(body2.tpe, resType, body2.pos)
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
            val sym = Symbol.createValueSymbol(binding.name, arg.tpe, sc.owner, arg.pos)
            vals += ValDef(sym, arg)(binding.span)
            caseScope.define(sym)

          val tagIndex =
            if tagTypesOpt.isEmpty then -1
            else scrutType.asUnionType.tagIndex(tag.name)

          val cond = Desugaring.testVariantTag(encodedScrut, tagIndex, tag.span)
          val body2 = transform(body)(using caseScope, rp, so, tt)
          val commonType = checker.commonResultType(body2.tpe, resType, body2.pos)
          val elsep = cont(commonType)
          val commonType2 = checker.commonResultType(body2.tpe, elsep.tpe, body2.pos)
          val adapted = checker.adapt(body2, commonType2)

          val body3 = Block(vals.toList :+ adapted)(adapted.tpe, caseDef.span)
          If(cond, body3, elsep)(body3.tpe, caseDef.span)

  private def transform(lambda: Ast.Lambda)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): Word =
     val Ast.Lambda(params, body) = lambda

     val targetFunTypeOpt: Option[ProcType] = tt.knownType.flatMap(_.getFunctionApplyType)

     if targetFunTypeOpt.nonEmpty then
       val expect = targetFunTypeOpt.get.paramCount
       if expect != params.size then
         Reporter.error(s"Expect a function with $expect parameters, found = ${params.size}", lambda.pos)
         return Block(words = Nil)(ErrorType, lambda.span)

     // Each object has a self symbol
     val thisSym = Symbol.createValueSymbol("this", this.nonCyclicTypeProvider, sc.owner, lambda.pos)

     val funSym = Symbol.createSymbol("apply", this.nonCyclicTypeProvider, Flags.Method, thisSym, lambda.pos)
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
        val paramSym = Symbol.createParamSymbol(param.name, tp, funSym, param.pos)
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
       checker.checkInstantiated(tvar, param.pos)

     val tparamSyms = Nil
     val funDef = FunDef(funSym, tparamSyms, paramSyms, bodyTyped)(lambda.span)
     val objType = ObjectType(fields = Nil, methods = NamedInfo("apply", procType) :: Nil, mutableFields = Nil)

     this.nonCyclicTypeProvider.addProvider(thisSym, () => objType)

     Object(thisSym, vals = Nil, defs = funDef :: Nil)(objType, lambda.span)

  private def transform(vdef: Ast.ValDef)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): DelayedDef[ValDef] =
    var flags: Flags = if tt == TargetType.ObjectMember then Flags.Field else Flags.empty

    if vdef.mutable then
      flags = flags | Flags.Mutable

    val sym = Symbol.createValueSymbol(vdef.name, this.nonCyclicTypeProvider, flags, sc.owner, vdef.ident.pos)

    lazy val givenType: Type =
      val tpt = transformType(vdef.typ)
      val tp2 = checker.checkValueType(tpt.tpe, tpt.pos)
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

  private def transformFunDef(funDef: Ast.FunDef)(using sc: Scope, rp: Reporter, so: Source, tt: TargetType): DelayedDef[FunDef] =
    val flags = if tt == TargetType.ObjectMember then Flags.Method else Flags.Fun

    val funSym = Symbol.createSymbol(funDef.name, this.nonCyclicTypeProvider, flags, sc.owner, funDef.ident.pos)
    val funScope = sc.fresh(funSym)

    lazy val tparamSyms =
      for tparam <- funDef.tparams yield
        lazy val bound =
          if tparam.bound.isEmpty then
            TypeBound(BottomType, AnyType)
          else
            val boundTree = transformType(tparam.bound)(using funScope)
            TypeBound(BottomType, boundTree.tpe)

        val infoProvider: InfoProvider = (sym: Symbol) => bound
        val sym = Symbol.createTypeParamSymbol(tparam.name, infoProvider, funSym, tparam.pos)
        funScope.define(sym)
        sym

    lazy val paramSyms =
      tparamSyms

      for param <- funDef.params yield
        val tpt = transformType(param.typ)(using funScope)
        val paramSym = Symbol.createParamSymbol(param.name, tpt.tpe, funSym, param.pos)
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

    this.nonCyclicTypeProvider.addProvider(funSym, () => computeInfo(resultType), () => computeInfo(ErrorType))

    val typer = () =>
      FunDef(funSym, tparamSyms, paramSyms, typedBody) (funDef.span)

    DelayedDef(funSym, typer)

  private def transform(tdef: Ast.TypeDef)(using sc: Scope, rp: Reporter, so: Source): DelayedDef[TypeDef] =
    val typeSym = Symbol.createTypeSymbol(tdef.name, this.nonCyclicTypeProvider, sc.owner, tdef.ident.pos)

    val sc2 = sc.fresh(typeSym)
    val tparamSyms =
      for tparam <- tdef.tparams yield
        lazy val bound =
          if tparam.bound.isEmpty then
            TypeBound(BottomType, AnyType)
          else
            val boundTree = transformType(tparam.bound)(using sc2)
            TypeBound(BottomType, boundTree.tpe)

        val infoProvider: InfoProvider = (sym: Symbol) => bound
        val sym = Symbol.createTypeParamSymbol(tparam.name, infoProvider, typeSym, tparam.pos)
        sc2.define(sym)
        sym

    def computeInfo(): Type =
      if tdef.tparams.isEmpty then
        if tdef.rhs.isEmpty then
          if sc.owner.fullName == "stk.Predef" then
            val typeName = tdef.name
            if typeName == "Int" then PrimType.Int
            else if typeName == "Byte" then PrimType.Byte
            else if typeName == "Char" then PrimType.Char
            else if typeName == "Bool" then PrimType.Bool
            else if typeName == "void" then VoidType
            else if typeName == "Any" then AnyType
            else if typeName == "Bottom" then BottomType
            else
              Reporter.error("Unknown primitive type " + typeName, tdef.pos)
              AnyType
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
        val tparamRefs = tparamSyms.zipWithIndex.map: (tparamSym, i) =>
          TypeParamRef(tparamSym.name, i)
        val subst = tparamSyms.zip(tparamRefs).toMap

        val rhs = transformType(tdef.rhs)(using sc2)
        checker.delayedCheck { checker.checkValueType(rhs) }
        val tparamInfos = tparamSyms.map(tparam => NamedInfo(tparam.name, tparam.info.as[TypeBound]))

        val rhsType =
          if tdef.isBound then TypeBound(BottomType, rhs.tpe)
          else rhs.tpe

        val rawType = TypeLambda(tparamInfos, rhsType)
        TypeOps.substSymbols(rawType, subst)
    end computeInfo

    this.nonCyclicTypeProvider.addProvider(typeSym, computeInfo)

    // check type symbols after completion to allow cycles, type A = A
    val typer = () => TypeDef(typeSym)(tdef.span)

    DelayedDef(typeSym, typer)

  private def transformMethodDecl(ddef: Ast.FunDef)(using sc: Scope, rp: Reporter, so: Source): TypeTree =
    val defScope = sc.fresh()

    val tparamSyms =
      for tparam <- ddef.tparams yield
        val bound =
          if tparam.bound.isEmpty then
            TypeBound(BottomType, AnyType)
          else
            val boundTree = transformType(tparam.bound)(using defScope)
            TypeBound(BottomType, boundTree.tpe)

        val infoProvider: InfoProvider = (sym: Symbol) => bound
        val sym = Symbol.createTypeParamSymbol(tparam.name, infoProvider, sc.owner, tparam.pos)
        defScope.define(sym)
        sym

    val paramSyms =
      for param <- ddef.params yield
        val tpt = transformType(param.typ)(using defScope)
        val paramSym = Symbol.createParamSymbol(param.name, tpt.tpe, sc.owner, param.pos)
        defScope.define(paramSym)
        paramSym

    val resultType =
      assert(!ddef.resType.isEmpty)
      val resTypeTree = transformType(ddef.resType)(using defScope)
      checker.delayedCheck { checker.checkVoidOrValueType(resTypeTree) }
      resTypeTree.tpe

    val methodType = ProcType(paramSyms.map(_.toNamedInfo), resultType, preParamCount = 0)
    val finalType =
      if tparamSyms.isEmpty then
        methodType
      else
        val tparamRefs = tparamSyms.zipWithIndex.map: (tparamSym, i) =>
          TypeParamRef(tparamSym.name, i)
        val substs = tparamSyms.zip(tparamRefs).toMap
        val tparamInfos = tparamSyms.map(tparam => NamedInfo(tparam.name, tparam.info.as[TypeBound]))
        val rawType = PolyType(tparamInfos, methodType)
        TypeOps.substSymbols(rawType, substs)

    TypeTree(finalType)(ddef.span)

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
            val nsInfo = sym.info.as[NameTableInfo]
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

      case Ast.ObjectType(members) =>
        val fieldTypes = new mutable.ArrayBuffer[NamedInfo[Type]]
        val methodTypes = new mutable.ArrayBuffer[NamedInfo[Type]]
        for member <- members do
          if fieldTypes.exists(_.name == member.name) || methodTypes.exists(_.name == member.name) then
            Reporter.error("Member " + member.name + " already defined", member.pos)
          else
            val memberTypeTree = transformMethodDecl(member)
            methodTypes += NamedInfo(member.name, memberTypeTree.tpe)
        end for
        val tp = ObjectType(fields = fieldTypes.toList, methods = methodTypes.toList, mutableFields = Nil)
        TypeTree(tp)(tpt.span)

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
        var i = 0
        val paramTypes2 =
          for paramType <- paramTypes yield
            val tpt = transformType(paramType)
            checker.delayedCheck { checker.checkValueType(tpt) }
            val namedInfo = NamedInfo("param" + i, tpt.tpe)
            i = i+1
            namedInfo

        val resType2 = transformType(resType)
        checker.delayedCheck { checker.checkValueType(resType2) }
        val applyType = ProcType(paramTypes2, resType2.tpe, preParamCount = 0)
        val objType = ObjectType(fields = Nil, methods = NamedInfo("apply", applyType) :: Nil, mutableFields = Nil)
        TypeTree(objType)(tpt.span)

      case _: Ast.EmptyTypeTree =>
        Reporter.abort("Unexpected empty type tree", tpt.pos)

object Namer:
  def main(args: Array[String]): Unit =
    Reporter.monitor:
      val stdLib = "lib/Predef.stk" :: Nil
      val runtimeFiles = Nil
      val namer = (nssAst: List[Ast.Namespace]) => transform(nssAst, stdLib, runtimeFiles)
      val nss = Parser.parse(args.toList) |> namer |> TreeChecker.check

      for ns <- nss do
        println(ns.symbol.sourcePos.source.file + ":")
        println(ns.show)
        println

  def transform(nssAst: List[Ast.Namespace], stdlib: List[String], runtime: List[String])(using Reporter) : List[Namespace] =
    val rootNameTable = new NameTable
    val runtimeNameTable = new NameTable
    transform(nssAst, stdlib, runtime, rootNameTable, runtimeNameTable)

  /** The stdlib cannot depend on pre-defined symbols */
  def transform(
    nssAst: List[Ast.Namespace],
    stdlib: List[String],
    runtime: List[String],
    rootNameTable: NameTable,
    runtimeNameTable: NameTable)(using rp: Reporter)
  : List[Namespace] =

    // StdLib is compiled without the Predef
    val nssStdLib = transform(stdlib, rootNameTable, predef = new NameTable)
    Definitions.initialize(rootNameTable)

    val predefNameTable = Definitions.instance.Predef_nameTable

    // Runtime definitions are not entered into the root name table thus is
    // inaccessible in user programs
    val nssRuntime = transform(runtime, runtimeNameTable, predefNameTable)

    val nss = new Namer(rp).transform(nssAst, rootNameTable, predefNameTable)
    nssStdLib ++ nssRuntime ++ nss

  def transform(files: List[String], rootNameTable: NameTable, predef: NameTable)(using rp: Reporter): List[Namespace] =
    val namer = (nss: List[Ast.Namespace]) =>
      new Namer(rp).transform(nss, rootNameTable, predef)
    // `|>` will stop early in the presence of parsing errors
    Parser.parse(files) |> namer

  private class DelayedDef[+T <: Def](val symbol: Symbol, delayed: () => T):
    private lazy val definition: T = delayed()
    def force(): T =
      symbol.info // force symbol
      definition

  enum Scope:
    case RootScope(table: NameTable, owner: Symbol)
    case NestedScope(outer: Scope, table: NameTable, owner: Symbol)

    protected val table: NameTable

    /** The owner symbol of the current scope
      *
      * It can be null for top-level scopes
      */
    val owner: Symbol

    def fresh(): Scope =
      new Scope.NestedScope(this, new NameTable, owner)

    def fresh(owner: Symbol): Scope =
      new Scope.NestedScope(this, new NameTable, owner)

    def fresh(owner: Symbol, nameTable: NameTable): Scope =
      new Scope.NestedScope(this, nameTable, owner)

    def resolve(name: String, isType: Boolean): Option[Symbol] = Debug.trace(s"Resolving $name in scope " + table.show, enable = false):
      table.resolve(name, isType) match
        case None =>
          this match
            case nsc: NestedScope => nsc.outer.resolve(name, isType)
            case _ => None

        case res  => res

    def resolve(name: String, pos: SourcePosition, isType: Boolean = false)(using Reporter): Symbol =
      resolve(name, isType) match
        case Some(sym) => sym
        case None =>
          val kind = if isType then "type" else "term"
          Reporter.error(s"Undefined $kind identifier " + name, pos)
          Symbol.createFunSymbol(name, ErrorType, owner, pos)

    def define(sym: Symbol)(using Reporter): Unit =
      table.define(sym)
