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
          val flags = if vdef.mutable then Flag.Mutable else Flag.empty
          val sym = Symbol.createValueSymbol(defn.name, flags)
          sc.define(sym, defn.pos)

        case funDef: Ast.FunDef =>
          val info = StackInfo(funDef.params.size.toByte, 1)
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

    val mainWords = transform(prog.main)(using sc)
    val mainSym = Symbol.createFunSymbol("main", StackInfo(0, 0))
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
         Word.If(transform(cond), transform(thenp), transform(elsep)).withPos(word.pos) :: Nil

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

        val sym = Symbol.createValueSymbol(vdef.name, flags)
        val rhs = transform(vdef.words)
        sc.define(sym, vdef.pos)
        Word.Assign(sym, rhs).withPos(vdef.pos) :: Nil

  private def transform(words: List[Ast.Word])(using sc: Scope): List[Word] =
    words.flatMap(word => transform(word))

  private def transform(sym: Symbol, funDef: Ast.FunDef)(using sc: Scope): Fun =
    val locals = new mutable.ArrayBuffer[Symbol]
    val funScope = sc.fresh(sym => if !sym.isParameter then locals.addOne(sym))
    val paramSyms =
      for param <- funDef.params
      yield
        val paramSym = Symbol.createParamSymbol(param.name)
        funScope.define(paramSym, param.pos)
        paramSym

    val words = transform(funDef.words)(using funScope)
    Fun(sym, paramSyms, locals.toList, words).withPos(funDef.pos)

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
        case None      => Reporter.abort("Undefined identifier " + name, span)

    def define(sym: Symbol, span: Span): Unit =
      map.get(sym.name) match
        case None =>
          map(sym.name) = sym
          notifyDefined(sym)

        case Some(sym) =>
          Reporter.abort(sym.name + " is already bound", span)
