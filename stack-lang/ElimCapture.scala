import scala.collection.mutable

import Sast.*
import Symbols.*
import Types.*
import Positions.Span

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
  val ProcFieldName = "proc"

  def transform(nss: List[Namespace])(using Reporter): List[Namespace] =
    for ns <- nss yield transformNamespace(ns)

  def transformNamespace(ns: Namespace)(using Reporter): Namespace =
    given ctx: Context = new Context
    val defs =
      for defn <- ns.defs yield
        treeMap.recur(defn)

    // Enter names to name table of the namespace
    for fdef <- ctx.lifted do ns.info.define(fdef.symbol)

    Namespace(ns.symbol, ns.fullName, ns.imports, defs ++ ctx.lifted.toList)(ns.span)

  /** The encoded type of a function */
  def encodedRecordType(funType: FunctionType): RecordType =
    val paramInfos = funType.paramTypes.zipWithIndex.map: (tp, i) =>
      NamedInfo("p" + i, tp)
    val paramInfos2 = paramInfos :+ NamedInfo(EnvParamName, AnyType)
    val procType = ProcType(paramInfos2, funType.resultType, preParamCount = 0)
    val envType = AnyType
    val fieldProc = NamedInfo(ProcFieldName, procType)
    val fieldEnv = NamedInfo(EnvFieldName, envType)
    RecordType(List(fieldProc, fieldEnv))

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
  private def transitiveCapture(fun: Symbol)(using ctx: Context): List[Symbol] =
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
          if capture.is(Flags.Val) && capture.isLocal && !all.contains(capture)
        do
          all += capture

        for
          capture <- captures
          if capture.is(Flags.Fun) && capture.isLocal
        do
          recur(capture)
    end recur
    recur(fun)
    all.toList
  end transitiveCapture

  private def makeFunInfo(fdef: FunDef)(using ctx: Context): FunInfo =
    val oldFunSym = fdef.symbol
    val captures = transitiveCapture(fdef.symbol)
    // Cannot have same names in the captured symbol
    assert(
      captures.size == captures.map(_.name).toSet.size,
      "[Internal error] captured different variables with same name")

    val tparamInfos = fdef.tparams.map(tparam => NamedInfo(tparam.name, tparam.info.as[TypeBound]))
    val paramInfos = fdef.params.map(_.toNamedInfo)
    val resType = TypeOps.finalResultType(fdef.symbol.info)

    val envType = RecordType(captures.map(_.toNamedInfo))
    val paramInfos2 = paramInfos :+ NamedInfo(EnvParamName, envType)

    var funType: Type = ProcType(paramInfos2, resType, preParamCount = 0)
    if tparamInfos.nonEmpty then
      funType = PolyType(tparamInfos, funType)

    val funName = ctx.flatName(fdef.symbol)
    val funSym = Symbol.createFunSymbol(funName, funType, oldFunSym.enclosingNamespace, oldFunSym.sourcePos)
    FunInfo(funSym, captures)

  private def createEnvRecord(captures: List[Symbol], span: Span)(using Context): RecordLit =
    val fields =
      for capture <- captures
      yield capture.name -> Ident(capture)(span)

    val fieldTypes = captures.map(_.toNamedInfo)
    val envType = RecordType(fieldTypes)

    RecordLit(fields)(envType, span)

  /** The information about a function during the transformation
    *
    * @param funSym   the symbol for the function after transform
    * @param captures the transitive captures of the function
    */
  case class FunInfo(newFunSym: Symbol, captures: List[Symbol])

  class Context(
      val funInfos: Map[Symbol, FunInfo],       // rewiring of funs
      val rewiring: Map[Symbol, Symbol],        // rewiring of locals
      val captures: Map[Symbol, List[Symbol]],  // captured locals in a function
      val lifted: mutable.ArrayBuffer[FunDef],  // lifted funs
      val uniq: UniqueName
    ):


    def this() = this(Map.empty, Map.empty, Map.empty, new mutable.ArrayBuffer, new UniqueName)

    def withFun(fdef: FunDef): Context =
      new Context(funInfos, rewiring, captures.updated(fdef.symbol, fdef.captures), lifted, uniq)

    def withRewire(from: Symbol, to: Symbol): Context =
      new Context(funInfos, rewiring.updated(from, to), captures, lifted, uniq)

    def withFunInfo(fun: Symbol, info: FunInfo): Context =
      new Context(funInfos.updated(fun, info), rewiring, captures, lifted, uniq)

    def flatName(fun: Symbol): String =
      val name = fun.ownersIterator.foldLeft(fun.name): (acc, owner) =>
        if owner.isFunction then acc + "$" + owner.name else acc
      uniq.freshName(name)

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
    def lift(fdef: FunDef)(using ctx: Context): Word =
      val FunInfo(funSym, captures) = ctx.funInfos(fdef.symbol)

      val bodyItems = new mutable.ArrayBuffer[Word]
      val locals = mutable.ArrayBuffer.from(fdef.locals)

      val envType = RecordType(captures.map(_.toNamedInfo))
      val envSym = Symbol.createValueSymbol(EnvParamName, envType, funSym, fdef.symbol.sourcePos)

      var ctx2 = ctx
      for capture <- captures do
        val subst = Symbol.createValueSymbol(capture.name, capture.info, funSym, capture.sourcePos)
        locals += subst
        ctx2 = ctx2.withRewire(capture, subst)
        val rhs = Select(Ident(envSym)(fdef.span), capture.name)(capture.info, fdef.span)
        bodyItems += Assign(subst, rhs)(rhs.span)

      bodyItems += this(fdef.body)(using ctx2)
      val body = Phrase(bodyItems.toList)(fdef.body.tpe, fdef.body.span)
      val params = fdef.params :+ envSym
      ctx.lifted += FunDef(funSym, fdef.tparams, params, body)
                          (locals = locals.toList, captures = Nil, fdef.span)

      Phrase(words = Nil)(VoidType, fdef.span)

    def apply(word: Word)(using ctx: Context): Word = Debug.trace(word.show + ", ctx = " + ctx.show, (_: Word) => "", enable = false):
      word match
        case Apply(fun, args) =>
          val fun2 = this(fun)
          val args2 = args.map(this.apply)

          fun2 match
            case Encoded(RecordLit((ProcFieldName, proc) :: (EnvFieldName, env) :: Nil)) =>
              // local function call
              Apply(proc, args2 :+ env)(word.tpe, word.span)

            case _ =>
              if fun2.tpe.isFunctionType then
                // non-local function call
                val funType = fun2.tpe.asFunctionType
                val recordType = ElimCapture.encodedRecordType(funType)
                val closure = Encoded(fun2)(recordType)
                val env = Select(closure, EnvFieldName)(recordType.fieldType(EnvFieldName), fun2.span)
                val proc = Select(closure, ProcFieldName)(recordType.fieldType(ProcFieldName), fun2.span)
                Apply(proc, args2 :+ env)(word.tpe, word.span)
              else
                // global function call
                Apply(fun2, args2)(word.tpe, word.span)

        case Ident(sym) =>
          if sym.is(Flags.Fun) && sym.isLocal then
            // local function reference
            val FunInfo(subst, captures) = ctx.funInfos(sym)
            val funRef2 = Ident(subst)(word.span)
            val env = createEnvRecord(captures, word.span)
            val procField = NamedInfo(ProcFieldName, subst.info)
            val envField = NamedInfo(EnvFieldName, env.tpe)
            val recordType = RecordType(List(procField, envField))
            val closure = RecordLit(List(ProcFieldName -> funRef2, EnvFieldName -> env))(recordType, word.span)
            Encoded(closure)(sym.info)
          else
            Ident(ctx.rewiring.getOrElse(sym, sym))(word.span)

        case fdef: FunDef =>
          if fdef.symbol.isLocal then lift(fdef)
          else recur(fdef)

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
