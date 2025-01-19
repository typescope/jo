package phases

import common.Debug
import common.UniqueName

import ast.Positions
import sast.*
import sast.Sast.*
import sast.Symbols.*
import sast.Types.*

import scala.collection.mutable

/**
  * Eliminate capture of local values in objects and local functions
  *
  * Top-level functions are not transformed --- they do not capture locals.
  */
object ElimCapture:
  def transform(nss: List[Namespace]): List[Namespace] =
    for ns <- nss yield transformNamespace(ns)

  def transformNamespace(ns: Namespace): Namespace =
    val uniq = new UniqueName
    val defs =
      ns.defs.flatMap:
        case fdef: FunDef => transformFunDef(fdef, uniq)
        case defn => defn :: Nil

    Namespace(ns.symbol, ns.imports, defs)(ns.span)

  def transformFunDef(fdef: FunDef, uniq: UniqueName): List[Def] =
    given ctx: Context = new Context(uniq)
    val lifter = new Lifter(fdef.symbol)
    val body = lifter.apply(fdef.body)
    val lifted = ctx.lifted.toList
    val locals = lifter.locals.toList
    fdef.copy(body = body)(locals, fdef.span) :: lifted

  private def createLiftedFunSym(
    fdef: FunDef,
    prependParams: List[NamedInfo[Type]],
    appendParams: List[NamedInfo[Type]])(
    using ctx: Context): Symbol =

    val oldFunSym = fdef.symbol

    val tparamInfos = fdef.tparams.map(tparam => NamedInfo(tparam.name, tparam.info.as[TypeBound]))
    val paramInfos = fdef.params.map(_.toNamedInfo)
    val resType = TypeOps.finalResultType(fdef.symbol.info)

    val paramInfos2 = prependParams ++ paramInfos ++ appendParams

    var funType: Type = ProcType(paramInfos2, resType, preParamCount = 0)
    if tparamInfos.nonEmpty then
      funType = PolyType(tparamInfos, funType)

    val funName = ctx.flatName(fdef.symbol)
    Symbol.createFunSymbol(funName, funType, oldFunSym.enclosingNamespace, oldFunSym.sourcePos)

  /** The information for a lifted function
    *
    * @param funSym   the symbol for the function after transform
    * @param captures the transitive captures of the function
    */
  case class LiftInfo(newFunSym: Symbol, captures: List[Symbol])

  class Context(
    val liftInfos: Map[Symbol, LiftInfo],     // info for lifted functions
    val rewiring: Map[Symbol, Symbol],        // rewiring of locals
    val lifted: mutable.ArrayBuffer[Def],     // lifted definitions
    val uniq: UniqueName):

    def this(uniq: UniqueName) =
      this(Map.empty, Map.empty, new mutable.ArrayBuffer, uniq)

    def withSubsts(substs: Map[Symbol, Symbol]): Context =
      new Context(liftInfos, rewiring ++ substs, lifted, uniq)

    def withLiftInfo(fun: Symbol, info: LiftInfo): Context =
      new Context(liftInfos.updated(fun, info), rewiring, lifted, uniq)

    def flatName(fun: Symbol): String =
      val name = fun.ownersIterator.foldLeft(fun.name): (acc, owner) =>
        if owner.isFunction then acc + "$" + owner.name else acc
      uniq.freshName(name)

    def show: String =
      "{ rewires: " + rewiring.toString + "}"
  end Context

  /** A new instance is created for each top-level function */
  class Lifter(owner: Symbol) extends SastOps.TreeMap:
    val locals = new mutable.ArrayBuffer[Symbol]

    /** Local function definitions */
    val localDefs = mutable.Map.empty[Symbol, FunDef]

    /** Transitively captured locals in a function */
    val captures = mutable.Map.empty[Symbol, List[Symbol]]

    type Context = ElimCapture.Context

    def rewire(sym: Symbol)(using ctx: Context): Symbol =
      ctx.rewiring.get(sym) match
        case Some(sym) => rewire(sym)
        case None => sym

    /**
      * Each local functions is transformed from
      *
      *    fun f(x: Int): Int = x a + b +
      *
      * ==>
      *
      *    fun f_e(x: Int, a: Int, b: Int): Int =
      *      x a + b +
      *
      * TODO:
      *
      * - capture of type parameters (closure conversion after erasure?)
      */
    def liftLocalFunction(fdef: FunDef)(using ctx: Context): Word =
      val LiftInfo(funSym, captures) = ctx.liftInfos(fdef.symbol)

      val substs = mutable.Map.empty[Symbol, Symbol]
      val paramSymsCaptured =
        for capture <- captures yield
          val sym = Symbol.createValueSymbol(capture.name, capture.info, funSym, fdef.symbol.sourcePos)
          substs(capture) = sym
          sym

      val lifter = new Lifter(funSym)
      val body = lifter(fdef.body)(using ctx.withSubsts(substs.toMap))
      val params = fdef.params ++ paramSymsCaptured
      ctx.lifted += FunDef(funSym, fdef.tparams, params, body)
                          (locals = lifter.locals.toList, fdef.span)

      Block(words = Nil)(VoidType, fdef.span)

    /**
      * Each object is transformed from
      *
      *   object { var x = e;  fun f(x: Int): Int = ...; }
      *
      * ==>
      *
      *   { x = e, f = Ident(f_global), a = a }
      *
      * where the methods are lifted to top-level and captured free variables
      * become fields.
      *
      * The lifted methods take an additional parameter for `this` --- it has
      * an object type.
      *
      * At method call sites, we still have the invariant that the receiver has
      * an object type. We augment call with the receiver as an argument.
      *
      * We also adapt the type of `fun` to agree with arguments to satisfy the
      * invariant of Apply nodes.
      *
      * TODO:
      *
      * - capture of type parameters (closure conversion after erasure?)
      */
    def liftObject(obj: Object)(using ctx: Context): Word =
      val objType = obj.tpe.asObjectType
      val allCaptures: List[Symbol] =
        obj.defs.foldLeft(List.empty[Symbol]): (acc, fdef) =>
          transitiveCapture(fdef).foldLeft(acc): (acc, sym) =>
            // rewiring is important -- the captured variable might have been rebound
            val sym1 = rewire(sym)
            if acc.contains(sym1) || sym1 == obj.self then acc
            else sym1 :: acc

      // Avoid duplicate names in records/objects
      val uniq = new UniqueName

      val members = new mutable.ArrayBuffer[(String, Word)]
      val memberTypes = new mutable.ArrayBuffer[NamedInfo[Type]]
      val funToLifted = mutable.Map.empty[Symbol, Symbol]
      val captureToField = mutable.Map.empty[Symbol, String]

      // The type of the desugared record is delayed
      val infoProvider: InfoProvider = sym =>
         val capturedMembers =
           for capture <- allCaptures
           yield NamedInfo(captureToField(capture), capture.info)

         ObjectType(objType.members ++ capturedMembers.toList, objType.mutableFields)

      val thisTypeName = ctx.uniq.freshName("ThisType")
      val thisTypeAliasSym = Symbol.createTypeSymbol(thisTypeName, infoProvider, owner.enclosingNamespace, obj.self.sourcePos)
      val thisType = TypeRef(thisTypeAliasSym)
      ctx.lifted += TypeDef(thisTypeAliasSym)(obj.span)

      for vdef <- obj.vals do
        uniq.freshName(vdef.name)
        members += vdef.name -> this(vdef.rhs)
        memberTypes += NamedInfo(vdef.name, vdef.rhs.tpe)

      for fdef <- obj.defs do
        uniq.freshName(fdef.name)

        val liftedSym = createLiftedFunSym(fdef, prependParams = NamedInfo("this", thisType) :: Nil, appendParams = Nil)
        funToLifted(fdef.symbol) = liftedSym

        memberTypes += NamedInfo(fdef.name, TypeRef(liftedSym))

      for capture <- allCaptures yield
        val field = uniq.freshName(capture.name)
        captureToField(capture) = field
        members += field -> Ident(capture)(obj.span)
        memberTypes += NamedInfo(field, capture.info)

      for fdef <- obj.defs do
        val span = fdef.symbol.sourcePos.span
        val liftedSym = funToLifted(fdef.symbol)

        members += fdef.name -> Ident(liftedSym)(fdef.span)

        val paramThis = Symbol.createParamSymbol("this", thisType, liftedSym, fdef.symbol.sourcePos)

        val substs = mutable.Map.empty[Symbol, Symbol]
        val aliases = new mutable.ArrayBuffer[Assign]
        for capture <- transitiveCapture(fdef) if capture != obj.self do
          // Rewiring is important -- the captured variable might have been rebound
          val capture2 = rewire(capture)
          val subst = Symbol.createValueSymbol(capture2.name, capture2.info, liftedSym, fdef.symbol.sourcePos)
          val lhs = Ident(subst)(span)
          println("captureToField = " + captureToField + ", capture2 = " + capture2)
          val rhs = Select(Ident(paramThis)(span), captureToField(capture2))(capture2.info, span)
          aliases += Assign(lhs, rhs)(span)
          substs(capture2) = subst

        substs(obj.self) = paramThis

        val lifter = new Lifter(fdef.symbol)
        val body = lifter(fdef.body)(using ctx.withSubsts(substs.toMap))
        val body2 = Block(aliases.toList :+ body)(body.tpe, body.span)
        val params = paramThis :: fdef.params

        ctx.lifted += FunDef(liftedSym, fdef.tparams, params, body2)
                            (locals = lifter.locals.toList, fdef.span)


      val externalMembers = memberTypes.filter(m => objType.hasTermMember(m.name))
      val externalType = RecordType(externalMembers.toList)
      Encoded(RecordLit(members.toList)(externalType, obj.span))(objType)

    def transform(app: Apply)(using ctx: Context): Word =
      val Apply(fun, args) = app

      val args2 = args.map(this.apply)

      fun match
        case Ident(sym) if sym.is(Flags.Fun) && sym.isLocal =>
          // local function call
          val LiftInfo(subst, captures) = ctx.liftInfos(sym)
          val funSubst = Ident(subst)(app.span)
          val extraArgs =
            for
              capture <- captures
            yield
              // the captured sym needs substitution in recursive functions
              val sym = rewire(capture)
              Ident(sym)(app.span)

          Apply(funSubst, args2 ++ extraArgs)(app.tpe, app.span)

        case Select(qual, name) =>
          assert(qual.tpe.isObjectType, "Expect object type, found = " + qual.tpe.show)

          val qual2 = this(qual)
          val procType = qual2.tpe.termMember(name).asProcType
          val liftedProcType = procType.prepend(NamedInfo("this", qual2.tpe) :: Nil)
          if qual2.isIdempotent then
            val proc = Select(qual2, name)(procType, fun.span)
            Apply(Encoded(proc)(liftedProcType), qual2 :: args2)(app.tpe, app.span)
          else
            given Positions.Source = owner.sourcePos.source
            val receiverSym = Symbol.createValueSymbol("o", qual2.tpe, owner, qual2.pos)
            val receiver = Ident(receiverSym)(qual2.span)
            this.locals += receiverSym
            val assign = Assign(Ident(receiverSym)(qual2.span), qual2)(qual2.span)
            val proc = Select(receiver, name)(procType, fun.span)
            val apply = Apply(Encoded(proc)(liftedProcType), receiver :: args2)(app.tpe, app.span)
            Block(assign :: apply :: Nil)(app.tpe, app.span)

        case _ =>
         // global function call
         Apply(fun, args2)(app.tpe, app.span)

    def apply(word: Word)(using ctx: Context): Word = Debug.trace(word.show + ", ctx = " + ctx.show, (_: Word).show, enable = false):
      word match
        case Ident(sym) =>
          val sym2 = rewire(sym)
          if sym2 != sym then Ident(sym2)(word.span)
          else word

        case apply: Apply =>
          transform(apply)

        case ValDef(sym, rhs) =>
          this.locals += sym
          Assign(Ident(sym)(sym.sourcePos.span), this(rhs))(word.span)

        case fdef: FunDef =>
          localDefs(fdef.symbol) = fdef

          liftLocalFunction(fdef)

        case obj: Object =>
          liftObject(obj)

        case Block(words) =>
          var ctx2 = ctx

          // Enter the lifting info in the context for transforming the block
          for
            case fdef: FunDef <- words
          do
            // Local functions are not mutually recursive
            localDefs(fdef.symbol) = fdef
            val liftInfo = makeLiftInfo(fdef)
            ctx2 = ctx2.withLiftInfo(fdef.symbol, liftInfo)

          recur(word)(using ctx2)

        case _ => recur(word)
    end apply

    /** Compute the transitive capture of locals
      *
      * e.g.
      *
      *    fun f(x: Int): Int = a g x +
      *
      *    fun g(x: Int): Int = b f x +
      *
      * In the above, the function `f` would capture `a` and `b`.
      */
    private def transitiveCapture(fdef: FunDef): List[Symbol] =
      val all = new mutable.ArrayBuffer[Symbol]
      val visited = new mutable.ArrayBuffer[Symbol]

      def recur(fdef: FunDef): Unit = Debug.trace("fun = " + fdef.symbol, enable = false):
        if !visited.contains(fdef.symbol) then
          visited += fdef.symbol
          val captures = this.captures.get(fdef.symbol) match
            case Some(res) => res
            case None => fdef.freeVariables

          for
            capture <- captures if capture.isLocal
          do
            // Global captures is also in the census, only care about locals.
            if capture.is(Flags.Val) && !all.contains(capture) then
              all += capture
            else if capture.is(Flags.Fun) then
              recur(localDefs(capture))
      end recur

      recur(fdef)
      val res = all.toList
      // cache result
      this.captures(fdef.symbol) = res
      res
    end transitiveCapture

    private def makeLiftInfo(fdef: FunDef)(using ctx: Context): LiftInfo =
      val captures = transitiveCapture(fdef).map(sym => rewire(sym))
      // Cannot have same names in the captured symbol
      //
      // TODO: This is possible via transitive capture.
      assert(
        captures.size == captures.map(_.name).toSet.size,
        "[Internal error] captured different variables with same name")

      val paramCaptures = captures.map(_.toNamedInfo)

      val funSym = createLiftedFunSym(fdef, prependParams = Nil, appendParams = paramCaptures)
      LiftInfo(funSym, captures)
