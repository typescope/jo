import scala.collection.mutable
import scala.collection.immutable

import Sast.*
import Types.*
import Symbols.*
import Reporter.Span

/**
  * The namer handles name resolution and desugaring.
  *
  * It converts ASTs to Semantic ASTs.
  */
class Namer(using Reporter):
  val checker = new Checker

  val errorSymbol = Symbol.createFunSymbol("error", Type.Error)

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
    for defn <- prog.defs do index(defn)(using sc)

    val funs = new mutable.ArrayBuffer[Fun]
    val inits = new mutable.ArrayBuffer[Assign]
    val locals = new mutable.ArrayBuffer[Symbol]

    val mainFunScope = sc.fresh(sym => if !sym.isParameter then locals.addOne(sym))

    for defn <- prog.defs yield
      defn match
        case valDef: Ast.ValDef =>
          val Some(sym) = sc.resolve(defn.name, isType = false): @unchecked
          val rhs = transform(valDef.rhs)(using mainFunScope)
          checker.expect(rhs, sym.info)
          inits += new Assign(sym, rhs)(defn.pos)

        case funDef: Ast.FunDef =>
          val Some(sym) = sc.resolve(defn.name, isType = false): @unchecked
          funs += transform(sym, funDef)(using sc)

        case tdef: Ast.TypeDef =>
    end for

    val mainPhrase = transform(prog.main)(using mainFunScope)
    val mainType = Type.Proc(names = Nil, paramTypes = Nil, resType = Type.Void)
    val mainSym = Symbol.createFunSymbol("main", mainType)
    val mainPos = mainPhrase.pos
    val mainBody = Phrase((inits ++ mainPhrase.words).toList)(mainPhrase.tpe, mainPos)
    val params = Nil
    val mainFun = Fun(mainSym, params, locals.toList, mainBody)(mainPos)

    funs += mainFun

    Prog(funs.toList, inits.map(_.symbol).toList, mainSym)

  private def index(defn: Ast.Def)(using sc: Scope) =
   defn match
     case vdef: Ast.ValDef =>
       val tpe = transform(vdef.typ)(using sc)
       checker.expectValueType(tpe, vdef.typ.pos)
       val flags = if vdef.mutable then Flag.Mutable else Flag.empty
       val sym = Symbol.createValueSymbol(defn.name, tpe, flags)
       sc.define(sym, defn.pos)

     case funDef: Ast.FunDef =>
       val paramNames = funDef.params.map(_.name)
       val paramTypes =
         for param <- funDef.params yield
           val paramType = transform(param.typ)(using sc)
           checker.expectValueType(paramType, param.typ.pos)
           paramType

       val resType = transform(funDef.resType)(using sc)
       val funType = Type.Proc(paramNames, paramTypes, resType)
       val sym = Symbol.createFunSymbol(defn.name, funType)
       sc.define(sym, defn.pos)

     case tdef: Ast.TypeDef =>
       // TODO: fix scope of type definitions or make type checking lazy
       val info = transform(tdef.rhs)(using sc)
       val sym = Symbol.createTypeSymbol(defn.name, info)
       sc.define(sym, defn.pos, isType = true)

  private def transform(word: Ast.Word)(using sc: Scope): Word =
    word match
      case Ast.IntLit(v)  =>
        IntLit(v)(Type.Int, word.pos)

      case Ast.BoolLit(v) =>
        BoolLit(v)(Type.Bool, word.pos)

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

    val tpe = transform(vdef.typ)
    val rhs = transform(vdef.rhs)

    checker.expectValueType(tpe, vdef.typ.pos)
    val sym = Symbol.createValueSymbol(vdef.name, tpe, flags)

    checker.expect(rhs, tpe)

    sc.define(sym, vdef.pos)
    Assign(sym, rhs)(vdef.pos)

  private def transform(ifte: Ast.If)(using sc: Scope): Word =
    val Ast.If(cond, thenp, elsep) = ifte
    val cond2 = transform(cond)
    val then2 = transform(thenp)
    val else2 = transform(elsep)
    checker.expect(cond2, Type.Bool)
    if !matches(then2.tpe, else2.tpe) then
      Reporter.error(
        "Expect if branches to have the same type," +
          s"found = ${then2.tpe} and ${else2.tpe}",
        ifte.pos)

    If(cond2, then2, else2)(then2.tpe, ifte.pos)

  private def transform(record: Ast.RecordLit)(using sc: Scope): Word =
    val Ast.RecordLit(namedArgs) = record
    val namedArgs2 = new mutable.ListMap[String, Phrase]
    for Ast.NamedArg(id, rhs) <- namedArgs do
      if namedArgs2.contains(id.name) then
        Reporter.error("Arg " + id.name + " already defined", id.pos)
      else
        val rhs2 = transform(rhs)
        checker.expectValueType(rhs2)
        namedArgs2 += id.name -> rhs2
    end for
    val fields = immutable.ListMap.from(namedArgs2)
    val tpe = Type.Record(fields.map { case (k, v) => k -> v.tpe })
    RecordLit(fields)(tpe, record.pos)

  private def transform(variant: Ast.Variant)(using sc: Scope): Word =
    val Ast.Variant(tag, value, typ) = variant
    val value2 = transform(value)
    val unionType = transform(typ)
    val tagType = checker.checkTagValue(tag, value2, unionType, typ.pos)

    // encode variants as records
    val tagIndex =
      if tagType.isError then -1
      else unionType.tagIndex(tag.name)

    val tagValue =
      Phrase(IntLit(tagIndex)(Type.Int, tag.pos) :: Nil)(Type.Int, tag.pos)

    val fields =
      if tagType.isVoid then
        immutable.ListMap("tag" -> tagValue)
      else
        immutable.ListMap("tag" -> tagValue, "value" -> value2)

    // desugar variant to record
    val fieldTypes =
      if tagType.isVoid then
        immutable.ListMap("tag" -> Type.Int)
      else
        immutable.ListMap("tag" -> Type.Int, "value" -> tagType)

    val encodeType = Type.Record(fieldTypes)

    Encoded(RecordLit(fields)(encodeType, variant.pos))(unionType)

  private def transform(patmat: Ast.Match)(using sc: Scope): Phrase =
    val sc2 = sc.fresh()

    val Ast.Match(scrutinee, cases) = patmat
    val scrutinee2 = transform(scrutinee)
    checker.expectValueType(scrutinee2)

    val scrutType = scrutinee2.tpe
    val scrutSym = Symbol.createValueSymbol("scrutinee", scrutType)
    val bindAssign = Assign(scrutSym, scrutinee2)(scrutinee.pos)
    sc2.define(scrutSym, scrutinee.pos)

    def transformCases(cases: List[Ast.Case]): Phrase =
      val caseScope = sc2.fresh()
      cases match
        case (caseDef @ Ast.Case(pat, body)) :: rest =>
          pat match
            case Ast.Wildcard() =>
              // all remaining patterns are ignored
              if cases.nonEmpty then
                Reporter.error("Cases after wildcard are ignored", pat.pos)
              transform(body)(using caseScope)

            case Ast.TagPat(tag, bindings) =>
              val tagType = checker.tagType(tag, scrutType, scrutinee.pos)
              if tagType.isVoid && bindings.nonEmpty then
                Reporter.error("The tag does not take arguments", tag.pos)
                transformCases(rest)

              else if !tagType.isVoid && bindings.isEmpty then
                Reporter.error("The tag take an argument", tag.pos)
                transformCases(rest)

              else
                val fieldTypes = immutable.ListMap("tag" -> Type.Int, "value" -> tagType)
                val encodeType = Type.Record(fieldTypes)
                val encodedScrut = Encoded(Ident(scrutSym)(scrutinee.pos))(encodeType)
                val tagFieldSel = Select(encodedScrut, "tag")(Type.Int, tag.pos)

                val bindingAssigns = mutable.ArrayBuffer.empty[Assign]
                for binding <- bindings do
                  val valFieldSel = Select(encodedScrut, "value")(tagType, binding.pos)
                  val sym = Symbol.createValueSymbol(binding.name, tagType)
                  bindingAssigns += Assign(sym, Phrase(valFieldSel))(binding.pos)
                  caseScope.define(sym, binding.pos)

                val tagIndex =
                  if tagType.isError then -1
                  else scrutType.tagIndex(tag.name)

                val condWords =
                  tagFieldSel
                    :: IntLit(tagIndex)(Type.Int, tag.pos)
                    :: Ident(predef.eql)(tag.pos)
                    :: Nil

                val cond = Phrase(condWords)(Type.Bool, tag.pos)
                val body2 = transform(body)(using caseScope)
                val elsep =  transformCases(rest)
                Phrase(If(cond, body2, elsep)(body2.tpe, caseDef.pos))

        case Nil =>
          // TODO: throw runtime exception
          Phrase(Nil)(Type.Void, patmat.pos)

    transformCases(cases)

  private def transform(phrase: Ast.Phrase)(using sc: Scope): Phrase =
    val sc2 = sc.fresh()

    for tdef <- phrase.tdefs do
      // TODO: fix scope of type definitions or make type checking lazy
      val info = transform(tdef.rhs)(using sc2)
      val sym = Symbol.createTypeSymbol(tdef.name, info)
      sc.define(sym, tdef.pos, isType = true)

    val wordsTyped = phrase.words.flatMap: word =>
        transform(word)(using sc2) match
          case Phrase(Nil) => Nil
          case word => word :: Nil

    val vs = new Checker.ValueStack
    checker.check(wordsTyped)(using vs)

    val tp =
      if !vs.isError && vs.size > 1 then
        Reporter.error(
          "At most one value expected, found = " + vs.size, phrase.pos)
        Type.Error
      else
        vs.pop() match
          case Some(tp) =>
            if !tp.isValueType then
              Reporter.error(
                "Value expected, found type = " + tp, phrase.pos)
              Type.Error
            else
              tp

          case None => Type.Void

    Phrase(wordsTyped)(tp, phrase.pos)

  private def transform(sym: Symbol, funDef: Ast.FunDef)(using sc: Scope): Fun =
    val locals = new mutable.ArrayBuffer[Symbol]
    val funScope = sc.fresh(sym => if !sym.isParameter then locals.addOne(sym))
    val paramSyms =
      for param <- funDef.params
      yield
        val tpe = transform(param.typ)
        val paramSym = Symbol.createParamSymbol(param.name, tpe)
        funScope.define(paramSym, param.pos)
        paramSym

    val body2 = transform(funDef.body)(using funScope)
    checker.expect(body2, sym.info.resultType)
    Fun(sym, paramSyms, locals.toList, body2)(funDef.pos)

  private def transform(tpt: Ast.TypeTree)(using sc: Scope): Type =
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
        val fieldTypes = new mutable.ListMap[String, Type]
        for field <- fields do
          if fieldTypes.contains(field.name) then
            Reporter.error("Field " + field.name + " already defined", field.pos)
          else
            fieldTypes += field.name -> transform(field.typ)
        end for
        Type.Record(immutable.ListMap.from(fieldTypes))

      case Ast.UnionType(branches) =>
        val branchTypes = new mutable.ListMap[String, Type]
        for branch <- branches do
          if branchTypes.contains(branch.name) then
            Reporter.error("Branch " + branch.name + " already defined", branch.pos)
          else
            branchTypes += branch.name -> transform(branch.typ)
        end for
        Type.Union(immutable.ListMap.from(branchTypes))

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

    def resolve(name: String, span: Span, isType: Boolean = false): Symbol =
      resolve(name, isType) match
        case Some(sym) => sym
        case None =>
          Reporter.error("Undefined identifier " + name, span)
          errorSymbol

    def define(sym: Symbol, span: Span, isType: Boolean = false): Unit =
      val table = getTable(isType)
      table.get(sym.name) match
        case None =>
          table(sym.name) = sym
          notifyDefined(sym)

        case Some(sym) =>
          Reporter.error(sym.name + " is already bound", span)
