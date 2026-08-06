package phases

import sast.*
import sast.Symbols.*
import sast.Trees.*
import sast.Denotations.*

/** Synthesizes bridge methods: for each interface a class declares a `view`
  * of, a method carrying the interface's own signature that adapts its
  * arguments, forwards to the natural-typed implementation, and adapts the
  * result back.
  *
  * Whether a bridge is needed at all is backend policy — see `BackendTyping`
  * for why `Subtyping.conforms` cannot answer it.
  *
  * Always runs after `Erasure`, so every type read here is already erased;
  * this phase never erases anything itself, it only adapts between erased
  * types. Detection reads `ClassDef`s straight out of the tree, so whichever
  * classes exist when it runs are all handled by one code path — there is no
  * need to force or re-register any symbol's info to make a class visible.
  *
  * Runs *before* `ElimCapture`, on every backend. That rests on `Erasure`'s
  * postcondition holding: if every lambda has already been coerced into the
  * shape its position requires, a lifted class matches its interface with
  * nothing left to bridge, so this phase only ever bridges hand-written
  * classes — which exist this early. Running before also keeps
  * `ElimCapture`'s no-`Lambda` postcondition intact, since any adaptation
  * lambda a bridge body needs is still lifted afterwards.
  *
  * Running *after* instead is what a weak `compatible` appears to call for,
  * and it does fix lifted classes — but it leaves bare `Lambda` nodes no
  * backend can compile (tests/pos/bridge-lambda-param.jo). The two
  * regression tests that pin the boundary are
  * bridge-lifted-interface-ref.jo (needs a bridge on the JVM but not on
  * native, because only the JVM's `compatible` distinguishes a reference
  * type from `Any`) and bridge-lifted-nested-lambda.jo (needs `coercionFree`
  * to conjoin its result check; when it did not, native miscompiled it
  * silently).
  */
class InterfaceBridge(typing: BackendTyping, adapter: TypeAdapter)(using defn: Definitions) extends Phase:
  // Only `cdef.funs` gains entries — no existing function body is rewritten.
  override def transformFunDef(fdef: FunDef)(using Context): FunDef = fdef

  override def transformClassDef(cdef: ClassDef)(using Context): ClassDef =
    val classSym = cdef.symbol
    Phase.owner.set(classSym)

    val pairs = bridgePairs(cdef)

    if pairs.isEmpty then
      cdef

    else
      val bridges = pairs.map((ifaceMeth, implMeth) => createBridge(cdef, ifaceMeth, implMeth))
      registerBridges(classSym, bridges.map(_.symbol))
      cdef.copy(funs = cdef.funs ++ bridges)(cdef.annots, cdef.span)

  /** Each (interface method, implementing method) pair that needs a bridge. */
  private def bridgePairs(cdef: ClassDef): List[(Symbol, Symbol)] =
    for
      view      <- cdef.views
      ifaceMeth <- view.tpe.classInfo.methods if ifaceMeth.is(Flags.Defer)
      implMeth   = cdef.symbol.classInfo.memberSymbol(ifaceMeth.name)
      if !typing.callCompatible(ifaceMeth.tpe.asProcType, implMeth.tpe.asProcType)
    yield (ifaceMeth, implMeth)

  /** A bridge carries the interface method's own signature, so a call
    * compiled against the interface reaches it directly.
    */
  private def createBridge(cdef: ClassDef, ifaceMeth: Symbol, implMeth: Symbol)(using Context): FunDef =
    val classSym = cdef.symbol
    val bridgeType = ifaceMeth.tpe.asProcType
    val targetType = implMeth.tpe.asProcType

    val bridgeSym = TermSymbol.create(
      typing.bridgeName(ifaceMeth),
      bridgeType,
      Flags.Fun | Flags.Method | Flags.Synthetic,
      Visibility.Default,
      classSym,
      implMeth.sourcePos
    )

    // Any adaptation lambda the body needs belongs to the bridge, not to the
    // class or to whichever method happened to be transformed last.
    Phase.owner.set(bridgeSym)

    TreeOps.createFunDef(bridgeSym): (paramRefs, autoRefs) =>
      val target = Ident(classSym.classInfo.self)(bridgeSym.span).select(implMeth.name)
      val args = paramRefs.zip(targetType.paramTypes).map((ref, tp) => adapter.adapt(ref, tp))
      val autos = autoRefs.zip(targetType.autoTypes).map((ref, tp) => adapter.adapt(ref, tp))
      adapter.adapt(Apply(target, args, autos)(bridgeSym.span), bridgeType.resultType)

  /** Bridges must be members of the class, not merely trees in its `funs` —
    * native resolves them through the class's `ClassInfo` when building its
    * interface table.
    */
  private def registerBridges(classSym: Symbol, bridgeSyms: List[Symbol]): Unit =
    val info = classSym.classInfo
    defn.index.add(classSym, ClassInfo(
      info.classSymbol,
      info.tparams,
      info.self,
      info.fields,
      info.methods ++ bridgeSyms,
      info.views
    ))
