package phases

import sast.*
import sast.Symbols.*
import sast.Trees.*
import sast.Types.*
import sast.Denotations.*

import scala.collection.mutable

/** Erase type parameters and make boxing/unboxing of primitive values explicit
  *
  * Optional: Add bridge methods to classes for boxing mismatch of abstract interface methods.
  *
  * @param isTagged whether values of a type are tagged for the target platform
  * @param bottomErasedTo what `Bottom` itself erases to. Defaults to `BottomType`
  * (i.e. left alone, the historical behavior every non-JVM backend still
  * relies on): `Subtyping.conforms(Bottom, _)` is unconditionally true, so
  * `adapt` never sees a reason to wrap a `Bottom`-typed value in `Encoded`,
  * regardless of `isTagged`. The JVM backend instead passes `AnyType` — the
  * same target a genuinely unresolved type parameter erases to just above —
  * which makes a `Bottom`-typed value participate in the ordinary
  * cast/unbox-at-use-site scheme (`adapt`'s `Encoded(value)(expectedType)`
  * fallback, "backend will decide whether the cast involves unboxing") that
  * already exists for any other Any-erased value. That distinction matters
  * because `Bottom`-typed *ordinary calls* (e.g. `abort(...)`, a plain
  * `invokestatic`) are opaque to the JVM verifier, which still expects the
  * call's declared return representation to be reconciled with the position
  * where the value is used.
  * @param bridgeRepresentationMatches whether two erased types (an
  * interface method's and the implementing natural method's, per
  * parameter/result) count as the *same representation* for bridge
  * purposes — as opposed to merely `Subtyping.conforms`-compatible with
  * the same tagging category, which `makeBridge`'s `taggingConforms` check
  * alone allows. Defaults to `(_, _) => true` (native's original, still
  * unchanged behavior: no extra restriction beyond tagging/conformance).
  * `taggingConforms` only asks "is this tagged/boxed at all," not "is it
  * *this* reference type" — so e.g. a natural method returning `String`
  * against an interface method erased to `Any` looks tagging-compatible,
  * and `String <: Any` conforms, so no bridge gets synthesized by default.
  * That's fine for a backend whose calling convention treats all reference
  * values uniformly regardless of declared type. The JVM backend's
  * `invokeinterface` dispatch instead needs the *exact* descriptor
  * (`Ljava/lang/Object;`, not `Ljava/lang/String;`) to exist on the class,
  * so it compares the backend's name-independent JVM representations, not
  * an approximation reproduced here: `Erasure`'s own erased types alone
  * aren't a reliable proxy for
  * "same JVM representation" (e.g. a plain type alias like `type Num = Int
  * | String` is left as `StaticRef(Num)` by `EraseTypeMap` — only `Bottom`
  * gets special dealiasing treatment there — while an interface's
  * uninstantiated type parameter erases to `AnyType`; those look like
  * different `Erasure`-level types even though `jvmType` maps both to the
  * identical `Ref(ObjectDesc)`, since it fully dealiases via `.approx`).
  */
