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
    val sc = new Scope.NestedScope(rootScope)
    for defn <- prog.defs do
      val sym =
        defn match
          case _: Ast.Def.ValDef =>
            Symbol.createValueSymbol(defn.name)
          case funDef: Ast.Def.FunDef =>
            val info = StackInfo(funDef.params.size.toByte, 1)
            Symbol.createFunSymbol(defn.name, info)

      sc.define(sym, defn.pos)

    val defs = for defn <- prog.defs yield transform(defn)(using sc)
    val main = transform(prog.main)(using sc)

    // check code
    for defn <- defs do checker.check(defn)
    checker.check(main)(using new Checker.ValueStack)

    Prog(defs, main)

  private def transform(word: Ast.Word)(using sc: Scope): List[Word] =
    word match
      case Ast.Word.IntLit(v)  => Word.IntLit(v).withPos(word.pos) :: Nil
      case Ast.Word.BoolLit(v) => Word.BoolLit(v).withPos(word.pos) :: Nil
      case Ast.Word.Fence(ws)  =>
        val words = transform(ws)
        checker.check(words)(using new Checker.ValueStack)
        words

      case Ast.Word.If(cond, thenp, elsep) =>
         Word.If(transform(cond), transform(thenp), transform(elsep)).withPos(word.pos) :: Nil

      case Ast.Word.Ident(name) =>
        sc.resolve(name) match
          case Some(sym) => Word.Ident(sym).withPos(word.pos) :: Nil
          case None      => Reporter.abort("Undefined identifier " + name, word.pos)

  private def transform(words: List[Ast.Word])(using sc: Scope): List[Word] =
    words.flatMap(word => transform(word))

  private def transform(defn: Ast.Def)(using sc: Scope): Def =
    val Some(sym) = sc.resolve(defn.name): @unchecked
    defn match
      case valDef: Ast.Def.ValDef =>
        Def.ValDef(sym, transform(valDef.words)).withPos(defn.pos)

      case funDef: Ast.Def.FunDef =>
        val funScope = new Scope.NestedScope(sc)
        val paramSyms =
          for param <- funDef.params
          yield
            val paramSym = Symbol.createParamSymbol(param.name)
            funScope.define(paramSym, param.pos)
            paramSym

        val words = transform(funDef.words)(using funScope)
        Def.FunDef(sym, paramSyms, words).withPos(defn.pos)

  private enum Scope:
    case RootScope()
    case NestedScope(outer: Scope)

    private val map: mutable.Map[String, Symbol] = mutable.Map.empty

    def resolve(name: String): Option[Symbol] =
      map.get(name) match
        case None =>
          this match
            case NestedScope(outer) => outer.resolve(name)
            case _ => None

        case res  => res

    def define(sym: Symbol, span: Span): Unit =
      map.get(sym.name) match
        case None =>
          map(sym.name) = sym

        case Some(sym) =>
          Reporter.abort(sym.name + " is already bound", span)
