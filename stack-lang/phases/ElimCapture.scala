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
class ElimCapture(using Definitions) extends Phase[Symbol]:
  val contextObject = Phase.OwnerContext

  override def transformDefs(defs: List[Def])(using Context): List[Def] =
    val uniq = new UniqueName
    defs.flatMap:
      case fdef: FunDef =>
        val (fdef2, defs) = ElimCapture.transformFunDef(fdef, uniq)
        fdef2 :: defs

      case cdef: ClassDef =>
        val lifted = new mutable.ArrayBuffer[Def]
        val funs = new mutable.ArrayBuffer[FunDef]
        for fdef <- cdef.funs do
          val (fdef2, defs) = ElimCapture.transformFunDef(fdef, uniq)
          lifted ++= defs
          funs += fdef2

        cdef.copy(funs = funs.toList)(cdef.span) :: lifted.toList

      case defn => super.transformDef(defn) :: Nil

object ElimCapture:
  def transformFunDef(fdef: FunDef, uniq: UniqueName)(using Definitions): (FunDef, List[Def]) =
    given ctx: Context = new Context(uniq)
    val lifter = new Lifter(fdef.symbol)
    val body = lifter.apply(fdef.body)
    val lifted = ctx.lifted.toList
    (fdef.copy(body = body)(fdef.span), lifted)

  private def createLiftedFunSym(
    fdef: FunDef,
    prependParams: List[NamedInfo[Type]],
    appendParams: List[NamedInfo[Type]])(
    using ctx: Context, defn: Definitions): Symbol =

    val oldFunSym = fdef.symbol

    val paramInfos = fdef.params.map(_.toNamedInfo)
    val autoInfos = fdef.autos.map(_.toNamedInfo)
    val resType = fdef.procType.resultType
    val paramInfos2 = prependParams ++ paramInfos ++ appendParams
    val funType = ProcType(fdef.tparams, paramInfos2, autoInfos, resType, fdef.effectPolicy, preParamCount = 0)

    val funName = ctx.flatName(fdef.symbol)
    Symbol.createSymbol(funName, funType, Flags.Fun | Flags.Synthetic, oldFunSym.enclosingContainer, oldFunSym.sourcePos)

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

    def flatName(fun: Symbol)(using Definitions): String =
      val name = fun.ownersIterator.foldLeft(fun.name): (acc, owner) =>
        if owner.isFunction then acc + "$" + owner.name else acc
      uniq.freshName(name)

    def show: String =
      "{ rewires: " + rewiring.toString + "}"
  end Context

  /** A new instance is created for each top-level function */
  class Lifter(owner: Symbol)(using defn: Definitions) extends SastOps.TreeMap:
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
    override def transformLocalFunDef(fdef: FunDef)(using ctx: Context): Word =
      localDefs(fdef.symbol) = fdef

      val LiftInfo(funSym, captures) = ctx.liftInfos(fdef.symbol)

      val substs = mutable.Map.empty[Symbol, Symbol]
      val paramSymsCaptured =
        for capture <- captures yield
          val sym = Symbol.createSymbol(capture.name, capture.info, Flags.Synthetic, funSym, fdef.symbol.sourcePos)
          substs(capture) = sym
          sym

      val lifter = new Lifter(funSym)
      val body = lifter(fdef.body)(using ctx.withSubsts(substs.toMap))
      val params = fdef.params ++ paramSymsCaptured
      ctx.lifted += FunDef(funSym, fdef.tparams, params, fdef.autos, fdef.resultType, body)(fdef.span)

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
    override def transformObject(obj: Object)(using ctx: Context): Word =
      val objType = obj.tpe.asObjectType
      val allCaptures: List[Symbol] =
        obj.funs.foldLeft(List.empty[Symbol]): (acc, fdef) =>
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
      val lazyInfo = () =>
         val capturedMembers =
           for capture <- allCaptures
           yield NamedInfo(captureToField(capture), capture.info)

         ObjectType(objType.fields ++ capturedMembers.toList, objType.methods, objType.mutableFields)

      val thisTypeName = ctx.uniq.freshName("ThisType")
      val thisTypeAliasSym = new TypeSymbol(Kind.Simple, thisTypeName, Flags.Synthetic, obj.self.sourcePos)
      val thisType = StaticRef(thisTypeAliasSym)
      ctx.lifted += TypeDef(thisTypeAliasSym)(obj.span)

      defn.addLazy(thisTypeAliasSym, owner.enclosingContainer, lazyInfo)

      for vdef <- obj.vals do
        uniq.freshName(vdef.name)
        members += vdef.name -> this(vdef.rhs)
        memberTypes += NamedInfo(vdef.name, vdef.rhs.tpe)

      for fdef <- obj.funs do
        uniq.freshName(fdef.name)

        val liftedSym = createLiftedFunSym(fdef, prependParams = NamedInfo("this", thisType) :: Nil, appendParams = Nil)
        funToLifted(fdef.symbol) = liftedSym

        memberTypes += NamedInfo(fdef.name, StaticRef(liftedSym))

      for capture <- allCaptures yield
        val field = uniq.freshName(capture.name)
        captureToField(capture) = field
        members += field -> Ident(capture)(obj.span)
        memberTypes += NamedInfo(field, capture.info)

      for fdef <- obj.funs do
        val span = fdef.symbol.sourcePos.span
        val liftedSym = funToLifted(fdef.symbol)

        members += fdef.name -> Ident(liftedSym)(fdef.span)

        val paramThis = Symbol.createSymbol("this", thisType, Flags.Param, liftedSym, fdef.symbol.sourcePos)

        val substs = mutable.Map.empty[Symbol, Symbol]
        val aliases = new mutable.ArrayBuffer[Assign]
        for capture <- transitiveCapture(fdef) if capture != obj.self do
          // Rewiring is important -- the captured variable might have been rebound
          val capture2 = rewire(capture)
          val subst = Symbol.createSymbol(capture2.name, capture2.info, Flags.Synthetic, liftedSym, fdef.symbol.sourcePos)
          val lhs = Ident(subst)(span)
          val rhs = Select(Ident(paramThis)(span), captureToField(capture2))(capture2.info, span)
          aliases += Assign(lhs, rhs)(span)
          substs(capture2) = subst

        substs(obj.self) = paramThis

        val lifter = new Lifter(fdef.symbol)
        val body = lifter(fdef.body)(using ctx.withSubsts(substs.toMap))
        val body2 = Block(aliases.toList :+ body)(body.tpe, body.span)
        val params = paramThis :: fdef.params

        ctx.lifted += FunDef(liftedSym, fdef.tparams, params, fdef.autos, fdef.resultType, body2)(fdef.span)
      end for

      val recordType = RecordType(memberTypes.toList)
      Encoded(RecordLit(members.toList)(recordType, obj.span))(objType)

    override def transformApply(app: Apply)(using ctx: Context): Word =
      val Apply(fun, args, autos) = app

      val args2 = args.map(this.apply)
      val autos2 = autos.map(this.apply)

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

          Apply(funSubst, args2 ++ extraArgs, autos2)(app.tpe, app.span)

        case Select(qual, name) if qual.tpe.isObjectType =>
          val qual2 = this(qual)
          val procType = qual2.tpe.termMember(name).asProcType
          val liftedProcType = procType.prepend(NamedInfo("this", qual2.tpe) :: Nil)
          if qual2.isIdempotent then
            val proc = Select(qual2, name)(procType, fun.span)
            Apply(Encoded(proc)(liftedProcType), qual2 :: args2, autos2)(app.tpe, app.span)
          else
            given Positions.Source = owner.sourcePos.source
            val receiverSym = Symbol.createSymbol("o", qual2.tpe, Flags.Synthetic, owner, qual2.pos)
            val receiver = Ident(receiverSym)(qual2.span)
            val assign = Assign(Ident(receiverSym)(qual2.span), qual2)(qual2.span)
            val proc = Select(receiver, name)(procType, fun.span)
            val apply = Apply(Encoded(proc)(liftedProcType), receiver :: args2, autos2)(app.tpe, app.span)
            Block(assign :: apply :: Nil)(app.tpe, app.span)

        case TypeApply(Select(qual, name), targs) if qual.tpe.isObjectType =>
          // TODO: after type erasure, the special handling here can be removed
          val qual2 = this(qual)
          val funType = fun.tpe.asProcType
          val procType = qual2.tpe.termMember(name).asProcType
          val thisParamType = NamedInfo("this", qual2.tpe)
          val liftedFunType = funType.prepend(thisParamType :: Nil)
          val liftedProcType = procType.prepend(thisParamType :: Nil)
          if qual2.isIdempotent then
            val meth = Encoded(Select(qual2, name)(procType, fun.span))(liftedProcType)
            val fun2 = TypeApply(meth, targs)(liftedFunType, fun.span)
            Apply(fun2, qual2 :: args2, autos2)(app.tpe, app.span)
          else
            given Positions.Source = owner.sourcePos.source
            val receiverSym = Symbol.createSymbol("o", qual2.tpe, Flags.Synthetic, owner, qual2.pos)
            val receiver = Ident(receiverSym)(qual2.span)
            val assign = Assign(Ident(receiverSym)(qual2.span), qual2)(qual2.span)
            val meth = Encoded(Select(receiver, name)(procType, fun.span))(liftedProcType)
            val fun2 = TypeApply(meth, targs)(liftedFunType, fun.span)
            val apply = Apply(fun2, receiver :: args2, autos2)(app.tpe, app.span)
            Block(assign :: apply :: Nil)(app.tpe, app.span)

        case _ =>
         // global function call
         Apply(fun, args2, autos2)(app.tpe, app.span)


    override def transformValDef(vdef: ValDef)(using ctx: Context): Word =
      val ValDef(sym, rhs) = vdef
      Assign(Ident(sym)(sym.sourcePos.span), this(rhs))(vdef.span)

    override def transformBlock(block: Block)(using ctx: Context): Word =
      var ctx2 = ctx

      // Enter the lifting info in the context for transforming the block
      for
        case fdef: FunDef <- block.words
      do
        // Local functions are not mutually recursive
        localDefs(fdef.symbol) = fdef
        val liftInfo = makeLiftInfo(fdef)
        ctx2 = ctx2.withLiftInfo(fdef.symbol, liftInfo)

      super.transformBlock(block)(using ctx2)

    override def transformIdent(ident: Ident)(using ctx: Context): Word =
      val sym2 = rewire(ident.symbol)
      if sym2 != ident.symbol then Ident(sym2)(ident.span)
      else ident

    private def makeLiftInfo(fdef: FunDef)(using ctx: Context): LiftInfo =
      val captures = transitiveCapture(fdef).map(sym => rewire(sym))
      // Cannot have same names in the captured symbol
      //
      // TODO: This is possible via transitive capture.
      assert(
        captures.size == captures.map(_.name).toSet.size,
        "[Internal error] captured different variables with same name in " + fdef.symbol)

      val paramCaptures = captures.map(_.toNamedInfo)

      val funSym = createLiftedFunSym(fdef, prependParams = Nil, appendParams = paramCaptures)
      LiftInfo(funSym, captures)

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
            if !capture.is(Flags.Fun) && !all.contains(capture) then
              all += capture
            else if capture.is(Flags.Fun) then
              recur(this.localDefs(capture))
      end recur

      recur(fdef)
      val res = all.toList
      // cache result
      this.captures(fdef.symbol) = res
      res
    end transitiveCapture