class Erasure(
  isTagged: Type => Boolean, bottomErasedTo: Type = BottomType,
  bridgeRepresentationMatches: (Type, Type) => Boolean = (_, _) => true
)(using defn: Definitions) extends Phase:
  private val allPrimitivesTagged = isTagged `eq` Erasure.allTagged

  // Bridge-detection results, the erased-type map, and the pre-erasure
  // `Definitions` snapshot all live as plain instance fields here, not
  // `Phase.PhaseKey`s (contrast `Erasure.labelResultTypes`, kept as one
  // below since it's genuinely only ever read within this phase's own
  // single tree walk). `Phase.transform` creates a *fresh* `Context` per
  // phase invocation, so anything routed through a `PhaseKey` is invisible
  // across phase boundaries. The bridge capability exported below needs to
  // call back into this exact Erasure instance from a separate, later phase
  // invocation — a plain field on `this` is visible
  // there regardless (an object reference doesn't care which `Context` is
  // ambient when it's dereferenced), where a `PhaseKey` read wouldn't be.
  private val bridgeMap: mutable.Map[Symbol, List[(Symbol, Symbol)]] = mutable.Map.empty
  private var prevDefn: Definitions = defn
  private var typeMap: Erasure.EraseTypeMap = new Erasure.EraseTypeMap(bottomErasedTo)(using defn)

  /** Bridges detected for `classSym`, forcing detection first if it hasn't
    * happened yet. `eraseDenotation`'s `ClassInfo` case (below) always
    * writes `bridgeMap(classSym)`, even to an empty list — so "has this
    * symbol's key ever been written" is a reliable "already detected" test.
    *
    * For an ordinary class, forcing `classSym.info` is enough on its own:
    * the installed `defn.index` transform (`initContext`, below) runs
    * `eraseDenotation` lazily, the first time anything reads a symbol's
    * info, exactly the trigger detection needs. But a class `ElimCapture`
    * synthesizes *after* this transform is installed is instead registered
    * via `defn.index.add(classSym, someClassInfo)` — and `add`, on an
    * already-transform-wrapped index, writes straight into that wrapper's
    * own cache, bypassing the transform entirely (see
    * `InfoProvider.InfoTransformer.add`/`get`). So for such a class,
    * `classSym.info` alone just reads the same never-transformed value back
    * forever — detection needs to be run on it explicitly instead, which is
    * exactly what the fallback below does (and then re-registers the
    * erased result, so this class's info reads consistently everywhere
    * from here on, the same as an ordinary class's would).
    */
  private def bridgesFor(classSym: Symbol)(using Context): List[(Symbol, Symbol)] =
    if !bridgeMap.contains(classSym) then
      classSym.info
      if !bridgeMap.contains(classSym) then
        defn.index.add(classSym, eraseDenotation(classSym.info))
    bridgeMap.getOrElse(classSym, Nil)

  /** The only cross-phase interface exported by erasure. Consumers need not
    * know that bridge detection and adaptation are implemented by this
    * particular phase instance.
    */
  val bridges: Erasure.Bridges = new Erasure.Bridges:
    def materialize(classSym: Symbol)(using ctx: Context): List[FunDef] =
      Erasure.createBridges(
        classSym,
        bridgesFor(classSym),
        (word, expectedType, returnType, context) =>
          eraseWord(word, expectedType, returnType)(using context),
        prevDefn
      )

  override def initContext()(using Context): Unit =
    Erasure.labelResultTypes.set(mutable.Map.empty)

    prevDefn = defn.snapshot
    typeMap = new Erasure.EraseTypeMap(bottomErasedTo)(using prevDefn)

    defn.index.installTransform: (_, denot) =>
      eraseDenotation(denot)

  def eraseDenotation(denot: Denotation)(using Context): Denotation =
    denot match
      case info: ClassInfo =>
        val bridges = new mutable.ArrayBuffer[(Symbol, Symbol)]
        var changed = false
        val erasedViews = info.views.map: tp =>
          val tp2 = eraseType(tp)
          changed = changed || tp2.ne(tp)

          // An external class implements its interfaces on the host platform,
          // where no bridge of ours is emitted or wanted — and its `ClassInfo`
          // lists only its own declarations, so the lookup below is not one it
          // can always answer.
          if !allPrimitivesTagged && !info.classSymbol.isExternal then
            val interfaceInfo = tp2.classInfo
            for method <- interfaceInfo.methods if method.is(Flags.Defer) do
              val implMeth = info.memberSymbol(method.name)
              makeBridge(method, implMeth) match
                case Some(bridge) => bridges += bridge -> implMeth
                case None =>
            end for
          end if

          tp2

        val bridgeList = bridges.toList
        bridgeMap(info.classSymbol) = bridgeList

        if !changed && info.tparams.isEmpty && bridges.isEmpty then return denot

        ClassInfo(
          info.classSymbol,
          Nil, // tparams
          info.self,
          info.fields,
          info.methods ++ bridgeList.map(_._1),
          if changed then erasedViews else info.views
        )

      case toi: TypeOperatorInfo =>
        val body2 = eraseType(toi.body)
        if toi.body `eq` body2 then toi
        else TypeOperatorInfo(toi.tparams, body2, toi.preParamCount)

      case tp: Type => eraseType(tp)

  def eraseType(tp: Type)(using Context): Type =
    typeMap.apply(tp)(using Set.empty)

  def taggingConforms(tp1: Type, tp2: Type): Boolean =
    isTagged(tp1) == isTagged(tp2) && {
      if !tp1.isLambdaType && !tp2.isLambdaType then
        true

      else if tp1.isLambdaType && tp2.isLambdaType then
        val lambda1 = tp1.asLambdaType
        val lambda2 = tp2.asLambdaType
        taggingConforms(lambda1.resultType, lambda2.resultType)
        lambda1.paramTypes.zip(lambda2.paramTypes).forall((tp1, tp2) => taggingConforms(tp1, tp2))

      else if tp1.isLambdaType then
        assert(tp2.approx.isAnyType, "tp2 = " + tp2.show)
        val lambda1 = tp1.asLambdaType
        taggingConforms(lambda1.resultType, AnyType)
        lambda1.paramTypes.forall(tp1 => taggingConforms(tp1, AnyType))

      else
        assert(tp2.isLambdaType, "tp2 = " + tp2.show)
        assert(tp1.approx.isAnyType, "tp1 = " + tp1.show)
        val lambda2 = tp2.asLambdaType
        taggingConforms(lambda2.resultType, AnyType)
        lambda2.paramTypes.forall(tp2 => taggingConforms(tp2, AnyType))

    }

  /** Create a bridge method symbol
    *
    */
  def makeBridge(methDefer: Symbol, methImpl: Symbol)(using Context): Option[Symbol] =
    val procType1 = eraseType(methDefer.tpe).asProcType
    val procType2 = eraseType(methImpl.tpe).asProcType

    val taggingOK =
      taggingConforms(procType1.resultType, procType2.resultType)
      && procType1.paramTypes.zip(procType2.paramTypes).forall((tp1, tp2) => taggingConforms(tp2, tp1))

    val representationOK =
      bridgeRepresentationMatches(procType1.resultType, procType2.resultType)
      && procType1.paramTypes.zip(procType2.paramTypes).forall((tp1, tp2) => bridgeRepresentationMatches(tp1, tp2))

    if taggingOK && representationOK && Subtyping.conforms(procType2, procType1) then
      None

    else
      val bridge = TermSymbol.create(
        methDefer.name + Names.BridgeSuffix,
        procType1,
        Flags.Fun | Flags.Method | Flags.Synthetic,
        Visibility.Default,
        methImpl.owner,
        methImpl.sourcePos
      )(using prevDefn)

      Some(bridge)

  /** Type adaptation for boxing/unboxing of primitive types and cast
    *
    * Only nodes that may have a type of type paramter or primitive type need
    * adaptation.
    */
  def adapt(value: Word, expectedType: Type)(using Context): Word =
    expectedType match
      case ref: RefType => assert(ref.symbol.isType, "Unexpected type = " + expectedType.show)
      case _ =>

    if !expectedType.isValueType then
      value
    else
      val valueType = value.tpe

      val conforms = Subtyping.conforms(value.tpe, expectedType)

      // println("value.tpe = " + value.tpe.show + ", expect = " + expectedType.show)

      if allPrimitivesTagged then
        // fast path for JS/Ruby/Python
        // Lambdas do not matter because all values are tagged
        if conforms then value else Encoded(value)(expectedType)

      else
        if !expectedType.isLambdaType && !valueType.isLambdaType then
          if conforms then
            if isTagged(valueType) || !isTagged(expectedType) then
              value

            else
              Encoded(value)(expectedType)

          else
            assert(valueType.approx.isAnyType, "Expect Any, found = " + valueType.show + ", word = " + value.show)
            // Backend will decide whether the cast involves unboxing
            Encoded(value)(expectedType)

        else if expectedType.isLambdaType && valueType.isLambdaType then
          adaptLambdaValue(value, valueType.asLambdaType, expectedType.asLambdaType)

        else if expectedType.isLambdaType then
          if conforms then
            value
          else
            assert(valueType.approx.isAnyType, "Expect Any, found = " + valueType.show + ", word = " + value.show)
            val lambdaType2 = expectedType.asLambdaType
            val lambdaType1 = LambdaType(lambdaType2.params.map(_ => AnyType), AnyType, lambdaType2.receives)
            adaptLambdaValue(Encoded(value)(lambdaType1), lambdaType1, lambdaType2)

        else
          assert(valueType.isLambdaType, "Expect lambda type, found = " + valueType.show)
          assert(expectedType.approx.isAnyType, "Expect Any, found = " + expectedType.show)
          val lambdaType1 = valueType.asLambdaType
          val lambdaType2 = LambdaType(lambdaType1.params.map(_ => AnyType), AnyType, lambdaType1.receives)
          adaptLambdaValue(value, lambdaType1, lambdaType2)


  def adaptLambdaValue(value: Word, valueType: LambdaType, expectedType: LambdaType)(using Context): Word =
    val lambdaType1 @ LambdaType(paramTypes1, resType1, _) = valueType
    val lambdaType2 @ LambdaType(paramTypes2, resType2, _)  = expectedType

    // println("lambda1 = " + lambdaType1.show + ", lambda2 = " + lambdaType2.show)

    assert(
      paramTypes1.size == paramTypes2.size,
      "lambda arity not equal. lambda1 = " + lambdaType1.show + ", lambda2 = " + lambdaType2.show
    )

    val taggingOK =
      taggingConforms(resType1, resType2)
      && paramTypes1.zip(paramTypes2).forall((tp1, tp2) => taggingConforms(tp2, tp1))

    if taggingOK then
      if Subtyping.conforms(valueType, expectedType) then value else Encoded(value)(expectedType)

    else
      // New symbols should go to old info, so they can be found during eraseType
      TreeOps.createLambda(lambdaType2, Phase.owner.value, value.span)(paramRefs => {
        val args = paramRefs.zip(paramTypes1).map: (paramRef, paramType) =>
          adapt(paramRef, paramType)

        adapt(Apply(value, args, autos = Nil)(value.span), resType2)
      })(using prevDefn)

  def eraseWord(word: Word, expectedType: Type, returnType: Type | Null)(using Context): Word = common.Debug.trace("erase " + word.show, (_: Word).show, enable = false):
    word match
      case Select(qual, name) =>
        val qual2 = eraseWord(qual, expectedType = eraseType(qual.tpe).widen, returnType)
        val select2 =
          if qual2.eq(qual) then word
          else Select(qual2, name)(word.span)
        adapt(select2, expectedType)

      case Encoded(repr) =>
        val isVoid = word.tpe.isVoidType
        repr match
          case lambda: Lambda if !isVoid =>
            // interface encoding
            assert(word.tpe.isLambdaInterface, "Non-lambda interface: " + word.tpe.show)
            val interfaceType = eraseType(word.tpe)
            val Some(lambdaType) = interfaceType.getLambdaInterfaceType.runtimeChecked
            // Return cannot cross lambda boundary
            eraseWord(lambda, expectedType = lambdaType, returnType = null) match
              case Encoded(lambda2: Lambda) =>
                Encoded(lambda2)(interfaceType)

              case lambda2: Lambda =>
                // TODO: This is not good enough for JVM once it's supported
                //
                // In that case, an adaptation lambda needs to be created.
                Encoded(lambda2)(interfaceType)

              case word => throw new Exception("Unexpected lambda interface: " + word.show)

          case _ =>
            if isVoid then
              // value drop
              assert(expectedType.isVoidType, "expected type is non-void: " + expectedType.show)
              val repr2 = eraseWord(repr, expectedType = eraseType(repr.tpe).widen, returnType)

              if repr2.eq(repr) then word else Encoded(repr2)(VoidType)

            else
              // pattern type cast, pattern desugaring array result cast
              val word2 = eraseWord(repr, expectedType = eraseType(repr.tpe).widen, returnType)
              val encodedType2 = eraseType(word.tpe)
              val encoded = adapt(word2, encodedType2)
              adapt(encoded, expectedType)

      case apply @ Apply(fun, args, autos) =>
        val fun2 = fun match
          case TypeApply(funInner, _) => eraseWord(funInner, expectedType = eraseType(fun.tpe).widen, returnType)
          case _ => eraseWord(fun, expectedType = eraseType(fun.tpe).widen, returnType)

        val invokeType =
          try
            fun2.tpe.asInvokableType
          catch case ex: Exception =>
            println("fun.tpe = " + fun.tpe  + ", fun2.tpe = " + fun2.tpe + ", apply = " + apply)
            throw ex

        var changed = fun2 `ne` fun

        val args2 = args.zip(invokeType.paramTypes).map: (arg, paramType) =>
          val arg2 = eraseWord(arg, paramType, returnType)
          changed ||= arg2 `ne` arg
          arg2

        val autos2 = autos.zip(invokeType.autoTypes).map: (auto, autoType) =>
          val auto2 = eraseWord(auto, autoType, returnType)
          changed ||= auto2 `ne` auto
          auto2

        // TODO: type change can be achieved by mutating the type for better performance
        val apply2 =
          if changed || !Subtyping.conforms(invokeType.resultType, apply.tpe) then
            Apply(fun2, args2, autos2)(apply.span, apply.isPartialApply)
          else
            apply

        adapt(apply2, expectedType)

      case New(tpt) =>
        val tp2 = eraseType(tpt.tpe)
        // No adaptation for New
        if tp2.eq(tpt.tpe) then
          word
        else
          New(TypeTree(tp2)(tpt.span))(word.span)

      case Assign(id, rhs, isDefined) =>
        val rhs2 = eraseWord(rhs, id.symbol.tpe, returnType)
        if rhs.eq(rhs2) then word else Assign(id, rhs2, isDefined)

      case FieldAssign(select @ Select(qual, name), rhs) =>
        val qual2 = eraseWord(qual, expectedType = eraseType(qual.tpe).widen, returnType)
        val select2 = if qual2.eq(qual) then select else Select(qual2, name)(word.span)
        val expectType = select2.tpe.widen
        val rhs2 = eraseWord(rhs, expectType, returnType)

        if select2.eq(select) && rhs2.eq(rhs) then word
        else FieldAssign(select2, rhs2)

      case fdef: FunDef => transformFunDef(fdef)

      case ifElse: If =>
        val If(cond, thenp, elsep) = ifElse
        val cond2 = eraseWord(cond, expectedType = defn.BoolType, returnType)
        val thenp2 = eraseWord(thenp, expectedType, returnType)
        val elsep2 = eraseWord(elsep, expectedType, returnType)

        // adaptation happens in each branch
        // TODO: set type to expectedType?
        If(cond2, thenp2, elsep2)(expectedType, ifElse.span)

      case whileDo: While =>
        val While(cond, body) = whileDo
        val cond2 = eraseWord(cond, expectedType = defn.BoolType, returnType)
        val body2 = eraseWord(body, expectedType = VoidType, returnType)
        if cond2.eq(cond) && body2.eq(body) then
          whileDo
        else
          While(cond2, body2)(whileDo.span)

      case Labeled(label, resultType, body) =>
        val resultType2 = eraseType(resultType)
        // Record this label's own (erased) type for a same-labeled `Return`
        // to look up (see the `Return` case) — but don't rebind
        // `returnType` itself while erasing `body`. `returnType` means "the
        // type a `Flags.Fun` function `Return` targets," which stays fixed
        // to the enclosing *function's* return type regardless of how many
        // `Labeled` blocks (e.g. TailCallOpt's `_tco_loop`, typically
        // `VoidType`) a `return` happens to be lexically nested inside.
        // Conflating the two used to mean a real function return nested in
        // a `Labeled` block got erased against that block's type instead of
        // the function's.
        Erasure.labelResultTypes.value(label) = resultType2
        val body2 = eraseWord(body, resultType2, returnType)

        val word2 =
          if body2.eq(body) && resultType2.eq(resultType) then
            word
          else
            Labeled(label, resultType2, body2)(word.span)

        adapt(word2, expectedType)

      case ret @ Return(label, value) =>
        assert(returnType != null, "return type is null")
        // A `Flags.Fun` return targets the enclosing function's own return
        // type (`returnType`, now never rebound by an enclosing `Labeled` —
        // see that case); a local "break out of this block" jump targets
        // that specific label's own type, recorded there.
        val targetType = if label.is(Flags.Fun) then returnType else Erasure.labelResultTypes.value(label)
        val value2 = eraseWord(ret.value, targetType, returnType)
        if value2.eq(value) then word
        else Return(label, value2)(word.span)

      case classTest @ ClassTest(value, cls) =>
        val value2 = eraseWord(value, expectedType = eraseType(value.tpe).widen, returnType)
        if value2.eq(value) then
          adapt(classTest, expectedType)
        else
          adapt(ClassTest(value2, cls)(classTest.span), expectedType)

      case Block(words) =>
        (words: @unchecked) match
          case Nil => word

          case init :+ last =>
            var changed = false
            val init2 = init.map: word =>
              val word2 = eraseWord(word, expectedType = VoidType, returnType)
              changed ||= word2 `ne` word
              word2

            // adaptation happens recursively
            val last2 = eraseWord(last, expectedType, returnType)
            changed ||= last2 `ne` last

            if changed then Block(init2 :+ last2)(word.span) else word

      case lambda @ Lambda(symbol, params, receives, body) =>
        // Return may not cross lambda boundary
        val body2 = eraseWord(body, eraseType(body.tpe).widen, returnType = null)

        val paramChanged = params.exists: param =>
          val tp1 = defn.index.prevInfo(param).asType
          val tp2 = eraseType(tp1)
          tp1 `ne` tp2

        val lambda2 =
          if body2.ne(body) || paramChanged then
            Lambda(symbol, params, receives, body2)(lambda.span)
          else
            lambda

        adapt(lambda2, expectedType)

      case _: Literal | _: Ident => adapt(word, expectedType)

      case _: With | _: Allow | _: Match | _: PatValDef | _: RecordLit |
           _: PatDef | _: IsExpr | _: TypeApply =>

        throw new Exception("Unexpected tree: " + word)

  /** Leave the def tree in original info, which are harmless */
  override def transformFunDef(fdef: FunDef)(using Context): FunDef = try
    val sym = fdef.symbol

    Phase.owner.set(sym)

    val body2 =
      val resType = sym.tpe.asProcType.resultType
      eraseWord(fdef.body, expectedType = resType, returnType = resType)

    fdef.copy(body = body2)(fdef.annots, fdef.span)
  catch case ex =>
    println(fdef.symbol.tpe.show)
    println(fdef.show)
    throw ex

