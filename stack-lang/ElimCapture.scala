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

  def transform(prog: Prog): Prog =
    given Context = new Context
    val defns =
      for defn <- prog.defs
      yield treeMap.recur(defn)

    val main = treeMap.apply(prog.main)
    Prog(defns, main)

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
    def recur(fun: Symbol): Unit =
      if !visited.contains(fun) then
        visited += fun
        val captures = ctx.captures(fun)

        // Global captures is also in the census, we only care about capture of
        // locals.
        for
          capture <- captures
          if capture.isLocal && capture.isValue && !all.contains(capture)
        do
          all += capture

        for
          capture <- captures
          if capture.isLocal && capture.isFunction
        do
          recur(capture)
    end recur
    recur(fun)
    all.toList
  end transitiveCapture

  def makeWrapperSymbol(fdef: FunDef)(using Context): Symbol =
    val captures = transitiveCapture(fdef.symbol)
    // Cannot have same names in the symbol --- they must be the same symbol

    val tparamBounds = fdef.tparams.map(_.info)
    val paramNames = fdef.params.map(_.name)
    val paramTypes = fdef.params.map(_.info)
    val envType = RecordType(captures.map(sym => sym.name -> sym.info))
    val resType = TypeOps.finalResultType(fdef.symbol.info)

    var funType: Type = ProcType(paramNames :+ "env", paramTypes :+ envType, resType)
    if tparamBounds.nonEmpty then
      funType = PolyType(fdef.tparams.map(_.name), tparamBounds, funType)

    Symbol.createFunSymbol(fdef.name + "$cc", funType, fdef.symbol.sourcePos)

  def createEnvRecord(fun: Symbol, span: Span)(using Context): RecordLit =
    val captures = transitiveCapture(fun)
    // create env record
    val fields =
      for capture <- captures
      yield capture.name -> Ident(capture)(span)

    val fieldTypes =
      for capture <- captures
      yield capture.name -> capture.info

    val envType = RecordType(fieldTypes)

    RecordLit(fields)(envType, span)

  class Context(
      val rewiring: Map[Symbol, Symbol],        // rewiring of funs and locals
      val captures: Map[Symbol, List[Symbol]]   // captured locals in a function
    ):

    def this() = this(Map.empty, Map.empty)

    def withFun(fdef: FunDef): Context =
      new Context(rewiring, captures.updated(fdef.symbol, fdef.captures))

    def withRewire(from: Symbol, to: Symbol): Context =
      new Context(rewiring.updated(from, to), captures)
  end Context

  class ClosureTreeMap extends SastOps.TreeMap:
    type Context = ElimCapture.Context

    def transform(vdef: ValDef)(using Context): ValDef = vdef

    def transform(tdef: TypeDef)(using Context): TypeDef = tdef

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
    def transform(fdef: FunDef)(using ctx: Context): FunDef =
      val funSym = ctx.rewiring(fdef.symbol)

      val bodyItems = new mutable.ArrayBuffer[Word]
      val locals = mutable.ArrayBuffer.from(fdef.locals)
      var ctx2 = ctx
      for capture <- fdef.captures do
        val subst = Symbol.createValueSymbol(capture.name, capture.info, Flag.Local, capture.sourcePos)
        locals += subst
        ctx2 = ctx2.withRewire(capture, subst)
        val rhs = Ident(capture)(fdef.span)
        bodyItems += Assign(subst, rhs)(rhs.span)

      bodyItems += this(fdef.body)(using ctx2)
      val body = Phrase(bodyItems.toList)(fdef.body.tpe, fdef.body.span)
      FunDef(funSym, fdef.tparams, fdef.params, body)(locals = locals.toList, captures = Nil, fdef.span)

    def apply(word: Word)(using ctx: Context): Word =
      word match
        case Ident(sym) =>
          if sym.isFunction then
            if sym.isLocal then
              val env = createEnvRecord(sym, word.span)
              val subst = ctx.rewiring(sym)
              val fun = Ident(subst)(word.span)
              // TODO: handle param insertion properly after introducing call syntax
              Phrase(env :: fun :: Nil)(word.tpe, word.span)
            else
              word
          else
            Ident(ctx.rewiring.getOrElse(sym, sym))(word.span)

        case FunRef(sym) =>
          val env = createEnvRecord(sym, word.span)
          val recordType = RecordType(List("fun" -> word.tpe, "env" -> env.tpe))
          val subst = ctx.rewiring(sym)
          val funRef2 = FunRef(subst)(subst.info, word.span)
          val closure = RecordLit(List("fun" -> funRef2, "env" -> env))(recordType, word.span)
          Encoded(closure)(word.tpe)

        case fdef: FunDef =>
          transform(fdef)

        case Phrase(words) =>
          var ctx2 = ctx

          for case fdef: FunDef <- words do
            ctx2 = ctx2.withFun(fdef)

          // Enter the rewire symbol of local functions for mutual recursion
          for
            case fdef: FunDef <- words
          do
            ctx2 = ctx2.withRewire(fdef.symbol, makeWrapperSymbol(fdef))

          val words2 =
            for word <- words yield this(word)(using ctx2)


          Phrase(words2)(word.tpe, word.span)

        case _ => recur(word)
