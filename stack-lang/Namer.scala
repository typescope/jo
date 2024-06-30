import scala.collection.mutable

import Sast.*
import Types.*
import Symbols.*
import Reporter.Span
import Namer.{ Scope, DelayedTask, errorSymbol }

/**
  * The namer handles name resolution and desugaring.
  *
  * It converts ASTs to Semantic ASTs.
  */
class Namer(using Reporter):
  val checker = new Checker

  def transform(prog: Ast.Prog): Sast.Prog =
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
    val main2 = transform(prog.main)(using sc)

    checker.performDelayedChecks()

    Prog(defs, main2)

  private def transform(defs: List[Ast.Def])(using sc: Scope): List[Def] =
    val tasks = new mutable.ArrayBuffer[DelayedTask]

    for defn <- defs do
      tasks += index(defn)

    for task <- tasks.toList yield
      task.typer()

  private def index(defn: Ast.Def)(using sc: Scope): DelayedTask =

    defn match
      case vdef: Ast.ValDef =>
        lazy val rhs = transform(vdef.rhs)
        lazy val tpe: Type =
          if vdef.typ.isEmpty then
            rhs.tpe
          else
            val tpt = transformType(vdef.typ)
            val tp2 = checker.checkValueType(tpt.tpe, tpt.pos)
            checker.checkType(rhs, tp2)
            tp2

        val delayedType = DelayedType()(tpe)

        val flags = if vdef.mutable then Flag.Mutable else Flag.empty
        val sym = Symbol.createValueSymbol(defn.name, delayedType, flags)
        sc.define(sym, defn.pos)

        val typer = () =>
          val tp = tpe // ensure tpe is forced for error checking
          ValDef(sym, rhs)(vdef.pos)
        DelayedTask(sym, typer)

      case funDef: Ast.FunDef =>
        transform(funDef)

      case tdef: Ast.TypeDef =>
        transform(tdef)
    end match
  end index

  private def transform(word: Ast.Word)(using sc: Scope): Word =
    word match
      case Ast.IntLit(v)  =>
        IntLit(v)(word.pos)

      case Ast.BoolLit(v) =>
        BoolLit(v)(word.pos)

      case Ast.Fence(ws)  =>
        val phrase = transform(ws)
        phrase

      case ifte: Ast.If =>
        transform(ifte)

      case Ast.While(cond, body) =>
         val cond2 = transform(cond)
         val body2 = transform(body)
         checker.checkType(cond2, BoolType)
         While(cond2, body2)(word.pos)

      case Ast.Ident(name) =>
        val sym = sc.resolve(name, word.pos)
        Ident(sym)(word.pos)

      case Ast.Assign(id, words) =>
        val sym = sc.resolve(id.name, id.pos)
        if !sym.isMutable then
          Reporter.error("The variable " + id.name + " is not mutable", id.pos)

        val rhs = transform(words)
        checker.checkType(rhs, sym.info)
        Assign(sym, rhs)(word.pos)

      case record: Ast.RecordLit =>
        transform(record)

      case variant: Ast.Variant =>
        transform(variant)

      case patmat: Ast.Match =>
        transform(patmat)

      case Ast.Select(qual, name) =>
        val qual2 = transform(qual)
        val tp = checker.fieldType(qual2.tpe, name, qual.pos)
        Select(qual2, name)(tp, word.pos)

      case Ast.TypeApply(fun, targs) =>
        val fun2 = transform(fun)
        val targs2 = targs.map(transformType)
        checker.checkTypeApply(fun2, targs2)

      case vdef: Ast.ValDef =>
        transform(vdef)

  private def transform(vdef: Ast.ValDef)(using sc: Scope): Word =
    var flags: Flags = Flag.Local
    if vdef.mutable then
      flags = flags | Flag.Mutable

    val rhs = transform(vdef.rhs)
    val tpe =
      if vdef.typ.isEmpty then
        rhs.tpe
      else
        val tpt = transformType(vdef.typ)
        val tp2 = checker.checkValueType(tpt.tpe, tpt.pos)
        checker.checkType(rhs, tp2)
        tp2

    val sym = Symbol.createValueSymbol(vdef.name, tpe, flags)

    sc.define(sym, vdef.pos)
    ValDef(sym, rhs)(vdef.pos)

  private def transform(ifte: Ast.If)(using sc: Scope): Word =
    val Ast.If(cond, thenp, elsep) = ifte
    val cond2 = transform(cond)
    val then2 = transform(thenp)
    val else2 = transform(elsep)
    checker.checkType(cond2, BoolType)

    // adapt result type
    val then3 = checker.adapt(then2, else2.tpe, then2.pos)
    val else3 = checker.adapt(else2, then3.tpe, else2.pos)

    If(cond2, then3, else3)(else3.tpe, ifte.pos)

  private def transform(record: Ast.RecordLit)(using sc: Scope): Word =
    val Ast.RecordLit(namedArgs) = record
    val namedArgs2 = new mutable.ArrayBuffer[(String, Phrase)]
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
    RecordLit(fields)(tpe, record.pos)

  private def transform(variant: Ast.Variant)(using sc: Scope): Word =
    val Ast.Variant(tag, values, typ) = variant
    val values2 = values.map(transform)
    val unionType = transformType(typ)
    val tagTypesOpt = checker.tagTypes(tag, unionType.tpe, unionType.pos)

    val tagTypes =
      tagTypesOpt match
        case Some(tagTypes) =>
          checker.checkTagValues(values2, tagTypes, tag.pos)
          tagTypes

        case None =>
          Nil

    // encode variants as records
    val tagIndex =
      if tagTypesOpt.isEmpty then -1
      else unionType.tpe.asUnionType.tagIndex(tag.name)

    val encodedValue = Desugaring.encodeVariant(tagIndex, values2, tagTypes, tag.pos, variant.pos)
    Encoded(encodedValue)(unionType.tpe)

  private def transform(patmat: Ast.Match)(using sc: Scope): Word =
    val sc2 = sc.fresh()

    val Ast.Match(scrutinee, cases) = patmat
    val scrutinee2 = transform(scrutinee)
    checker.checkValueType(scrutinee2)

    val scrutType = scrutinee2.tpe
    val scrutSym = Symbol.createValueSymbol("scrutinee", scrutType, Flag.Local)
    val scrutIdent = Ident(scrutSym)(scrutinee.pos)
    val bind = ValDef(scrutSym, scrutinee2)(scrutinee.pos)
    sc2.define(scrutSym, scrutinee.pos)

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
            IntLit(1)(scrutIdent.pos)
              :: Ident(runtime.abort)(scrutIdent.pos)
              :: Nil
          val res = Phrase(words)(BottomType, patmat.pos)
          checker.adapt(res, resType, patmat.pos)
      end match

    val body = transformCases(cases, BottomType, allTags)
    Phrase(bind :: body :: Nil)(body.tpe, patmat.pos)

  private def transform
    (scrut: Ident, caseDef: Ast.Case, resType: Type, cont: Type => Word)
    (using sc: Scope): Word =

    val caseScope = sc.fresh()

    val Ast.Case(pat, body) = caseDef
    val scrutPos = scrut.pos
    val scrutType = scrut.tpe

    pat match
      case Ast.Wildcard() =>
        // TODO: all remaining patterns are ignored
        // if cases.nonEmpty then
        //  Reporter.error("Cases after wildcard are ignored", pat.pos)
        val body2 = transform(body)(using caseScope)
        val adapted = checker.adapt(body2, resType, body2.pos)
        val elsep = cont(adapted.tpe)
        checker.adapt(adapted, elsep.tpe, body2.pos)

      case Ast.TagPat(tag, bindings) =>
        val tagTypesOpt = checker.tagTypes(tag, scrutType, scrutPos)
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
            val arg = Desugaring.selectVariantArg(encodedScrut, i, binding.pos)
            val sym = Symbol.createValueSymbol(binding.name, arg.tpe, Flag.Local)
            vals += ValDef(sym, arg)(binding.pos)
            caseScope.define(sym, binding.pos)

          val tagIndex =
            if tagTypesOpt.isEmpty then -1
            else scrutType.asUnionType.tagIndex(tag.name)

          val cond = Desugaring.testVariantTag(encodedScrut, tagIndex, tag.pos)
          val body2 = transform(body)(using caseScope)
          val adapted = checker.adapt(body2, resType, body2.pos)
          val elsep = cont(adapted.tpe)
          val adapted2 = checker.adapt(adapted, elsep.tpe, body2.pos)

          val body3 = Phrase(vals.toList :+ adapted2)(adapted2.tpe, caseDef.pos)
          If(cond, body3, elsep)(body3.tpe, caseDef.pos)

  private def transform(phrase: Ast.Phrase)(using sc: Scope): Phrase =
    val sc2 = sc.fresh()

    transform(phrase.tdefs)(using sc2)

    val wordsTyped = phrase.words.flatMap: word =>
      transform(word)(using sc2) match
        case Phrase(Nil) => Nil
        case word => word :: Nil

    val vs = new Checker.ValueStack
    checker.check(wordsTyped)(using vs)

    val tp =
      if !vs.isError && vs.size > 1 then
        Reporter.error("At most one value expected, found = " + vs.size, phrase.pos)
        ErrorType
      else
        vs.pop() match
          case Some(tp) => checker.checkValueType(tp, phrase.pos)
          case None => VoidType

    Phrase(wordsTyped)(tp, phrase.pos)

  private def transform(funDef: Ast.FunDef)(using sc: Scope): DelayedTask =
    val locals = new mutable.ArrayBuffer[Symbol]
    val paramSyms = new mutable.ArrayBuffer[Symbol]
    val tparamSyms = new mutable.ArrayBuffer[Symbol]
    val bounds = new mutable.ArrayBuffer[Type]
    val funScope = sc.fresh(sym => if !sym.isParameter then locals.addOne(sym))

    val paramNames = funDef.params.map(_.name)
    val tparamNames = funDef.tparams.map(_.name)

    lazy val finalResultType =
      // TODO: missing kind check
      val resTypeTree = transformType(funDef.resType)(using funScope)
      resTypeTree.tpe

    lazy val info =
      for (tparam, i) <- funDef.tparams.zipWithIndex yield
        val info = DelayedType() { bounds(i) }
        val sym = Symbol.createTypeSymbol(tparam.name, info)
        funScope.define(sym, tparam.pos)
        tparamSyms += sym
        sym

      for tparam <- funDef.tparams do
        bounds +=(
          if tparam.bound.isEmpty then
            TypeBound(BottomType, AnyType)
          else
            val boundTree = transformType(tparam.bound)(using funScope)
            TypeBound(BottomType, boundTree.tpe)
        )

      val paramTypes =
        for param <- funDef.params yield
          val tpt = transformType(param.typ)(using funScope)
          val paramSym = Symbol.createParamSymbol(param.name, tpt.tpe)
          funScope.define(paramSym, param.pos)
          paramSyms += paramSym
          tpt.tpe

      val procType = ProcType(paramNames, paramTypes, finalResultType)
      if bounds.isEmpty then procType
      else
        val tparamRefs = tparamSyms.zipWithIndex.map: (tparamSym, i) =>
          TypeParamRef(tparamSym.name, i)
        val substs = tparamSyms.zip(tparamRefs).toMap
        val rawType = PolyType(tparamNames, bounds.toList, procType)
        TypeOps.substSymbols(rawType, substs)

    val delayedType = DelayedType()(info)
    val sym = Symbol.createFunSymbol(funDef.name, delayedType)
    sc.define(sym, funDef.pos)

    val typer = () =>
      delayedType.force()
      val body2 = transform(funDef.body)(using funScope)
      checker.checkType(body2, finalResultType)
      FunDef(sym, tparamSyms.toList, paramSyms.toList, locals.toList, body2)(funDef.pos)

    DelayedTask(sym, typer)

  private def transform(tdef: Ast.TypeDef)(using sc: Scope): DelayedTask =
    val names = new mutable.ArrayBuffer[String]
    val bounds = new mutable.ArrayBuffer[Type]

    lazy val info =
      if tdef.tparams.isEmpty then
        transformType(tdef.rhs).tpe
      else
        val sc2 = sc.fresh()
        val tparamSyms =
          for (tparam, i) <- tdef.tparams.zipWithIndex yield
            names += tparam.name

            val info = DelayedType() { bounds(i) }
            val sym = Symbol.createTypeSymbol(tparam.name, info)
            sc2.define(sym, tparam.pos)
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

        val body = transformType(tdef.rhs)(using sc2)
        val rawType = TypeLambda(names.toList, bounds.toList, body.tpe)
        TypeOps.substSymbols(rawType, subst)

    val delayedType = DelayedType()(info)

    val sym = Symbol.createTypeSymbol(tdef.name, delayedType)
    sc.define(sym, tdef.pos)

    // check type symbols after completion to allow cycles, type A = A
    val typer = () =>
      info match
        case TypeLambda(_, _, body) =>
          checker.checkValueType(body, tdef.rhs.pos)
        case tp =>
          checker.checkValueType(tp, tdef.rhs.pos)

      TypeDef(sym)(tdef.pos)

    DelayedTask(sym, typer)

  private def transformType(tpt: Ast.TypeTree)(using sc: Scope): TypeTree =
    tpt match
      case Ast.Ident(name) =>
        sc.resolve(name, isType = true) match
          case Some(sym) =>
            TypeTree(TypeRef(sym))(tpt.pos)

          case None =>
            Reporter.error("Unknown type " + tpt, tpt.pos)
            TypeTree(ErrorType)(tpt.pos)

      case Ast.RecordType(fields) =>
        val fieldTypes = new mutable.ArrayBuffer[(String, Type)]
        for field <- fields do
          if fieldTypes.exists(_._1 == field.name) then
            Reporter.error("Field " + field.name + " already defined", field.pos)
          else
            val tpt = transformType(field.typ)
            checker.checkValueType(tpt)
            fieldTypes += field.name -> tpt.tpe
        end for
        TypeTree(RecordType(fieldTypes.toList))(tpt.pos)

      case Ast.UnionType(branches) =>
        val branchTypes = new mutable.ArrayBuffer[(String, List[Type])]
        for branch <- branches do
          if branchTypes.exists(_._1 == branch.name) then
            Reporter.error("Branch " + branch.name + " already defined", branch.pos)
          else
            val tps =
              for tpt <- branch.tpts yield
                val tpt2 = transformType(tpt)
                checker.checkValueType(tpt2)
                tpt2.tpe

            branchTypes += branch.name -> tps
        end for
        TypeTree(UnionType(branchTypes.toList))(tpt.pos)

      case Ast.AppliedType(tctor, targs) =>
        val tctor2 = transformType(tctor)
        val targs2 = for targ <- targs yield transformType(targ)
        checker.delayedCheck { checker.checkBounds(tctor2, targs2) }
        TypeTree(AppliedType(tctor2.tpe, targs2.map(_.tpe)))(tpt.pos)

      case Ast.FunctionType(paramTypes, resType) =>
        val paramTypes2 =
          for paramType <- paramTypes yield
            val tpt = transformType(paramType)
            checker.checkValueType(tpt)
            tpt.tpe

        val resType2 = transformType(resType)
        checker.checkValueType(resType2)

        TypeTree(FunctionType(paramTypes2, resType2.tpe))(tpt.pos)

      case _: Ast.EmptyTypeTree =>
        Reporter.abort("Unexpected empty type tree", tpt.pos)