object Erasure:
  /** Narrow result consumed by the later bridge-materialization phase. */
  trait Bridges:
    def materialize(classSym: Symbol)(using Phase.Context): List[FunDef]

  /** Builds bridge trees using the erasure operation captured by the phase
    * that detected them. Keeping the tree construction here makes it
    * independent of both `InterfaceBridge` and a concrete `Erasure` object.
    */
  def createBridges(
    classSym: Symbol,
    bridgePairs: List[(Symbol, Symbol)],
    eraseWord: (Word, Type, Type | Null, Phase.Context) => Word,
    previousDefinitions: Definitions
  )(using context: Phase.Context, currentDefinitions: Definitions): List[FunDef] =
    for (bridgeSym, targetSym) <- bridgePairs yield
      val procType = bridgeSym.tpe.asProcType
      TreeOps.createFunDef(bridgeSym)((paramRefs, autoRefs) => {
        val targetRef = Ident(classSym.classInfo.self)(bridgeSym.span).select(targetSym.name)
        val app = Apply(targetRef, paramRefs, autoRefs)(bridgeSym.span)
        val resType = procType.resultType
        eraseWord(app, resType, resType, context)
      })(using previousDefinitions)

  /** Each currently-erased `Labeled` block's own (erased) result type,
    * keyed by its label symbol — see the `Labeled`/`Return` cases in
    * `eraseWord`. Safe as a `Phase.PhaseKey` (unlike `bridgeMap`/`typeMap`/
    * `prevDefn` on the class itself, see their doc comment) because it's
    * only ever written and read within this phase's own single tree walk,
    * never from a separate later phase.
    */
  val labelResultTypes: Phase.PhaseKey[mutable.Map[Symbol, Type]] =
    new Phase.PhaseKey("labelResultTypes")

  val allTagged: Type => Boolean = _ => true

  def untaggedTypes(symbols: Set[Symbol])(using Definitions): Type => Boolean =
    tp =>
      tp.approx match
        case StaticRef(sym) if symbols.contains(sym) => false
        case _ => true

  /** Erasure type parameters of classes and functions
    *
    * Type erasure should use the original type of symbols.
    */
  class EraseTypeMap(bottomErasedTo: Type)(using defn: Definitions) extends TypeMap:
    type Context = Set[Symbol]

    def apply(tp: Type)(using ctx: Context): Type =
      tp match
        case StaticRef(sym) =>
          if sym.isTypeParameter then AnyType
          // `tp.dealias` is cheap for anything that isn't itself an alias
          // chain (see `dealias`'s `isGroundType` short-circuit) — this only
          // does real work for the one alias (`type Bottom = Bottom` in the
          // `jo` namespace) that actually resolves to `BottomType`.
          else if tp.dealias.isBottomType then bottomErasedTo
          else tp

        case mref: MemberRef =>
          if mref.symbol.isField then this(mref.info)
          else mref.copy(prefix = this(mref.prefix))

        case UnionType(branches) => AnyType

        case AppliedType(tctor, targs) =>
          if tctor.isOneOf(Flags.Class | Flags.Interface) then
            StaticRef(tctor)
          else
            if tctor.isGroundType then
              tp

            else if tctor == defn.jo_Pack then
              // keep vararg mark
              AppliedType(tctor, AnyType :: Nil)

            else
              if ctx.contains(tctor) then AnyType
              else this(tp.dealias)(using ctx + tctor)

        case procType: ProcType =>
          val tparams2 = Nil

          val preTypeParamCount2 = 0

          val params2 =
            for param <- procType.params
            yield param.copy(info = this(param.info))

          val autos2 =
            for auto <- procType.autos
            yield auto.copy(info = this(auto.info))

          val candidates2 = procType.candidates.map(_ => Nil)

          val resType2 = this(procType.resultType)
          // DefaultValue contains no Types to map; thread defaultsFun through unchanged
          ProcType(
            tparams2, params2, autos2, candidates2, resType2, procType.receives,
            procType.preParamCount, preTypeParamCount2
          )(procType.defaultsLazy)

        case _ =>
          recur(tp)
      end match
    end apply
  end EraseTypeMap
