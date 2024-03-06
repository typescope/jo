import scala.collection.mutable

/**
  * The namer handles name resolution and desugaring.
  *
  * It converts ASTs to Semantic ASTs.
  */
object Namer:
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
            Sast.createValSymbol(defn.name)
          case _: Ast.Def.FunDef =>
            Sast.createFunSymbol(defn.name)

      sc.define(sym)

    val defs = for defn <- prog.defs yield transform(defn)(using sc)
    val main = transform(prog.main)(using sc)

    Sast.Prog(defs, main)

  private def transform(word: Ast.Word)(using sc: Scope): List[Sast.Word] =
    word match
      case Ast.Word.IntLit(v)  => Sast.Word.IntLit(v) :: Nil
      case Ast.Word.BoolLit(v) => Sast.Word.BoolLit(v) :: Nil
      case Ast.Word.Proc(ws)   => Sast.Word.Proc(transform(ws)) :: Nil

      case Ast.Word.IfStat(cond, thenp, elsep) =>
        // if w1 then w2 else w3 fi   ==>    w1 {w2} {w3} choose !

        transform(cond)
        :+ Sast.Word.Proc(transform(thenp))
        :+ Sast.Word.Proc(transform(elsep))
        :+ Sast.Word.Ident(Sast.predef.choose)
        :+ Sast.Word.Ident(Sast.predef.run)


      case Ast.Word.Ident(name) =>
        sc.resolve(name) match
          case Some(sym) => Sast.Word.Ident(sym) :: Nil
          case None      => throw new Exception("Undefined identifier " + name)

  private def transform(words: List[Ast.Word])(using sc: Scope): List[Sast.Word] =
    words.flatMap(word => transform(word))

  private def transform(defn: Ast.Def)(using sc: Scope): Sast.Def =
    val Some(sym) = sc.resolve(defn.name): @unchecked
    defn match
      case valDef: Ast.Def.ValDef =>
        Sast.Def.ValDef(sym, transform(valDef.words))

      case funDef: Ast.Def.FunDef =>
        Sast.Def.FunDef(sym, transform(funDef.words))

  private enum Scope:
    case RootScope()
    case NestedScope(outer: Scope)

    private val map: mutable.Map[String, Sast.Symbol] = mutable.Map.empty

    def resolve(name: String): Option[Sast.Symbol] =
      map.get(name) match
        case None =>
          this match
            case NestedScope(outer) => outer.resolve(name)
            case _ => None

        case res  => res

    def define(sym: Sast.Symbol): Unit =
      map.get(sym.name) match
        case None =>
          map(sym.name) = sym

        case Some(sym) =>
          throw new Exception(sym.name + " is already bound")
