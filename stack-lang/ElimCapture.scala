import scala.collection.mutable

import Sast.*
import Symbols.*
import Types.*
import Reporter.Span

/**
  * Eliminate captures of locals in functions
  *
  * Top-level functions are not transformed --- they do not capture locals.
  *
  * It depends on ExplicitInit to make captures of functions explicit.
  */
object ElimCapture:
  val treeMap = new ClosureTreeMap

  val EnvParamName = "$env"
  val EnvFieldName = "env"
  val FunFieldName = "fun"

  def transform(prog: Prog): Prog =
    given ctx: Context = new Context
    val defns =
      for defn <- prog.defs
      yield treeMap.recur(defn)(using ctx.withOwner(defn.symbol))

    val main = treeMap.apply(prog.main)
    Prog(defns ++ ctx.lifted.toList, main)

  /** Compute the transitive capture of locals
    *
    * e.g.
    *
    *    fun f(x: Int): Int = a g x +
    *
    *    fun g(x: Int): Int = b f x +
    *
    * In the above, the function `f` would capture `a` and `b`.
    *
    * TODO: cache to improve performance
    */
  def transitiveCapture(fun: Symbol)(using ctx: Context): List[Symbol] =
    val all = new mutable.ArrayBuffer[Symbol]
    val visited = new mutable.ArrayBuffer[Symbol]
    def recur(fun: Symbol): Unit = Debug.trace("fun = " + fun, enable = false):
      if !visited.contains(fun) then
        visited += fun
        val captures = ctx.captures(fun)

        // Global captures is also in the census, we only care about capture of
        // locals.
        for
          capture <- captures
          if capture.isAllOf(Flag.Local | Flag.Val) && !all.contains(capture)
        do
          all += capture

        for
          capture <- captures
          if capture.isAllOf(Flag.Local | Flag.Fun)
        do
          recur(capture)
    end recur
    recur(fun)
    all.toList
  end transitiveCapture

  def makeFunInfo(fdef: FunDef)(using ctx: Context): FunInfo =
    val captures = transitiveCapture(fdef.symbol)
    // Cannot have same names in the symbol --- they must be the same symbol

    val tparamBounds = fdef.tparams.map(_.info)
    val paramNames = fdef.params.map(_.name)
    val paramTypes = fdef.params.map(_.info)
    val resType = TypeOps.finalResultType(fdef.symbol.info)

    val envType = RecordType(captures.map(sym => sym.name -> sym.info))
    var funType: Type = ProcType(paramNames :+ EnvParamName, paramTypes :+ envType, resType)
    if tparamBounds.nonEmpty then
      funType = PolyType(fdef.tparams.map(_.name), tparamBounds, funType)

    val funName = ctx.flatName(fdef.symbol)
    val funSym = Symbol.createFunSymbol(funName, funType, fdef.symbol.sourcePos)
    FunInfo(funSym, captures)

  def createEnvRecord(fun: Symbol, captures: List[Symbol], span: Span)(using Context): RecordLit =
    val fields =
      for capture <- captures
      yield capture.name -> Ident(capture)(span)

    val fieldTypes =
      for capture <- captures
      yield capture.name -> capture.info

    val envType = RecordType(fieldTypes)

    RecordLit(fields)(envType, span)

  /** The information about a function during the transformation
    *
    * @param funSym   the symbol for the function after transform
    * @param captures the transitive captures of the function
    */
  case class FunInfo(funSym: Symbol, captures: List[Symbol])

  class Context(
      val owners: List[Symbol],                 // symbols of enclosing functions
      val funInfos: Map[Symbol, FunInfo],       // rewiring of funs and locals
      val rewiring: Map[Symbol, Symbol],        // rewiring of funs and locals
      val captures: Map[Symbol, List[Symbol]],  // captured locals in a function
      val lifted: mutable.ArrayBuffer[FunDef]   // lifted funs
    ):

    def this() = this(Nil, Map.empty, Map.empty, Map.empty, new mutable.ArrayBuffer)

    def withFun(fdef: FunDef): Context =
      new Context(owners, funInfos, rewiring, captures.updated(fdef.symbol, fdef.captures), lifted)

    def withRewire(from: Symbol, to: Symbol): Context =
      new Context(owners, funInfos, rewiring.updated(from, to), captures, lifted)

    def withFunInfo(fun: Symbol, info: FunInfo): Context =
      new Context(owners, funInfos.updated(fun, info), rewiring, captures, lifted)

    def withOwner(fun: Symbol): Context =
      new Context(fun :: owners, funInfos, rewiring, captures, lifted)

    def flatName(fun: Symbol): String =
      assert(owners.nonEmpty, fun.name)
      owners.foldLeft(fun.name) { (acc, owner) => owner.name + "$" + acc }

    def show: String =
      " { rewires: " + rewiring.toString + ", captures: " + captures + "}"
  end Context

  class ClosureTreeMap extends SastOps.TreeMap:
    type Context = ElimCapture.Context

    /**
      * Each local functions is transformed to a wrapper function and explicit
      * function:
      *
      *    fun f(x: Int): Int = x a + b +
      *
      * ==>
      *
      *    fun f_w(x: Int, env: { a: Int, b: Int }): Int =
      *      f_e(x, env.a, env.b)
      *
      *    fun f_e(x: Int, a: Int, b: Int): Int =
      *      x a + b +
      *
      * TODO:
      *
      * - named local functions do not need wrapper
      * - capture of type parameters (closure conversion after erasure?)
      */
    def transform(fdef: FunDef)(using ctx: Context): Word =
      val FunInfo(funSym, captures) = ctx.funInfos(fdef.symbol)

      val bodyItems = new mutable.ArrayBuffer[Word]
      val locals = mutable.ArrayBuffer.from(fdef.locals)

      val envType = RecordType(captures.map(sym => sym.name -> sym.info))
      val envSym = Symbol.createValueSymbol(EnvParamName, envType, Flag.Local, fdef.symbol.sourcePos)

      var ctx2 = ctx
      for capture <- captures do
        val subst = Symbol.createValueSymbol(capture.name, capture.info, Flag.Local, capture.sourcePos)
        locals += subst
        ctx2 = ctx2.withRewire(capture, subst)
        val rhs = Select(Ident(envSym)(fdef.span), capture.name)(capture.info, fdef.span)
        bodyItems += Assign(subst, rhs)(rhs.span)

      bodyItems += this(fdef.body)(using ctx2.withOwner(fdef.symbol))
      val body = Phrase(bodyItems.toList)(fdef.body.tpe, fdef.body.span)

      ctx.lifted += FunDef(funSym, fdef.tparams, fdef.params :+ envSym, body)
                          (locals = locals.toList, captures = Nil, fdef.span)

      Phrase(words = Nil)(VoidType, fdef.span)

    def apply(word: Word)(using ctx: Context): Word = Debug.trace(Printing.show(word) + ", ctx = " + ctx.show, (_: Word) => "", enable = false):
      word match
        case Ident(sym) =>
          if sym.isFunction then
            if sym.isLocal then
              val FunInfo(subst, captures) = ctx.funInfos(sym)
              val env = createEnvRecord(sym, captures, word.span)
              val fun = Ident(subst)(word.span)
              // TODO: handle param insertion properly after introducing call syntax
              Phrase(env :: fun :: Nil)(word.tpe, word.span)
            else
              word
          else
            Ident(ctx.rewiring.getOrElse(sym, sym))(word.span)

        case FunRef(sym) =>
          val FunInfo(subst, captures) = ctx.funInfos(sym)
          val env = createEnvRecord(sym, captures, word.span)
          val recordType = RecordType(List(FunFieldName -> word.tpe, EnvFieldName -> env.tpe))
          val funRef2 = FunRef(subst)(subst.info, word.span)
          val closure = RecordLit(List(FunFieldName -> funRef2, EnvFieldName -> env))(recordType, word.span)
          Encoded(closure)(word.tpe)

        case fdef: FunDef =>
          if ctx.owners.nonEmpty then transform(fdef)
          else recur(fdef)(using ctx.withOwner(fdef.symbol))

        case Phrase(words) =>
          var ctx2 = ctx

          for case fdef: FunDef <- words do
            ctx2 = ctx2.withFun(fdef)

          // Enter the rewire symbol of local functions for mutual recursion
          for
            case fdef: FunDef <- words
          do
            ctx2 = ctx2.withFunInfo(fdef.symbol, makeFunInfo(fdef)(using ctx2))

          val words2 =
            for word <- words yield this(word)(using ctx2)

          Phrase(words2)(word.tpe, word.span)

        case _ => recur(word)
