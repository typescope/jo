import scala.collection.mutable

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
    for defn <- prog.defs do
      defn match
        case vdef: Ast.ValDef =>
          val tpe = transform(vdef.typ)(using sc)
          val flags = if vdef.mutable then Flag.Mutable else Flag.empty
          val sym = Symbol.createValueSymbol(defn.name, tpe, flags)
          sc.define(sym, defn.pos)

        case funDef: Ast.FunDef =>
          val paramNames = funDef.params.map(_.name)
          val paramTypes =
            for param <- funDef.params yield
              val paramType = transform(param.typ)(using sc)
              checker.expectValueType(paramType, param.typ.pos)
              paramType.asInstanceOf[ValueType]

          val resType = transform(funDef.resType)(using sc)
          val funType = Type.Proc(paramNames, paramTypes, resType)
          val sym = Symbol.createFunSymbol(defn.name, funType)
          sc.define(sym, defn.pos)

    val funs = new mutable.ArrayBuffer[Fun]
    val inits = new mutable.ArrayBuffer[Word.Assign]
    val locals = new mutable.ArrayBuffer[Symbol]

    for defn <- prog.defs yield
      val Some(sym) = sc.resolve(defn.name, isType = false): @unchecked

      defn match
        case valDef: Ast.ValDef =>
          val valScope = sc.fresh(sym => if !sym.isParameter then locals.addOne(sym))
          val rhs = transform(valDef.rhs)(using valScope)
          inits += new Word.Assign(sym, rhs).withPos(defn.pos)

        case funDef: Ast.FunDef =>
          funs += transform(sym, funDef)(using sc)
    end for

    val mainPhrase = transform(prog.main)(using sc)
    val mainSym = Symbol.createFunSymbol("main", Type.Proc(Nil, Nil, Type.Void))
    val mainPos = mainPhrase.pos
    val mainBody = Phrase((inits ++ mainPhrase.words).toList, mainPhrase.tpe).withPos(mainPos)
    val params = Nil
    val mainFun = Fun(mainSym, params, locals.toList, mainBody).withPos(mainPos)

    funs += mainFun

    Prog(funs.toList, inits.map(_.symbol).toList, mainSym)

  private def transform(word: Ast.Word)(using sc: Scope): List[Word] =
    word match
      case Ast.IntLit(v)  =>
        Word.IntLit(v).withPos(word.pos) :: Nil

      case Ast.BoolLit(v) =>
        Word.BoolLit(v).withPos(word.pos) :: Nil

      case Ast.Fence(ws)  =>
        val phrase = transform(ws)
        phrase.words

      case Ast.If(cond, thenp, elsep) =>
         val cond2 = transform(cond)
         val then2 = transform(thenp)
         val else2 = transform(elsep)
         checker.expect(cond2, Type.Bool)
         if !matches(then2.tpe, else2.tpe) then
           Reporter.error(
             "Expect if branches to have the same type," +
               s"found = ${then2.tpe} and ${else2.tpe}",
             word.pos)

         Word.If(cond2, then2, else2).withPos(word.pos) :: Nil

      case Ast.While(cond, body) =>
         val cond2 = transform(cond)
         val body2 = transform(body)
         checker.expect(cond2, Type.Bool)
         Word.While(cond2, body2).withPos(word.pos) :: Nil

      case Ast.Ident(name) =>
        val sym = sc.resolve(name, word.pos)
        Word.Ident(sym).withPos(word.pos) :: Nil

      case Ast.Assign(id, words) =>
        val sym = sc.resolve(id.name, id.pos)
        if !sym.isMutable then
          Reporter.error("The variable " + id.name + " is not mutable", id.pos)

        val rhs = transform(words)
        checker.expect(rhs, sym.info)
        Word.Assign(sym, rhs).withPos(word.pos) :: Nil

      case vdef: Ast.ValDef =>
        var flags: Flags = Flag.Local
        if vdef.mutable then
          flags = flags | Flag.Mutable

        val tpe = transform(vdef.typ)
        val rhs = transform(vdef.rhs)

        checker.expectValueType(tpe, vdef.typ.pos)
        val sym = Symbol.createValueSymbol(vdef.name, tpe, flags)

        checker.expect(rhs, tpe)

        sc.define(sym, vdef.pos)
        Word.Assign(sym, rhs).withPos(vdef.pos) :: Nil

  private def transform(phrase: Ast.Phrase)(using sc: Scope): Phrase =
    val sc2 = sc.fresh()
    val wordsTyped = phrase.words.flatMap: word =>
        transform(word)(using sc2)

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

    Phrase(wordsTyped, tp).withPos(phrase.pos)

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
    Fun(sym, paramSyms, locals.toList, body2).withPos(funDef.pos)

  private def transform(tpt: Ast.TypeTree)(using sc: Scope): Type =
    tpt match
      case Ast.Ident(name) =>
        sc.resolve(name, isType = true) match
          case Some(sym) =>
            if sym.isPrimitive then sym.info
            else ??? // impossible

          case None =>
            Reporter.error("Unknown type " + tpt, tpt.pos)
            Type.Error

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
          Reporter.abort(sym.name + " is already bound", span)
