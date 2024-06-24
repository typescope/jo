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
    rootScope.define(predef.Int, Reporter.NoSpan, isType = true)
    rootScope.define(predef.Bool, Reporter.NoSpan, isType = true)
    rootScope.define(predef.Void, Reporter.NoSpan, isType = true)

    // Predefined term names
    for sym <- predef.allSymbols do
      rootScope.define(sym, Reporter.NoSpan)

    // Prepare scope according to scoping rules
    val sc = rootScope.fresh()
    val defs = transform(prog.defs)(using sc)
    val main2 = transform(prog.main)(using sc)

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
            val tp = transformType(vdef.typ)
            checker.expectValueType(tp, vdef.typ.pos)
            checker.expect(rhs, tp)
            tp

        val delayedType = Type.Delayed()(tpe)

        val flags = if vdef.mutable then Flag.Mutable else Flag.empty
        val sym = Symbol.createValueSymbol(defn.name, delayedType, flags)
        sc.define(sym, defn.pos)

        val typer = () =>
          val tp = tpe // ensure tpe is forced for error checking
          ValDef(sym, rhs)(vdef.pos)
        DelayedTask(sym, typer)

      case funDef: Ast.FunDef =>
        val delayedType = Type.Delayed() {
          val paramNames = funDef.params.map(_.name)
          val paramTypes =
            for param <- funDef.params yield
              val paramType = transformType(param.typ)
              checker.expectValueType(paramType, param.typ.pos)
              paramType

          val resType = transformType(funDef.resType)
          Type.Proc(paramNames, paramTypes, resType)
        }

        val sym = Symbol.createFunSymbol(defn.name, delayedType)
        sc.define(sym, defn.pos)

        val typer = () => transform(sym, funDef)
        DelayedTask(sym, typer)

      case tdef: Ast.TypeDef =>
        lazy val info = transformType(tdef.rhs)
        val delayedType = Type.Delayed()(info)

        val sym = Symbol.createTypeSymbol(defn.name, delayedType)
        sc.define(sym, defn.pos, isType = true)

        // check type symbols after completion to allow cycles, type A = A
        val typer = () =>
          checker.expectValueType(info, tdef.rhs.pos)
          TypeDef(sym)(tdef.pos)

        DelayedTask(sym, typer)
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
         checker.expect(cond2, Type.Bool)
         While(cond2, body2)(word.pos)

      case Ast.Ident(name) =>
        val sym = sc.resolve(name, word.pos)
        Ident(sym)(word.pos)

      case Ast.Assign(id, words) =>
        val sym = sc.resolve(id.name, id.pos)
        if !sym.isMutable then
          Reporter.error("The variable " + id.name + " is not mutable", id.pos)

        val rhs = transform(words)
        checker.expect(rhs, sym.info)
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
        val tp = transformType(vdef.typ)
        checker.expectValueType(tp, vdef.typ.pos)
        checker.expect(rhs, tp)
        tp

    val sym = Symbol.createValueSymbol(vdef.name, tpe, flags)

    sc.define(sym, vdef.pos)
    ValDef(sym, rhs)(vdef.pos)

  private def transform(ifte: Ast.If)(using sc: Scope): Word =
    val Ast.If(cond, thenp, elsep) = ifte
    val cond2 = transform(cond)
    val then2 = transform(thenp)
    val else2 = transform(elsep)
    checker.expect(cond2, Type.Bool)

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
        checker.expectValueType(rhs2)
        namedArgs2 += id.name -> rhs2
    end for
    val fields = namedArgs2.toList
    val tpe = Type.Record(fields.map { case (k, v) => k -> v.tpe })
    RecordLit(fields)(tpe, record.pos)

  private def transform(variant: Ast.Variant)(using sc: Scope): Word =
    val Ast.Variant(tag, value, typ) = variant
    val value2 = transform(value)
    val unionType = transformType(typ)
    val tagType = checker.checkTagValue(tag, value2, unionType, typ.pos)

    // encode variants as records
    val tagIndex =
      if tagType.isError then -1
      else unionType.asUnionType.tagIndex(tag.name)

    val tagValue = IntLit(tagIndex)(tag.pos)

    val fields =
      if tagType.isVoid then
        List("tag" -> tagValue)
      else
        List("tag" -> tagValue, "value" -> value2)

    // desugar variant to record
    val fieldTypes =
      if tagType.isVoid then
        List("tag" -> Type.Int)
      else
        List("tag" -> Type.Int, "value" -> tagType)

    val encodeType = Type.Record(fieldTypes)

    Encoded(RecordLit(fields)(encodeType, variant.pos))(unionType)

  private def transform(patmat: Ast.Match)(using sc: Scope): Word =
    val sc2 = sc.fresh()

    val Ast.Match(scrutinee, cases) = patmat
    val scrutinee2 = transform(scrutinee)
    checker.expectValueType(scrutinee2)

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
          val res = Phrase(words)(Type.Bottom, patmat.pos)
          checker.adapt(res, resType, patmat.pos)
      end match

    val body = transformCases(cases, Type.Bottom, allTags)
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
        val tagType = checker.tagType(tag, scrutType, scrutPos)
        if tagType.isVoid && bindings.nonEmpty then
          Reporter.error("The tag does not take arguments", tag.pos)
          cont(Type.Bottom)

        else if !tagType.isVoid && bindings.isEmpty then
          Reporter.error("The tag take an argument", tag.pos)
          cont(Type.Bottom)

        else
          val fieldTypes = List("tag" -> Type.Int, "value" -> tagType)
          val encodeType = Type.Record(fieldTypes)
          val encodedScrut = Encoded(scrut)(encodeType)
          val tagFieldSel = Select(encodedScrut, "tag")(Type.Int, tag.pos)

          val vals = mutable.ArrayBuffer.empty[ValDef]
          for binding <- bindings do
            val valFieldSel = Select(encodedScrut, "value")(tagType, binding.pos)
            val sym = Symbol.createValueSymbol(binding.name, tagType, Flag.Local)
            vals += ValDef(sym, valFieldSel)(binding.pos)
            caseScope.define(sym, binding.pos)

          val tagIndex =
            if tagType.isError then -1
            else scrutType.asUnionType.tagIndex(tag.name)

          val condWords =
            tagFieldSel
              :: IntLit(tagIndex)(tag.pos)
              :: Ident(predef.eql)(tag.pos)
              :: Nil

          val cond = Phrase(condWords)(Type.Bool, tag.pos)
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
        Type.Error
      else
        vs.pop() match
          case Some(tp) =>
            if !tp.isValueType then
              Reporter.error("Value expected, found type = " + tp, phrase.pos)
              Type.Error
            else
              tp

          case None => Type.Void

    Phrase(wordsTyped)(tp, phrase.pos)

  private def transform(sym: Symbol, funDef: Ast.FunDef)(using sc: Scope): FunDef =
    val locals = new mutable.ArrayBuffer[Symbol]
    val funScope = sc.fresh(sym => if !sym.isParameter then locals.addOne(sym))
    val paramSyms =
      for param <- funDef.params
      yield
        val tpe = transformType(param.typ)
        val paramSym = Symbol.createParamSymbol(param.name, tpe)
        funScope.define(paramSym, param.pos)
        paramSym

    val body2 = transform(funDef.body)(using funScope)
    checker.expect(body2, sym.info.resultType)
    FunDef(sym, paramSyms, locals.toList, body2)(funDef.pos)

  private def transformType(tpt: Ast.TypeTree)(using sc: Scope): Type =
    tpt match
      case Ast.Ident(name) =>
        sc.resolve(name, isType = true) match
          case Some(sym) =>
            if sym.isPrimitive then sym.info
            else Type.TypeRef(sym)

          case None =>
            Reporter.error("Unknown type " + tpt, tpt.pos)
            Type.Error

      case Ast.RecordType(fields) =>
        val fieldTypes = new mutable.ArrayBuffer[(String, Type)]
        for field <- fields do
          if fieldTypes.exists(_._1 == field.name) then
            Reporter.error("Field " + field.name + " already defined", field.pos)
          else
            fieldTypes += field.name -> transformType(field.typ)
        end for
        Type.Record(fieldTypes.toList)

      case Ast.UnionType(branches) =>
        val branchTypes = new mutable.ArrayBuffer[(String, Type)]
        for branch <- branches do
          if branchTypes.exists(_._1 == branch.name) then
            Reporter.error("Branch " + branch.name + " already defined", branch.pos)
          else
            branchTypes += branch.name -> transformType(branch.typ)
        end for
        Type.Union(branchTypes.toList)

      case Ast.AppliedType(tctor, targs) =>
        val tctor2 = transformType(tctor)
        val targs2 = for targ <- targs yield transformType(targ)
        Type.AppliedType(tctor2, targs2)

      case _: Ast.EmptyTypeTree =>
        Reporter.abort("Unexpected empty type tree", tpt.pos)

object Namer:
  val errorSymbol = Symbol.createFunSymbol("error", Type.Error)

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

    def define(sym: Symbol, span: Span, isType: Boolean = false)(using Reporter): Unit =
      val table = getTable(isType)
      table.get(sym.name) match
        case None =>
          table(sym.name) = sym
          notifyDefined(sym)

        case Some(sym) =>
          Reporter.error(sym.name + " is already bound", span)
