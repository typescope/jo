import scala.collection.mutable
import scala.annotation.constructorOnly

import Sast.*
import Types.*
import Symbols.*
import Reporter.Span
import Namer.{ Scope, LazyValue, DelayedDef, errorSymbol }

/**
  * The namer handles name resolution and desugaring.
  *
  * It converts ASTs to Semantic ASTs.
  */
class Namer(@constructorOnly reporter: Reporter):
  val checker = new Checker(reporter)

  def transform(prog: Ast.Prog)(using Reporter): Sast.Prog =
    val rootScope = new Scope.RootScope()

    // Predefined type names
    rootScope.define(predef.Int, Reporter.NoSpan)
    rootScope.define(predef.Bool, Reporter.NoSpan)
    rootScope.define(predef.Void, Reporter.NoSpan)

    // Predefined term names
    for sym <- predef.allSymbols do
      rootScope.define(sym, Reporter.NoSpan)

    // Prepare scope according to scoping rules
    val sc = rootScope.fresh()
    val defs = transform(prog.defs)(using sc)

    // Create dummy main symbol to make it easier to tell whether a definition is local or not
    val dummyMainSym = Symbol.createFunSymbol("$main", ProcType(Nil, Nil, VoidType), prog.main.pos)
    val main2 = transform(prog.main)(using sc.fresh(dummyMainSym))

    checker.performDelayedChecks()

    Prog(defs, main2)

  def transform(defs: List[Ast.Def])(using sc: Scope, rp: Reporter): List[Def] =
    val delayedDefs = new mutable.ArrayBuffer[DelayedDef[Def]]

    for defn <- defs do
      val delayedDef = index(defn)
      sc.define(delayedDef.symbol, defn.span)
      delayedDefs += delayedDef

    for delayedDef <- delayedDefs.toList yield
      delayedDef.force()

  private def index(defn: Ast.Def)(using sc: Scope, rp: Reporter): DelayedDef[Def] =
    defn match
      case vdef: Ast.ValDef =>
        transform(vdef)

      case funDef: Ast.FunDef =>
        transform(funDef)

      case tdef: Ast.TypeDef =>
        transform(tdef)
    end match
  end index

  private def checkCapture(sym: Symbol, span: Span)(using sc: Scope, rp: Reporter): Unit =
    if sym.isAllOf(Flag.Val | Flag.Mutable | Flag.Local) then
      // check no capture of mutable local vars
      val ownerFunOpt = sc.owningFunctionOf(sym)
      val curFunOpt = sc.owningFunction
      if ownerFunOpt != curFunOpt then
        Reporter.error("Cannot capture local mutable variable " + sym.name, span.toPos)

  def transform(word: Ast.Word)(using sc: Scope, rp: Reporter): Word =
    word match
      case Ast.IntLit(v)  =>
        IntLit(v)(word.span)

      case Ast.BoolLit(v) =>
        BoolLit(v)(word.span)

      case phrase: Ast.Phrase  =>
        transform(phrase)

      case ifte: Ast.If =>
        transform(ifte)

      case Ast.While(cond, body) =>
         val cond2 = transform(cond)
         val body2 = transform(body)
         checker.checkType(cond2, BoolType)
         While(cond2, body2)(word.span)

      case Ast.Ident(name) =>
        val sym = sc.resolve(name, word.span)
        checkCapture(sym, word.span)
        Ident(sym)(word.span)

      case Ast.Assign(id, words) =>
        val sym = sc.resolve(id.name, id.span)

        checker.checkMutable(sym, id.span)
        checkCapture(sym, id.span)

        val rhs = transform(words)
        checker.checkType(rhs, sym.info)
        Assign(sym, rhs)(word.span)

      case record: Ast.RecordLit =>
        transform(record)

      case variant: Ast.Variant =>
        transform(variant)

      case patmat: Ast.Match =>
        transform(patmat)

      case Ast.Select(qual, name) =>
        val qual2 = transform(qual)
        val tp = checker.fieldType(qual2.tpe, name, qual.span)
        Select(qual2, name)(tp, word.span)

      case Ast.TypeApply(fun, targs) =>
        val fun2 = transform(fun)
        val targs2 = targs.map(transformType)
        checker.checkTypeApply(fun2, targs2)

      case Ast.Lambda(params, body) =>
        val sc2 = sc.fresh()
        val id = Ast.Ident("anon")(word.span)
        val resType = Ast.EmptyTypeTree()(body.span)
        val tparams = Nil
        val funDef = Ast.FunDef(id, tparams, params, resType, body)(word.span)
        val funDef2 = transform(funDef)(using sc2).force()
        val lambdaType = funDef2.tpe.asProcType.toFunType
        val ref = Ident(funDef2.symbol)(word.span)
        Phrase(funDef2 :: ref :: Nil)(lambdaType, word.span)

      case vdef: Ast.ValDef =>
        val delayedDef = transform(vdef)
        sc.define(delayedDef.symbol, vdef.span)
        delayedDef.force()

  private def transform(ifte: Ast.If)(using sc: Scope, rp: Reporter): Word =
    val Ast.If(cond, thenp, elsep) = ifte
    val cond2 = transform(cond)
    val then2 = transform(thenp)
    val else2 = transform(elsep)
    checker.checkType(cond2, BoolType)

    // adapt result type
    val commonType = checker.commonResultType(then2.tpe, else2.tpe, else2.span)
    val then3 = checker.adapt(then2, commonType)
    val else3 = checker.adapt(else2, commonType)
    If(cond2, then3, else3)(commonType, ifte.span)

  private def transform(record: Ast.RecordLit)(using sc: Scope, rp: Reporter): Word =
    val Ast.RecordLit(namedArgs) = record
    val namedArgs2 = new mutable.ArrayBuffer[(String, Word)]
    for Ast.NamedArg(id, rhs) <- namedArgs do
      if namedArgs2.exists(_._1 == id.name) then
        Reporter.error("Arg " + id.name + " already defined", id.pos)
      else
        val rhs2 = transform(rhs)
        checker.checkValueType(rhs2)
        namedArgs2 += id.name -> rhs2
    end for
    val fields = namedArgs2.toList
    val tpe = RecordType(fields.map { case (k, v) => k -> v.tpe })
    RecordLit(fields)(tpe, record.span)

  private def transform(variant: Ast.Variant)(using sc: Scope, rp: Reporter): Word =
    val Ast.Variant(tag, values, typ) = variant
    val values2 = values.map(transform)
    val unionType = transformType(typ)
    val tagTypesOpt = checker.tagTypes(tag, unionType.tpe, unionType.span)

    val tagTypes =
      tagTypesOpt match
        case Some(tagTypes) =>
          checker.checkTagValues(values2, tagTypes, tag.span)
          tagTypes

        case None =>
          Nil

    // encode variants as records
    val tagIndex =
      if tagTypesOpt.isEmpty then -1
      else unionType.tpe.asUnionType.tagIndex(tag.name)

    val encodedValue = Desugaring.encodeVariant(tagIndex, values2, tagTypes, tag.span, variant.span)
    Encoded(encodedValue)(unionType.tpe)

  private def transform(patmat: Ast.Match)(using sc: Scope, rp: Reporter): Word =
    val sc2 = sc.fresh()

    val Ast.Match(scrutinee, cases) = patmat
    val scrutinee2 = transform(scrutinee)
    checker.checkValueType(scrutinee2)

    val scrutType = scrutinee2.tpe
    val scrutSym = Symbol.createValueSymbol("scrutinee", scrutType, Flag.Local, scrutinee2.pos)
    val scrutIdent = Ident(scrutSym)(scrutinee.span)
    val bind = ValDef(scrutSym, scrutinee2)(scrutinee.span)
    sc2.define(scrutSym, scrutinee.span)

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
          val words =
            IntLit(1)(scrutIdent.span)
              :: Ident(runtime.abort)(scrutIdent.span)
              :: Nil
          val res = Phrase(words)(BottomType, patmat.span)
          checker.adapt(res, resType)

      end match

    val body = transformCases(cases, BottomType, allTags)
    Phrase(bind :: body :: Nil)(body.tpe, patmat.span)

  private def transform
      (scrut: Ident, caseDef: Ast.Case, resType: Type, cont: Type => Word)
      (using sc: Scope, rp: Reporter): Word =

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
        val tagTypes = tagTypesOpt match
            case Some(tps) => tps
            case None => Nil

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
            val sym = Symbol.createValueSymbol(binding.name, arg.tpe, Flag.Local, arg.pos)
            vals += ValDef(sym, arg)(binding.span)
            caseScope.define(sym, binding.span)

          val tagIndex =
            if tagTypesOpt.isEmpty then -1
            else scrutType.asUnionType.tagIndex(tag.name)

          val cond = Desugaring.testVariantTag(encodedScrut, tagIndex, tag.span)
          val body2 = transform(body)(using caseScope)
          val commonType = checker.commonResultType(body2.tpe, resType, body2.span)
          val elsep = cont(commonType)
          val adapted2 = checker.adapt(body2, elsep.tpe)

          val body3 = Phrase(vals.toList :+ adapted2)(adapted2.tpe, caseDef.span)
          If(cond, body3, elsep)(body3.tpe, caseDef.span)

  private def transform(phrase: Ast.Phrase)(using sc: Scope, rp: Reporter): Word =
    val typer = new NamerUtils.PhraseTyper(this, checker)
    typer.transform(phrase)

  private def transform(vdef: Ast.ValDef)(using sc: Scope, rp: Reporter): DelayedDef[ValDef] =
    // TODO: add Local flag
    var flags: Flags = Flag.empty
    if vdef.mutable then
      flags = flags | Flag.Mutable

    if sc.isLocalScope then
      flags = flags | Flag.Local

    val sym = Symbol.createValueSymbol(vdef.name, checker.nonCyclicTypeProvider, flags, vdef.ident.pos)

    def checkRHS(sym: Symbol): Word =
      val sc2 = sc.fresh(sym)
      transform(vdef.rhs)(using sc2)

    val rhs: LazyValue[Symbol, Word] = LazyValue(checkRHS)

    def computeType(sym: Symbol): Type =
      if vdef.typ.isEmpty then
        rhs.get(sym).tpe
      else
        val tpt = transformType(vdef.typ)
        val tp2 = checker.checkValueType(tpt.tpe, tpt.span)
        checker.checkType(rhs.get(sym), tp2)
        tp2

    checker.nonCyclicTypeProvider.addProvider(sym, computeType)

    val typer = () => ValDef(sym, rhs.get(sym))(vdef.span)
    DelayedDef(sym, typer)

  private def transform(funDef: Ast.FunDef)(using sc: Scope, rp: Reporter): DelayedDef[FunDef] =
    val paramSyms = new mutable.ArrayBuffer[Symbol]
    val tparamSyms = new mutable.ArrayBuffer[Symbol]
    val bounds = new mutable.ArrayBuffer[Type]

    var flags: Flags = Flag.empty
    if sc.isLocalScope then
      flags = flags | Flag.Local

    val sym = Symbol.createFunSymbol(funDef.name, checker.cyclicTypeProvider, flags, funDef.ident.pos)
    val funScope = sc.fresh(sym)

    val paramNames = funDef.params.map(_.name)
    val tparamNames = funDef.tparams.map(_.name)

    var bodyTyped: Word = null
    // can be called multiple types from the info completer
    def checkBody()(using Reporter): Word =
      // trigger checking of parameters first to have the current scope
      paramTypes
      bodyTyped = transform(funDef.body)(using funScope)
      bodyTyped

    def checkBodyType()(using Reporter): Type = checkBody().tpe

    def getCheckedBody()(using Reporter): Word =
      if bodyTyped == null then checkBody() else bodyTyped

    lazy val givenResultType =
      // trigger checking of parameters first to have the current scope
      paramTypes

      assert(!funDef.resType.isEmpty)
      val resTypeTree = transformType(funDef.resType)(using funScope)
      checker.delayedCheck { checker.checkVoidOrValueType(resTypeTree) }
      resTypeTree.tpe

    lazy val paramTypes =
      for (tparam, i) <- funDef.tparams.zipWithIndex yield
        val infoProvider: InfoProvider = (sym: Symbol) => bounds(i)
        val sym = Symbol.createTypeSymbol(tparam.name, infoProvider, tparam.pos)
        tparamSyms += sym
        funScope.define(sym, tparam.span)

      for tparam <- funDef.tparams do
        bounds +=(
          if tparam.bound.isEmpty then
            TypeBound(BottomType, AnyType)
          else
            val boundTree = transformType(tparam.bound)(using funScope)
            TypeBound(BottomType, boundTree.tpe)
        )

      for param <- funDef.params yield
        val tpt = transformType(param.typ)(using funScope)
        val paramSym = Symbol.createParamSymbol(param.name, tpt.tpe, param.pos)
        funScope.define(paramSym, param.span)
        paramSyms += paramSym
        tpt.tpe

    def createFunType(resType: Type): Type =
      val procType = ProcType(paramNames, paramTypes, resType)
      if bounds.isEmpty then procType
      else
        val tparamRefs = tparamSyms.zipWithIndex.map: (tparamSym, i) =>
          TypeParamRef(tparamSym.name, i)
        val substs = tparamSyms.zip(tparamRefs).toMap
        val rawType = PolyType(tparamNames, bounds.toList, procType)
        TypeOps.substSymbols(rawType, substs)

    lazy val givenFunType =
      assert(!funDef.resType.isEmpty)
      createFunType(givenResultType)

    def computeType(using Reporter): Type =
      if !funDef.resType.isEmpty then
        givenFunType
      else
        // perform actual check without using the cache
        createFunType(checkBodyType())

    val initialType = () =>
      if !funDef.resType.isEmpty then givenFunType
      else createFunType(BottomType)

    checker.cyclicTypeProvider.addProvider(
      sym, rp => initialType(), rp => computeType(using rp)
    )

    val typer = () =>
      val bodyTyped = getCheckedBody()

      if !funDef.resType.isEmpty then
        checker.checkType(bodyTyped, givenResultType)

      FunDef(
        sym, tparamSyms.toList, paramSyms.toList, bodyTyped)(
        locals = Nil, captures = Nil, funDef.span)

    DelayedDef(sym, typer)

  private def transform(tdef: Ast.TypeDef)(using sc: Scope, rp: Reporter): DelayedDef[TypeDef] =
    val names = new mutable.ArrayBuffer[String]
    val bounds = new mutable.ArrayBuffer[Type]

    val sym = Symbol.createTypeSymbol(tdef.name, checker.nonCyclicTypeProvider, tdef.ident.pos)

    def computeInfo(sym: Symbol): Type =
      if tdef.tparams.isEmpty then
        val rhs = transformType(tdef.rhs)
        checker.delayedCheck { checker.checkValueType(rhs) }
        rhs.tpe
      else
        val sc2 = sc.fresh(sym)
        val tparamSyms =
          for (tparam, i) <- tdef.tparams.zipWithIndex yield
            names += tparam.name

            val info: InfoProvider = sym => bounds(i)
            val sym = Symbol.createTypeSymbol(tparam.name, info, tparam.pos)
            sc2.define(sym, tparam.span)
            sym

        for tparam <- tdef.tparams do
          bounds +=(
            if tparam.bound.isEmpty then
              TypeBound(BottomType, AnyType)
            else
              val boundTree = transformType(tparam.bound)(using sc2)
              TypeBound(BottomType, boundTree.tpe)
          )

        val tparamRefs = tparamSyms.zipWithIndex.map: (tparamSym, i) =>
          TypeParamRef(tparamSym.name, i)
        val subst = tparamSyms.zip(tparamRefs).toMap

        val rhs = transformType(tdef.rhs)(using sc2)
        checker.delayedCheck { checker.checkValueType(rhs) }
        val rawType = TypeLambda(names.toList, bounds.toList, rhs.tpe)
        TypeOps.substSymbols(rawType, subst)

    checker.nonCyclicTypeProvider.addProvider(sym, computeInfo)

    // check type symbols after completion to allow cycles, type A = A
    val typer = () => TypeDef(sym)(tdef.span)
    DelayedDef(sym, typer)

  /** Type check type tree
    *
    * Checks must be delayed by using `checker.delayedCheck`.
    */
  private def transformType(tpt: Ast.TypeTree)(using sc: Scope, rp: Reporter): TypeTree =
    tpt match
      case Ast.Ident(name) =>
        sc.resolve(name, isType = true) match
          case Some(sym) =>
            TypeTree(TypeRef(sym))(tpt.span)

          case None =>
            Reporter.error("Unknown type " + tpt, tpt.pos)
            TypeTree(ErrorType)(tpt.span)

      case Ast.RecordType(fields) =>
        val fieldTypes = new mutable.ArrayBuffer[(String, Type)]
        for field <- fields do
          if fieldTypes.exists(_._1 == field.name) then
            Reporter.error("Field " + field.name + " already defined", field.pos)
          else
            val tpt = transformType(field.typ)
            checker.delayedCheck { checker.checkValueType(tpt) }
            fieldTypes += field.name -> tpt.tpe
        end for
        TypeTree(RecordType(fieldTypes.toList))(tpt.span)

      case Ast.UnionType(branches) =>
        val branchTypes = new mutable.ArrayBuffer[(String, List[Type])]
        for branch <- branches do
          if branchTypes.exists(_._1 == branch.name) then
            Reporter.error("Branch " + branch.name + " already defined", branch.pos)
          else
            val tps =
              for tpt <- branch.tpts yield
                val tpt2 = transformType(tpt)
                checker.delayedCheck { checker.checkValueType(tpt2) }
                tpt2.tpe

            branchTypes += branch.name -> tps
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

  def transform(using reporter: Reporter): Ast.Prog => Prog =
    new Namer(reporter).transform

  private class DelayedDef[+T <: Def](val symbol: Symbol, delayed: () => T):
    private lazy val definition: T = delayed()
    def force(): T =
      symbol.info // force symbol
      definition

  private class LazyValue[S, T](compute: S => T):
    var cache: T | Null = null
    def get(s: S): T =
      if cache == null then cache = compute(s)
      cache.asInstanceOf[T]

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

    def isLocalScope = owners.isEmpty

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

    def resolve(name: String, span: Span, isType: Boolean = false)(using Reporter): Symbol =
      resolve(name, isType) match
        case Some(sym) => sym
        case None =>
          Reporter.error("Undefined identifier " + name, span.toPos)
          errorSymbol

    def define(sym: Symbol, span: Span)(using Reporter): Unit =
      val table = getTable(sym.isType)
      table.get(sym.name) match
        case None =>
          table(sym.name) = sym

        case Some(sym) =>
          Reporter.error(sym.name + " is already bound", span.toPos)
