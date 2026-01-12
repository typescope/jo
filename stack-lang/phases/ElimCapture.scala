package phases

import common.Debug
import common.UniqueName

import sast.*
import sast.Trees.*
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
    defs.flatMap:
      case fdef: FunDef =>
        val (fdef2, defs) = ElimCapture.transformFunDef(fdef)
        fdef2 :: defs

      case cdef: ClassDef =>
        val lifted = new mutable.ArrayBuffer[Def]
        val funs = new mutable.ArrayBuffer[FunDef]
        for fdef <- cdef.funs do
          val (fdef2, defs) = ElimCapture.transformFunDef(fdef)
          lifted ++= defs
          funs += fdef2

        cdef.copy(funs = funs.toList)(cdef.span) :: lifted.toList

      case defn => super.transformDef(defn) :: Nil

object ElimCapture:
  def transformFunDef(fdef: FunDef)(using Definitions): (FunDef, List[Def]) =
    given ctx: Context = new Context()
    val lifter = new Lifter(fdef.symbol)
    val body = lifter.apply(fdef.body)
    val lifted = ctx.lifted.toList

    (fdef.copy(body = body)(fdef.span), lifted)

  def flatName(fun: Symbol): String =
    fun.ownersIterator.foldLeft(fun.name): (acc, owner) =>
      if !owner.isContainer then owner.name + "$" + acc else acc

  def createLiftedFunSym(
      fdef: FunDef, prependParams: List[NamedInfo[Type]], appendParams: List[NamedInfo[Type]])(
      using defn: Definitions)
  : Symbol =

    val oldFunSym = fdef.symbol

    val oldProcType = oldFunSym.info.as[ProcType]
    val paramInfos = prependParams ++ oldProcType.params ++ appendParams
    val funType = oldProcType.copy(params = paramInfos)

    val funName = flatName(fdef.symbol)
    TermSymbol.create(funName, funType, Flags.Fun | Flags.Synthetic, Visibility.Default, oldFunSym.enclosingContainer, oldFunSym.sourcePos)

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
  ):

    def this() =
      this(Map.empty, Map.empty, new mutable.ArrayBuffer)

    def withSubsts(substs: Map[Symbol, Symbol]): Context =
      new Context(liftInfos, rewiring ++ substs, lifted)

    def withLiftInfo(fun: Symbol, info: LiftInfo): Context =
      new Context(liftInfos.updated(fun, info), rewiring, lifted)

    def show: String =
      "{ rewires: " + rewiring.toString + "}"
  end Context

  /** A new instance is created for each top-level function */
  class Lifter(owner: Symbol)(using defn: Definitions) extends TreeMap:
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
      val LiftInfo(funSym, captures) = ctx.liftInfos(fdef.symbol)

      val substs = mutable.Map.empty[Symbol, Symbol]
      val paramSymsCaptured =
        for capture <- captures yield
          val sym = TermSymbol.create(capture.name, capture.info, Flags.Synthetic, Visibility.Default, funSym, fdef.symbol.sourcePos)
          substs(capture) = sym
          sym

      val lifter = new Lifter(funSym)
      val body = lifter(fdef.body)(using ctx.withSubsts(substs.toMap))
      val params = fdef.params ++ paramSymsCaptured
      ctx.lifted += FunDef(funSym, fdef.tparams, params, fdef.autos, fdef.candidates, fdef.resultType, fdef.effectPolicy, body)(fdef.span)

      Block(words = Nil)(fdef.span)

    /**
      * Each lambda is transformed from
      *
      *   (x_i: T_i) => body
      *
      * ==>
      *
      *   class LiftedLambda
      *     val a: T
      *
      *     def LiftedLambda(a: T) =
      *       this.a = a
      *
      *     def apply(x_i: T_i) = body
      *
      *   new LiftedLambda(a)
      *
      * where lambda is lifted to a top-level class and captured free variables
      * become fields.
      *
      *
      * TODO:
      *
      * - capture of type parameters (closure conversion after erasure?)
      */
    override def transformLambda(lam: Lambda)(using ctx: Context): Word =
      liftLambda(lam, None)

    override def transformEncoded(encoded: Encoded)(using ctx: Context): Word =
      encoded.repr match
        case lam: Lambda if encoded.tpe.isLambdaInterface =>
          liftLambda(lam, Some(encoded.tpe))

        case _ =>
          super.transformEncoded(encoded)

    private def liftLambda
        (lam: Lambda, lambdaInterfaceOpt: Option[Type])
        (using ctx: Context)
    : Word =

      val Lambda(lambdaSym, params, receives, body) = lam
      val lambdaType = lam.tpe.asLambdaType

      // Compute captured free variables (with transitive closure)
      val allCaptures = transitiveCaptureForLambda(lam).map(rewire).distinct

      // Create lifted class name
      val className = flatName(lambdaSym)
      val classPos = lambdaSym.sourcePos

      // Create class symbol
      val classSym = TypeSymbol.create(
        Kind.Simple,
        className,
        Flags.Synthetic | Flags.Class,
        Visibility.Default,
        owner.enclosingContainer,
        classPos
      )

      // Create field symbols for captured variables
      val fieldSyms = new mutable.ArrayBuffer[Symbol]
      val captureToField = mutable.Map.empty[Symbol, String]

      // Avoid duplicate names in captures
      val uniq = new UniqueName(separator = "")

      // Be the first to avoid name conflict
      val viewFieldOpt = lambdaInterfaceOpt.map: tp =>
        val classInfo = tp.asClassInfo
        TermSymbol.create(
          classInfo.classSymbol.name,
          tp,
          Flags.Field | Flags.View | Flags.Defer,
          Visibility.Default,
          classSym,
          classPos
        )

      for capture <- allCaptures do
        val fieldName = uniq.freshName(capture.name)
        captureToField(capture) = fieldName
        val fieldSym = TermSymbol.create(
          fieldName,
          capture.info,
          Flags.Field,
          Visibility.Default,
          classSym,
          classPos
        )
        fieldSyms += fieldSym

      // Create a self symbol for the class instance
      val selfSym = TermSymbol.create(
        "this",
        StaticRef(classSym),
        Flags.Synthetic,
        Visibility.Default,
        classSym,
        classPos
      )

      // Create constructor symbol
      val ctorSym = TermSymbol.create(
        Names.Constructor,
        Flags.Fun | Flags.Method,
        Visibility.Default,
        classSym,
        classPos
      )

      // Create the apply method symbol
      val applyName =
        lambdaInterfaceOpt match
          case Some(itype) =>
            itype.getLambdaInterfaceMethod match
              case Some(meth) => meth.name
              case None =>
                throw new Exception("Internal error: non lambda interface = " + itype.show)

          case None =>
            "apply"

      val applySym = TermSymbol.create(
        applyName,
        Flags.Method | Flags.Fun | Flags.Synthetic,
        Visibility.Default,
        classSym,
        classPos
      )

      // Create constructor parameters for captured variables
      val ctorParams = new mutable.ArrayBuffer[Symbol]
      for capture <- allCaptures do
        val ctorParam = TermSymbol.create(
          captureToField(capture),
          capture.info,
          Flags.Param,
          Visibility.Default,
          ctorSym,
          classPos
        )
        ctorParams += ctorParam

      defn.add(ctorSym, ProcType(
        tparams = Nil,
        params = ctorParams.map(_.toNamedInfo).toList,
        autos = Nil,
        candidates = Nil,
        resultType = StaticRef(classSym),
        receivesInfo = Nil,
        preParamCount = 0
      ))

      defn.add(applySym, ProcType(
        tparams = Nil,
        params = params.map(_.toNamedInfo),
        autos = Nil,
        candidates = Nil,
        resultType = lambdaType.resultType,
        receivesInfo = receives,
        preParamCount = 0
      ))

      // Register the ClassInfo with the method symbols
      defn.add(classSym, ClassInfo(
        classSym,
        tparams = Nil,
        targs = Nil,
        self = selfSym,
        fields = viewFieldOpt.toList ++ fieldSyms.toList,
        methods = ctorSym :: applySym :: Nil,
        directViews = Nil
      ))

      // Create constructor body: initialize all fields from parameters, then return this
      val ctorBody =
        val initializers = new mutable.ArrayBuffer[Word]
        for (fieldSym, ctorParam) <- fieldSyms.zip(ctorParams) do
          val lhs = Select(Ident(selfSym)(lam.span), fieldSym.name)(lam.span)
          val rhs = Ident(ctorParam)(lam.span)
          initializers += FieldAssign(lhs, rhs)

        viewFieldOpt match
          case Some(sym) =>
            val lhs = Select(Ident(selfSym)(lam.span), sym.name)(lam.span)
            val rhs = Ident(defn.Predef_triple_dot)(lam.span).appliedTo()
            initializers += FieldAssign(lhs, rhs)

          case None =>

        // Return this at the end
        initializers += Ident(selfSym)(lam.span)
        Block(initializers.toList)(lam.span)

      // Create the constructor definition
      val ctorDef = FunDef(
        ctorSym,
        tparams = Nil,
        params = ctorParams.toList,
        autos = Nil,
        candidates = Nil,
        resultType = TypeTree(StaticRef(classSym))(lam.span),
        effectPolicy = Effects.Policy.CheckBound(Nil),
        body = ctorBody
      )(lam.span)

      // Create the apply method body with substitutions
      val substs = mutable.Map.empty[Symbol, Symbol]
      val aliases = new mutable.ArrayBuffer[Assign]

      // Substitute captured variables with field selections in the body
      for capture <- allCaptures do
        val subst = TermSymbol.create(
          capture.name,
          capture.info,
          Flags.Synthetic,
          Visibility.Default,
          applySym,
          classPos
        )
        val lhs = Ident(subst)(lam.span)
        val rhs = Select(Ident(selfSym)(lam.span), captureToField(capture))(lam.span)
        aliases += Assign(lhs, rhs)
        substs(capture) = subst

      // Transform the body with substitutions
      val lifter = new Lifter(applySym)
      val body2 = lifter(body)(using ctx.withSubsts(substs.toMap))
      val body3 = if aliases.isEmpty then body2 else Block(aliases.toList :+ body2)(body2.span)

      // Create the apply method
      val applyDef = FunDef(
        applySym,
        tparams = Nil,
        params = params,
        autos = Nil,
        candidates = Nil,
        resultType = TypeTree(lambdaType.resultType)(lam.span),
        effectPolicy = Effects.Policy.CheckBound(receives),
        body = body3
      )(lam.span)

      // Create the lifted class
      val classDef = ClassDef(
        classSym,
        selfSym,
        tparams = Nil,
        vals = fieldSyms.toList,
        funs = ctorDef :: applyDef :: Nil,
        directViews = Nil
      )(lam.span)

      ctx.lifted += classDef

      // Create the instantiation: new LiftedLambda(captured_values)
      val captureArgs = allCaptures.map(capture => Ident(capture)(lam.span))
      val classType = StaticRef(classSym)
      val newInstance = New(TypeTree(classType)(lam.span))(lam.span)
      val ctorSelect = Select(newInstance, Names.Constructor)(lam.span)
      val instantiation = Apply(ctorSelect, captureArgs, Nil)(lam.span)

      viewFieldOpt match
        case Some(sym) =>
          // interface encoding now fully implemented
          instantiation.select(sym.name)

        case None =>
          // Encode the class instance as having the lambda type
          Encoded(instantiation)(lambdaType)

    /**
      * Each object is transformed from
      *
      *   { var x = e;  fun f(x: Int): Int = ...; }
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
      * TODO:
      *
      * - capture of type parameters (closure conversion after erasure?)
      */
    override def transformApply(app: Apply)(using ctx: Context): Word =
      val Apply(fun, args, autos) = app

      val args2 = args.map(this.apply)
      val autos2 = autos.map(this.apply)

      // TODO: do we really need to translate object method apply?
      // It seems simpler and more flexible to leave it to backend.
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

          Apply(funSubst, args2 ++ extraArgs, autos2)(app.span)

        case _ =>
          // global function call or class method call
          Apply(this(fun), args2, autos2)(app.span)

    override def transformValDef(vdef: ValDef)(using ctx: Context): Word =
      val ValDef(sym, rhs) = vdef
      Assign(Ident(sym)(sym.sourcePos.span), this(rhs))

    override def transformBlock(block: Block)(using ctx: Context): Word =
      var ctx2 = ctx

      // Enter the lifting info in the context for transforming the block
      for
        case fdef: FunDef <- block.words
      do
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

    /** Compute the transitive closure of captures from initial free references
      *
      * Processes local function captures recursively to collect all transitively
      * referenced local variables.
      */
    private def computeTransitiveCapture(initialCaptures: List[Symbol]): List[Symbol] =
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
              recur(defn.getCode(capture))
      end recur

      // Process initial captures
      for
        capture <- initialCaptures if capture.isLocal
      do
        if !capture.is(Flags.Fun) && !all.contains(capture) then
          all += capture
        else if capture.is(Flags.Fun) then
          recur(defn.getCode(capture))

      all.toList
    end computeTransitiveCapture

    /** Compute the transitive capture of locals for a lambda
      *
      * e.g.
      *
      *    val lam = (x: Int) => g a
      *
      *    fun g(x: Int): Int = b + 1
      *
      * In the above, the lambda would capture `a` and `b`.
      */
    private def transitiveCaptureForLambda(lam: Lambda): List[Symbol] =
      computeTransitiveCapture(TreeOps.freeReferences(lam))

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
      this.captures.get(fdef.symbol) match
        case Some(res) => res
        case None =>
          val res = computeTransitiveCapture(fdef.freeVariables)
          // cache result
          this.captures(fdef.symbol) = res
          res
