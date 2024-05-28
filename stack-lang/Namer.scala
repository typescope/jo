import scala.collection.mutable

import Sast.*
import Reporter.Span

/**
  * The namer handles name resolution and desugaring.
  *
  * It converts ASTs to Semantic ASTs.
  */
class Namer(using Reporter):
  val checker = new Checker

  val errorSymbol = Symbol.createFunSymbol("error", ???)

  def transform(prog: Ast.Prog): Sast.Prog =
    val rootScope = new Scope.RootScope()

    // Predefined symbols
    for sym <- Sast.predef.allSymbols do
      rootScope.define(sym, Reporter.NoSpan)

    // Prepare scope according to scoping rules
    val sc = rootScope.fresh()
    for defn <- prog.defs do
      defn match
        case vdef: Ast.ValDef =>
          val tpe = transform(vdef.typ)
          val flags = if vdef.mutable then Flag.Mutable else Flag.empty
          val sym = Symbol.createValueSymbol(defn.name, tpe, flags)
          sc.define(sym, defn.pos)

        case funDef: Ast.FunDef =>
          val paramNames = funDef.params.map(_.name)
          val paramTypes =
            for param <- funDef.params
            yield transform(param.typ)
          val resType = transform(funDef.resType)
          val funType = Type.Proc(paramNames, paramTypes, resType)
          val sym = Symbol.createFunSymbol(defn.name, info)
          sc.define(sym, defn.pos)

    val funs = new mutable.ArrayBuffer[Fun]
    val inits = new mutable.ArrayBuffer[Word.Assign]
    val locals = new mutable.ArrayBuffer[Symbol]

    for defn <- prog.defs yield
      val Some(sym) = sc.resolve(defn.name): @unchecked

      defn match
        case valDef: Ast.ValDef =>
          val valScope = sc.fresh(sym => if !sym.isParameter then locals.addOne(sym))
          val rhs = transform(valDef.words)(using valScope)
          inits += new Word.Assign(sym, rhs).withPos(defn.pos)

        case funDef: Ast.FunDef =>
          funs += transform(sym, funDef)(using sc)
    end for

    val mainWords = transform(prog.main)(using sc.fresh())
    val mainSym = Symbol.createFunSymbol("main", Type.Proc(Nil, Nil, Type.Unit))
    val mainBody = (inits ++ mainWords).toList
    val mainPos = mainWords.map(_.pos).reduce(_ | _)
    val params = Nil
    val mainFun = Fun(mainSym, params, locals.toList, mainBody).withPos(mainPos)

    funs += mainFun

    // check code
    for fun <- funs do checker.check(fun)

    Prog(funs.toList, inits.map(_.symbol).toList, mainSym)

  private def transform(word: Ast.Word)(using sc: Scope): List[Word] =
    word match
      case Ast.IntLit(v)  => Word.IntLit(v).withPos(word.pos) :: Nil
      case Ast.BoolLit(v) => Word.BoolLit(v).withPos(word.pos) :: Nil
      case Ast.Fence(ws)  =>
        val words = transform(ws)
        checker.check(words)(using new Checker.ValueStack)
        words

      case Ast.If(cond, thenp, elsep) =>
         val cond2 = transform(cond)(using sc.fresh())
         val then2 = transform(thenp)(using sc.fresh())
         val else2 = transform(elsep)(using sc.fresh())
         Word.If(cond2, then2, else2).withPos(word.pos) :: Nil

      case Ast.While(cond, body) =>
         val cond2 = transform(cond)(using sc.fresh())
         val body2 = transform(body)(using sc.fresh())
         Word.While(cond2, body2).withPos(word.pos) :: Nil

      case Ast.Ident(name) =>
        val sym = sc.resolve(name, word.pos)
        Word.Ident(sym).withPos(word.pos) :: Nil

      case Ast.Assign(id, words) =>
        val sym = sc.resolve(id.name, id.pos)
        if !sym.isMutable then
          Reporter.error("The variable " + id.name + " is not mutable", id.pos)

        val rhs = transform(words)
        Word.Assign(sym, rhs).withPos(word.pos) :: Nil

      case vdef: Ast.ValDef =>
        var flags: Flags = Flag.Local
        if vdef.mutable then
          flags = flags | Flag.Mutable

        val tpe = transform(vdef.typ)
        val sym = Symbol.createValueSymbol(vdef.name, tpe, flags)
        val rhs = transform(vdef.words)(using sc.fresh())
        sc.define(sym, vdef.pos)
        Word.Assign(sym, rhs).withPos(vdef.pos) :: Nil

  private def transform(words: Ast.Phrase)(using sc: Scope): Phrase = ???

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

    val words = transform(funDef.words)(using funScope)
    Fun(sym, paramSyms, locals.toList, words).withPos(funDef.pos)

  private def transform(tpt: Ast.TypeTree)(using sc: Scope): Type = ???

  private enum Scope:
    case RootScope()
    case NestedScope(outer: Scope, definedHandler: Symbol => Unit)

    private val map: mutable.Map[String, Symbol] = mutable.Map.empty

    def fresh(): Scope =
      new Scope.NestedScope(this, _ => ())

    def fresh(definedHandler: Symbol => Unit): Scope =
      new Scope.NestedScope(this, definedHandler)

    def notifyDefined(sym: Symbol): Unit =
      this match
        case Scope.RootScope() =>

        case ns: NestedScope =>
          ns.definedHandler(sym)
          ns.outer.notifyDefined(sym)

    def resolve(name: String): Option[Symbol] =
      map.get(name) match
        case None =>
          this match
            case NestedScope(outer, _) => outer.resolve(name)
            case _ => None

        case res  => res

    def resolve(name: String, span: Span): Symbol =
      resolve(name) match
        case Some(sym) => sym
        case None =>
          Reporter.error("Undefined identifier " + name, span)
          errorSymbol

    def define(sym: Symbol, span: Span): Unit =
      map.get(sym.name) match
        case None =>
          map(sym.name) = sym
          notifyDefined(sym)

        case Some(sym) =>
          Reporter.abort(sym.name + " is already bound", span)