object Namer:
  val errorSymbol = Symbol.createFunSymbol("error", ErrorType)

  private class DelayedTask(val symbol: Symbol, val typer: () => Def)

  private enum Scope:
    case RootScope()
    case NestedScope(outer: Scope, definedHandler: Symbol => Unit)

    private val termNames: mutable.Map[String, Symbol] = mutable.Map.empty
    private val typeNames: mutable.Map[String, Symbol] = mutable.Map.empty

    def fresh(): Scope =
      new Scope.NestedScope(this, _ => ())

    def fresh(definedHandler: Symbol => Unit): Scope =
      new Scope.NestedScope(this, definedHandler)

    private def getTable(isType: Boolean) =
      if isType then typeNames else termNames

    def notifyDefined(sym: Symbol): Unit =
      this match
        case Scope.RootScope() =>

        case ns: NestedScope =>
          ns.definedHandler(sym)
          ns.outer.notifyDefined(sym)

    def resolve(name: String, isType: Boolean): Option[Symbol] =
      val table = getTable(isType)
      table.get(name) match
        case None =>
          this match
            case NestedScope(outer, _) => outer.resolve(name, isType)
            case _ => None

        case res  => res

    def resolve(name: String, span: Span, isType: Boolean = false)(using Reporter): Symbol =
      resolve(name, isType) match
        case Some(sym) => sym
        case None =>
          Reporter.error("Undefined identifier " + name, span)
          errorSymbol

    def define(sym: Symbol, span: Span)(using Reporter): Unit =
      val table = getTable(sym.isType)
      table.get(sym.name) match
        case None =>
          table(sym.name) = sym
          notifyDefined(sym)

        case Some(sym) =>
          Reporter.error(sym.name + " is already bound", span)
