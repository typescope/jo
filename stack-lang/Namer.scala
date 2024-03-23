import scala.collection.mutable

import Sast.*

/**
  * The namer handles name resolution and desugaring.
  *
  * It converts ASTs to Semantic ASTs.
  *
  * TODO: check stack safety.
  */
object Namer:
  /**
    * Represent number values on the value stack.
    *
    * Used to check stack safety.
    */
  class ValueStack:
    private var size: Int = 0
    def push() = size += 1
    def pop()  = size -= 1

  def transform(prog: Ast.Prog): Sast.Prog =
    val rootScope = new Scope.RootScope()

    // Predefined symbols
    for sym <- Sast.predef.allSymbols do
      rootScope.define(sym)

    // Prepare scope according to scoping rules
    val sc = new Scope.NestedScope(rootScope)
    for defn <- prog.defs do
      val sym =
        defn match
          case _: Ast.Def.ValDef =>
            Symbol.ValSymbol(defn.name)
          case funDef: Ast.Def.FunDef =>
            val tp = FunInfo(funDef.params.size.toByte, 1)
            Symbol.FunSymbol(defn.name, tp)

      sc.define(sym)

    val defs = for defn <- prog.defs yield transform(defn)(using sc)
    val main = transform(prog.main)(using sc)

    Prog(defs, main)

  private def transform(word: Ast.Word)(using sc: Scope): List[Word] =
    word match
      case Ast.Word.IntLit(v)  => Word.IntLit(v) :: Nil
      case Ast.Word.BoolLit(v) => Word.BoolLit(v) :: Nil
      case Ast.Word.Fence(ws)  => Word.Fence(transform(ws)) :: Nil

      case Ast.Word.IfStat(cond, thenp, elsep) =>
         Word.IfStat(transform(cond), transform(thenp), transform(elsep)) :: Nil

      case Ast.Word.Ident(name) =>
        sc.resolve(name) match
          case Some(sym) => Word.Ident(sym) :: Nil
          case None      => throw new Exception("Undefined identifier " + name)

  private def transform(words: List[Ast.Word])(using sc: Scope): List[Word] =
    words.flatMap(word => transform(word))

  private def transform(defn: Ast.Def)(using sc: Scope): Def =
    val Some(sym) = sc.resolve(defn.name): @unchecked
    defn match
      case valDef: Ast.Def.ValDef =>
        Def.ValDef(sym, transform(valDef.words))

      case funDef: Ast.Def.FunDef =>
        val funScope = new Scope.NestedScope(sc)
        val paramSyms =
          for param <- funDef.params yield
            val sym = Symbol.ValSymbol(param.name)
            funScope.define(sym)
            sym

        Def.FunDef(sym, paramSyms, transform(funDef.words)(using funScope))

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

    def define(sym: Symbol): Unit =
      map.get(sym.name) match
        case None =>
          map(sym.name) = sym

        case Some(sym) =>
          throw new Exception(sym.name + " is already bound")
