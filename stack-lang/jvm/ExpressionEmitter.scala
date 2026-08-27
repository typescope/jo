package jvm

import ast.Positions.Source

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import jvm.JVMTypes.*
import jvm.JVMTypes.JType.*
import jvm.ClassFile.CodeWriter

/** Lowers erased SAST expressions into JVM instructions for one method. */
final class ExpressionEmitter(
  runtime: JVMRuntime
)(using defn: Definitions, context: JVMContext) extends MethodBuilder.Expressions, PrimitiveOps.Operands, NativeCalls.Operands:
  import MethodBuilder.Flow
  private type MethodCtx = MethodContext
  private type Slots = MethodSlots
  private val primitiveOps = new PrimitiveOps(runtime, JVMTypes.typeOf, this)
  private val nativeCalls = new NativeCalls(JVMTypes.typeOf, this)

  override def emitReturn(t: JType, cw: CodeWriter): Unit =
    t match
      case V => cw.returnVoid()
      case r if isIntCat(r) => cw.ireturn()
      case Ref(_) => cw.areturn()
      case J => cw.lreturn()
      case D => cw.dreturn()
      case F => throw new Exception("JVM `float` has no Jo type; see JavaSymbols.unrepresentablePrimitive")

  /** Zero-initialize every local at method entry.
    *
    * The JVM's legacy verifier (see the class-file-version note on
    * `ClassFile`) requires every local slot to be definitely assigned along
    * *every* path reaching a read of it — including a loop's back edge.
    * `TailCallOpt`'s `_tco_result` accumulator, for one, is only assigned
    * on some loop iterations' control paths (the "still recursing" path
    * doesn't touch it), so without this the verifier sees it as possibly
    * uninitialized at the loop header and rejects the method. Zero-filling
    * every local up front — the same fix `javac`/`scalac` apply for `var x
    * = _` locals — makes every incoming edge agree the slot holds *some*
    * value of the right category before the body ever runs.
    */
  override def initializeLocals(locals: List[(Symbol, Int)], cw: CodeWriter): Unit =
    for (local, slot) <- locals do
      JVMTypes.typeOf(local.tpe) match
        case V => ()
        case t if isIntCat(t) => cw.iconst(0); storeLocal(t, slot, cw)
        case t @ Ref(_) => cw.aconstNull(); storeLocal(t, slot, cw)
        case J => cw.lconst(0L); storeLocal(J, slot, cw)
        case D => cw.dconst(0.0); storeLocal(D, slot, cw)
        case F => throw new Exception("JVM `float` has no Jo type; see JavaSymbols.unrepresentablePrimitive")

  //----------------------------------------------------------------------------
  // Statement/expression compilation
  //
  // Postcondition: a falling-through compile leaves exactly
  // jvmType(word.tpe) on the operand stack (nothing, for VoidType).
  //----------------------------------------------------------------------------

  /** Lower one node, attributing the instructions it emits to its own Jo
    * source line.
    *
    * `Block` and `Labeled` are skipped: they emit no instruction of their
    * own, and their span covers everything nested inside them, so marking a
    * line for them would only claim their first child's instructions for
    * the line the block opens on. Every other node marks its line on the
    * way in, and restores the enclosing node's on the way out so that an
    * instruction emitted after a nested operand (see `LineNumbers.here`)
    * can still find it.
    */
  override def compile(word: Word)(using ctx: MethodCtx): Flow =
    word match
      case _: Block | _: Labeled => compileNode(word)

      case _ =>
        val enclosing = ctx.lines.enter(word.span)
        val flow = compileNode(word)
        ctx.lines.leave(enclosing)
        flow

  private def compileNode(word: Word)(using ctx: MethodCtx): Flow =
    word match
      case Literal(c) => compileLiteral(c, JVMTypes.typeOf(word.tpe)); Flow.FallsThrough

      case Ident(sym) => compileIdent(sym, JVMTypes.typeOf(word.tpe)); Flow.FallsThrough

      case Assign(Ident(sym), rhs, _) =>
        // A `rhs` whose compiled form is directly terminal (a `Return`, or
        // an inlined `throwAny`) never actually completes, so there's no
        // value to store and the store never executes.
        //
        // `Erasure`'s `Assign` case (`eraseWord(rhs, id.symbol.tpe, ...)`)
        // already erases `rhs` against this exact assignment's target type
        // (`sym.tpe`, i.e. `pt` below) — including for a `Bottom`-typed
        // `rhs` now that `Erasure` erases `Bottom` to `AnyType` for this
        // backend (see `Erasure`'s own doc comment). A genuine mismatch
        // (an ordinary opaque call, like a pattern match's defensive
        // `abort(...)` fallback landing in `Assign` via TailCallOpt's
        // tail-position catch-all — see `insert` in lib/Set.jo) already
        // arrives wrapped in `Encoded`, reconciled by `compile`'s own
        // `Encoded` case; anything not wrapped already erased to exactly
        // `pt`. The returned flow says whether the store is reachable.
        val pt = JVMTypes.typeOf(sym.tpe)
        val flow = compile(rhs)
        if flow == Flow.FallsThrough then storeLocal(pt, ctx.slots(sym), ctx.cw)
        flow

      case FieldAssign(sel @ Select(qual, name), rhs) =>
        val owner = compileFieldReceiver(qual)
        // The field's *declared* type on the class (e.g. `b: T` in a
        // generic `class Pair[S, T]`) — not `rhs.tpe`, the call site's own
        // (possibly further-instantiated, e.g. `Int`) type — is what the
        // class's own field descriptor was written with in `compileClass`;
        // see `fieldDeclaredType`'s doc comment. `Erasure`'s `FieldAssign`
        // case already erases `rhs` against this
        // exact declared type — same reasoning as `Assign`'s local case
        // above, not re-derived here every time.
        val declared = fieldDeclaredType(sel)
        val flow = compile(rhs)

        if flow == Flow.Terminal then ctx.cw.pop()
        else
          ctx.lines.here()
          ctx.cw.putfield(owner, name, descOf(declared))

        flow

      case If(cond, thenp, elsep) => compileIf(cond, thenp, elsep)

      case While(cond, body) => compileWhile(cond, body)

      case Block(words) =>
        words.foldLeft(Flow.FallsThrough) { (flow, next) =>
          if flow == Flow.Terminal then flow else compile(next)
        }

      case Labeled(label, _, body) =>
        val endL = ctx.cw.newLabel()
        ctx.localLabels(label) = endL
        compile(body)
        ctx.cw.mark(endL)
        Flow.FallsThrough

      case Return(label, value) =>
        // No adaptation of `value` needed in either arm: `Erasure`'s own
        // `Return` case now erases `value` against the right target type
        // for each — `returnType` (the enclosing function's own return
        // type, never rebound by an enclosing `Labeled`) for a real
        // function return, or that specific label's own recorded type for
        // a local block-jump (see `Erasure.labelResultTypes`) — after the
        // `Erasure.scala` fix that separated the two (previously conflated
        // into one `returnType` parameter, which broke a `Flags.Fun`
        // return nested inside a `Labeled` block of a different type, e.g.
        // TailCallOpt's `_tco_loop`).
        if label.is(Flags.Fun) then
          compile(value)
          emitReturn(ctx.returnType, ctx.cw)
        else
          // Local "break out of this Labeled block" jump (e.g. one iteration
          // of a TailCallOpt `_tco_loop`), not a function return.
          val target = ctx.localLabels(label)
          compile(value)
          ctx.cw.gotoL(target)
        Flow.Terminal

      case apply: Apply => compileApply(apply)

      case TypeApply(fun, _) => compile(fun)

      case sel @ Select(qual, name) =>
        // A bare field read (not part of an Apply's function position), e.g.
        // a lifted lambda reading one of its captured fields, or a pattern
        // match's `$o.field` destructuring.
        val owner = compileFieldReceiver(qual)
        val declared = fieldDeclaredType(sel)
        ctx.cw.getfield(owner, name, descOf(declared))
        Flow.FallsThrough

      case ClassTest(value, classSym) =>
        compile(value)
        ctx.cw.instanceOf(classTestOwnerName(classSym))
        Flow.FallsThrough

      case Encoded(repr) =>
        val target = JVMTypes.typeOf(word.tpe)
        val flow = compile(repr)

        if flow == Flow.FallsThrough then
          ctx.lines.here()
          ValueAdaptation.emit(JVMTypes.typeOf(repr.tpe), target, ctx.cw)

        flow

      case other =>
        throw new Exception("JVM backend: unsupported node " + other.getClass.getSimpleName + " -- " + other.show)

  /** `instanceof`'s target class for a `ClassTest`. A primitive/`String`
    * type test checks against its real JDK box class — the same type any
    * such value is boxed to when it flows into an `Any`/union-typed
    * position (see `box`) — a user class/union-variant test checks against
    * its own compiled class, enqueuing it like any other reachable type.
    */
  private def classTestOwnerName(classSym: Symbol): String =
    if classSym == defn.String_type then StringClass
    else if classSym == defn.Int_type then "java/lang/Integer"
    else if classSym == defn.Bool_type then "java/lang/Boolean"
    else if classSym == defn.Byte_type then "java/lang/Byte"
    // A `Char` is boxed as an `Integer` (see `ValueAdaptation.box`), and a
    // union can hold at most one numeric type, so this can never be
    // confused with an `Int` test.
    else if classSym == defn.Char_type then "java/lang/Integer"
    // A Jo `Float` is 64-bit IEEE 754 (lib/Float.jo), so it is a JVM `double`
    // and boxes to `java.lang.Double` — see `ValueAdaptation.box`.
    else if classSym == defn.Float_type then "java/lang/Double"
    else if classSym == defn.Long_type then "java/lang/Long"
    // `Array[T]` is represented as a genuine JVM `Object[]` (see the
    // RefArray intrinsics), never as an instance of the library's own
    // `class Array[T]` wrapper that `patternType.classSymbol` names here —
    // testing against that compiled-but-never-instantiated class would
    // always fail.
    else if classSym == defn.Array_class then ObjectArrayDesc
    else context.requireClass(classSym)

  /** Compile a field access's receiver and return its JVM owner class name.
    * `jvmType`'s "erase everything non-primitive to Object" rule (right for
    * method params/results, which this backend dispatches by symbol, not
    * by the JVM's own type system) is wrong for `getfield`/`putfield`,
    * which need the receiver's verified type to actually match the
    * declaring class — so unlike every other "compile then adapt" call
    * site, this always narrows with an explicit `checkcast` before
    * returning, *except* for `self`: an instance method's `this` already
    * has the correct verified type without one.
    */
  /** The JVM type a field's `getfield`/`putfield` descriptor must use.
    *
    * `sel.tpe` (`qual.tpe.termMember(name)`, see `Select`) is the field's
    * type *as seen at this call site* — for a generic class's field, that's
    * whatever concrete type the receiver happens to be instantiated to
    * there (e.g. `Int` for `pair.b` where `pair: Pair[Bool, Int]`). But
    * `compileClass`'s `fieldOuts` always writes the field's descriptor from
    * its *declared* type on the class itself (`b: T` in `class Pair[S, T]`,
    * an unresolved type parameter — erasing, like any other non-primitive
    * type, to `Object`) — exactly the same "one descriptor per declaration,
    * reconciled at each call site" rule already applied to
    * generic function/method calls. Using the call site's own (possibly
    * narrower, e.g. `Int`) type instead would build a `Fieldref` the class
    * doesn't actually have, `NoSuchFieldError` at runtime.
    */
  private def fieldDeclaredType(sel: Select): JType =
    sel.tpe match
      case MemberRef(_, sym) => JVMTypes.typeOf(sym.tpe)
      case _ => throw new Exception("Cannot resolve field symbol for ." + sel.name)

  private def compileFieldReceiver(qual: Word)(using ctx: MethodCtx): String =
    compile(qual)
    // The receiver's own subexpression has moved the current line; the
    // `checkcast` and the field instruction that follow belong to the field
    // access, and are what a `ClassCastException` or `NullPointerException`
    // here would report.
    ctx.lines.here()
    qual match
      case Ident(sym) if ctx.selfSym.contains(sym) =>
        context.className(sym.owner)
      case _ =>
        val owner = classOrInterfaceSymbol(qual.tpe) match
          case Some(sym) => context.requireClass(sym)
          case None => internalNameOf(JVMTypes.typeOf(qual.tpe))
        // Field instructions require the receiver's verified type to match
        // the declaring class. A redundant checkcast is harmless when the
        // receiver already has that type.
        ctx.cw.checkcast(owner)
        owner

  /** Compile a sub-expression using an already-open emitter/slots without
    * needing a full MethodCtx (used by the constructor emitter, which has a
    * restricted body shape and no control flow).
    */
  override def compileInline(word: Word, slots: Slots, cw: CodeWriter, selfSym: Symbol, source: Source): Unit =
    given MethodCtx = new MethodCtx(cw, slots, V, selfSym = Some(selfSym), source = source)
    compile(word)

  private def compileLiteral(c: Constant, t: JType)(using ctx: MethodCtx): Unit =
    c match
      case Constant.Bool(b) => ctx.cw.iconst(if b then 1 else 0)
      // `Constant.Int` holds a `BigInt` and represents every integer-typed
      // literal, `Long` included (there's no separate `Constant.Long` — see
      // sast.Constant) — `t` (the literal's target JVM type, e.g. `J` for a
      // `val x: Long = 5`) is what actually picks the right representation.
      // A `Byte`-typed literal is pushed already narrowed to its signed
      // 8-bit pattern (`200` as `-56`), the canonical form every other
      // producer of a `Byte` yields — see `JVMTypes.descOf`.
      case Constant.Int(n)  =>
        if t == J then ctx.cw.lconst(n.toLong)
        else if t == B then ctx.cw.iconst(n.toInt.toByte)
        else ctx.cw.iconst(n.toInt)
      case Constant.String(s) => ctx.cw.stringConst(s)
      case Constant.Float(v) => ctx.cw.dconst(v)

  private def compileIdent(sym: Symbol, t: JType)(using ctx: MethodCtx): Unit =
    if ctx.selfSym.contains(sym) then ctx.cw.aload(0)
    else if ctx.slots.contains(sym) then loadLocal(t, ctx.slots(sym), ctx.cw)
    else
      // A field of the enclosing class, accessed as a bare Ident (self implicit)
      throw new Exception("Unsupported free identifier: " + sym.fullName)

  private def loadLocal(t: JType, slot: Int, cw: CodeWriter): Unit =
    if isIntCat(t) then cw.iload(slot)
    else if t == J then cw.lload(slot)
    else if t == D then cw.dload(slot)
    else cw.aload(slot)

  override def storeLocal(t: JType, slot: Int, cw: CodeWriter): Unit =
    if isIntCat(t) then cw.istore(slot)
    else if t == J then cw.lstore(slot)
    else if t == D then cw.dstore(slot)
    else cw.astore(slot)

  private def compileIf(cond: Word, thenp: Word, elsep: Word)(using ctx: MethodCtx): Flow =
    // Erasure normalizes the condition to the JVM Boolean representation.
    if compile(cond) == Flow.Terminal then return Flow.Terminal
    val elseL = ctx.cw.newLabel()
    val endL = ctx.cw.newLabel()
    val hasElse = !elsep.isEmpty
    ctx.cw.ifeq(if hasElse then elseL else endL)
    val thenFlow = compile(thenp)
    // A terminal branch never reaches `endL`, so jumping to the merge point
    // would be dead code. Erasure has already normalized both branch values
    // to the `If` expression's representation.
    if hasElse then
      if thenFlow == Flow.FallsThrough then ctx.cw.gotoL(endL)
      ctx.cw.mark(elseL)
      val elseFlow = compile(elsep)
      ctx.cw.mark(endL)
      if thenFlow == Flow.Terminal && elseFlow == Flow.Terminal then Flow.Terminal
      else Flow.FallsThrough
    else
      ctx.cw.mark(endL)
      Flow.FallsThrough

  private def compileWhile(cond: Word, body: Word)(using ctx: MethodCtx): Flow =
    val beginL = ctx.cw.newLabel()
    val endL = ctx.cw.newLabel()
    ctx.cw.mark(beginL)
    // See `compileIf`'s matching comment: `Erasure`'s `While` case also
    // erases `cond` against `Bool` explicitly.
    if compile(cond) == Flow.Terminal then return Flow.Terminal
    ctx.cw.ifeq(endL)
    if compile(body) == Flow.FallsThrough then ctx.cw.gotoL(beginL)
    ctx.cw.mark(endL)
    Flow.FallsThrough

  //----------------------------------------------------------------------------
  // Apply / calls
  //----------------------------------------------------------------------------

  private def stripTypeApply(w: Word): Word = w match
    case TypeApply(f, _) => f
    case f => f

  private def compileApply(apply: Apply)(using ctx: MethodCtx): Flow =
    val Apply(funRaw, args, autos) = apply
    val allArgs = args ++ autos
    val fun = stripTypeApply(funRaw)

    fun match
      case Ident(symRaw) =>
        val sym = context.resolve(symRaw)
        compileIdentApply(sym, allArgs, apply.tpe)

      case Select(qual, name) if isPrimitiveOwner(qual.tpe) =>
        primitiveOps.compilePrimitive(qual, name, allArgs)
        Flow.FallsThrough

      case Select(qual, name) if isStringOwner(qual.tpe) =>
        primitiveOps.compileString(qual, name, allArgs)
        Flow.FallsThrough

      case Select(New(tpt), Names.Constructor) =>
        compileNew(tpt.tpe, allArgs)
        Flow.FallsThrough

      case Select(qual, name) if qual.tpe.isClassInfoType =>
        compileMethodCall(apply, qual, name, allArgs)
        Flow.FallsThrough

      case other =>
        throw new Exception("JVM backend: unsupported call target " + other)

  /** A method call on a user class or interface instance (`p.sum`,
    * `iter.hasNext`), the general counterpart to
    * primitive and String operations. A class receiver, or an
    * interface receiver calling one of the interface's own abstract
    * members, uses real `invokevirtual`/`invokeinterface` — the JVM's own
    * dispatch resolves overrides correctly from the static receiver type,
    * exactly as for real Java, unlike native's hand-built itable (see
    * docs/jips/jvm-backend.md). A default interface method is emitted as a
    * static top-level helper.
    */
  private def compileMethodCall(apply: Apply, qual: Word, name: String, args: List[Word])(using ctx: MethodCtx): Unit =
    val methodSym = apply.memberSymbol.getOrElse(
      throw new Exception("Cannot resolve method symbol for ." + name))

    // A method reflected out of a Java class file (`JavaSymbols`) carries the
    // same owner/member/descriptor spec a hand-written `@extern` does, and its
    // receiver is just the spec's first operand — so it lowers through
    // `NativeCalls` rather than through this backend's own class naming, which
    // knows nothing about `java/io/FileWriter`.
    runtime.nativeSpec(methodSym) match
      case Some(spec) if methodSym.isExternal =>
        nativeCalls.compile(spec, qual :: args, apply.tpe, ctx.cw)
        return
      case _ =>

    // `.classSymbol` throws for anything but a plain class (in particular
    // for an interface, or a generic instantiation like `Iterator[Int]` —
    // an `AppliedType`, not a bare `StaticRef`); `classOrInterfaceSymbol`
    // (built on `typeSymbolOpt`) handles both.
    val classSym = classOrInterfaceSymbol(qual.tpe).getOrElse(
      throw new Exception("Cannot resolve receiver class/interface for ." + name + " on " + qual.tpe.show))
    val isIface = classSym.isInterface

    val procType = methodSym.tpe.asProcType
    val paramTypes = procType.paramTypes ++ procType.autoTypes

    // No trailing result reconciliation here (contrast `compileStaticCall`,
    // which still needs one — see its doc comment), and no per-argument
    // reconciliation either: `Erasure` (now wired into the JVM pipeline,
    // see Compiler.scala) already made both explicit in the tree — each
    // argument gets erased against its own `paramType`, and the whole call
    // against the call site's expected type — wherever the erased type and
    // the target disagree, as an outer `Encoded` node `compile(word)`'s own
    // `Encoded` case already consumes. Unlike `compileStaticCall` (also used
    // to redirect e.g. `String.size` to the backend-internal
    // `StringOps.size`, a target `Erasure` never saw), every call reaching
    // `compileMethodCall` is a genuine, `Erasure`-visible Jo-level method
    // call, args included.
    if isIface && !methodSym.is(Flags.Defer) then
      val target = context.topLevelLocation(methodSym)
      compile(qual)
      args.foreach(compile)
      val desc = "(Ljava/lang/Object;" + paramTypes.map(JVMTypes.descriptorOf).mkString + ")" + JVMTypes.descriptorOf(procType.resultType)
      ctx.lines.here()
      ctx.cw.invokestatic(target.owner, target.name, desc)

    else
      val ownerName = context.requireClass(classSym)
      // See `compileFieldReceiver`'s doc comment: `jvmType(qual.tpe)` is
      // already `ownerName` in the common case now, so this is a no-op, not
      // a redundant `checkcast`, whenever no real mismatch remains.
      compile(qual)
      ctx.cw.checkcast(ownerName)
      args.foreach(compile)
      val desc = JVMTypes.methodDescriptor(paramTypes, procType.resultType)
      ctx.lines.here()
      if isIface then ctx.cw.invokeinterface(ownerName, name, desc) else ctx.cw.invokevirtual(ownerName, name, desc)

  private def isPrimitiveOwner(tp: Type): Boolean =
    tp.approx match
      case StaticRef(sym) =>
        sym == defn.Int_type || sym == defn.Bool_type || sym == defn.Byte_type ||
        sym == defn.Char_type || sym == defn.Float_type || sym == defn.Long_type
      case _ => false

  private def isStringOwner(tp: Type): Boolean =
    tp.approx match
      case StaticRef(sym) => sym == defn.String_type
      case _ => false

  // `cast`/`refEq`/`isNull` (`runtime/jvm/Runtime.jo`) are declared with
  // plain `(a: Any, ...)` parameters — ordinary Jo functions, not `@extern`
  // (contrast `compileNativeCall`, whose target representations come from
  // raw descriptor strings `Erasure` never sees). `Erasure`'s `Apply` case
  // already erases every argument against that declared `Any`, and (for
  // `cast`'s generic `T` result) reconciles the call site's own concrete
  // instantiation via an outer `Encoded` node — so `args.head`/`resultType`
  // already arrive `Object`-erased here, same as any other call's args.
  private def compileIdentApply(sym: Symbol, args: List[Word], resultType: Type)(using ctx: MethodCtx): Flow =
    if sym == runtime.lowerInvokeLambda then
      LambdaABI.emitLoweredCall(args.head, compile, () => ctx.lines.here(), ctx.cw)
      Flow.FallsThrough
    else runtime.lowerBox.collectFirst { case (primitive, intrinsic) if intrinsic == sym => primitive } match
      case Some(primitive) =>
        compile(args.head)
        ValueAdaptation.emit(ValueAdaptation.Conversion.Box(primitive), ctx.cw)
        Flow.FallsThrough
      case None =>
        runtime.lowerUnbox.collectFirst { case (primitive, intrinsic) if intrinsic == sym => primitive } match
          case Some(primitive) =>
            compile(args.head)
            ValueAdaptation.emit(ValueAdaptation.Conversion.Unbox(primitive), ctx.cw)
            Flow.FallsThrough
          case None => compileNonConversionIdentApply(sym, args, resultType)

  private def compileNonConversionIdentApply(sym: Symbol, args: List[Word], resultType: Type)(using ctx: MethodCtx): Flow =
    if sym == runtime.cast then
      compile(args.head)

    else if sym.is(Flags.Object) then
      // Singleton-object accessor synthesized by `desugarObjectDef` as
      // `def A: A = ...` (a stub body every backend must special-case, see
      // Desugaring.scala). Union cases with no fields (e.g. `Empty`, `None`)
      // desugar the same way. The class eagerly initializes its unique
      // instance in `<clinit>`; Jo guarantees global initialization is
      // acyclic, so no lazy guard or re-entrancy protocol is required.
      val classSym = sym.tpe.asProcType.resultType.classSymbol
      val className = context.requireClass(classSym)
      ctx.cw.getstatic(className, ClassBuilder.SingletonField, "L" + className + ";")

    else if sym == runtime.paramKey then
      compileParamKey(args.head)

    else if sym == runtime.refEq then
      compile(args.head)
      compile(args(1))
      boolFromBranch(l => ctx.cw.ifAcmp("eq", l))

    else if sym == runtime.isNull then
      compile(args.head)
      boolFromBranch(l => ctx.cw.ifnull(l))

    else if sym == runtime.throwAny then
      compile(args.head)
      ctx.lines.here()
      ctx.cw.checkcast(ThrowableClass)
      ctx.cw.athrow()

    else if sym == defn.jo_pass then
      ctx.cw.aconstNull() // the one value of Unit, materialized as null (Ref(ObjectDesc))

    else if sym == runtime.Array_create then
      // The generic Array result uses the erased Object representation;
      // `anewarray` produces the compatible, more specific Object[] value.
      compile(args.head)
      ctx.cw.anewarray(ObjectClass)

    else if sym == runtime.Array_get then
      // See `Array_create`: the receiver conversion is the genuine,
      // backend-specific exception; the `Int` index isn't.
      compile(args.head); ctx.cw.checkcast(ObjectArrayDesc)
      compile(args(1))
      ctx.lines.here()
      ctx.cw.aaload()

    else if sym == runtime.Array_set then
      compile(args.head); ctx.cw.checkcast(ObjectArrayDesc)
      compile(args(1))
      compile(args(2))
      ctx.lines.here()
      ctx.cw.aastore()
      // `aastore` itself leaves nothing (V); `set`'s declared Unit result
      // needs a real null materialized to match (same reconciliation
      // `compileNativeCall` does for e.g. `psPrint`) — a genuine opcode/Jo
      // semantics gap, not anything `Erasure` could have closed.
      ctx.cw.aconstNull()

    else if sym == runtime.Array_size then
      compile(args.head); ctx.cw.checkcast(ObjectArrayDesc)
      ctx.lines.here()
      ctx.cw.arraylength()

    else if sym == runtime.Array_clone then
      compile(args.head); ctx.cw.checkcast(ObjectArrayDesc)
      ctx.lines.here()
      ctx.cw.invokevirtual(ObjectArrayDesc, "clone", "()Ljava/lang/Object;")
      ctx.cw.checkcast(ObjectArrayDesc)

    // `jvm.Array[T]` — a real Java array. Every reference array is an
    // `Object[]` by Java's own covariance, so the `Object[]` opcodes read and
    // write one correctly, and a bad store raises Java's `ArrayStoreException`
    // exactly as it would in Java.
    else if sym == runtime.javaArray_size then
      compile(args.head); ctx.cw.checkcast(ObjectArrayDesc)
      ctx.lines.here()
      ctx.cw.arraylength()

    else if sym == runtime.javaArray_get then
      compile(args.head); ctx.cw.checkcast(ObjectArrayDesc)
      compile(args(1))
      ctx.lines.here()
      ctx.cw.aaload()

    else if sym == runtime.javaArray_set then
      compile(args.head); ctx.cw.checkcast(ObjectArrayDesc)
      compile(args(1))
      compile(args(2))
      ctx.lines.here()
      ctx.cw.aastore()
      // See `Array_set`: `aastore` leaves nothing, so `set`'s declared Unit
      // result needs a null materialized to match.
      ctx.cw.aconstNull()

    else if sym == runtime.ByteArray_create then
      compile(args.head)
      ctx.cw.newByteArray()

    else if sym == runtime.ByteArray_get then
      // `baload` sign-extends the stored octet, which is exactly the signed
      // 8-bit pattern a Jo `Byte` is carried as (see `JVMTypes.descOf`).
      compile(args.head); ctx.cw.checkcast(ByteArrayDesc)
      compile(args(1))
      ctx.lines.here()
      ctx.cw.baload()

    else if sym == runtime.ByteArray_set then
      compile(args.head); ctx.cw.checkcast(ByteArrayDesc)
      compile(args(1))
      compile(args(2))
      ctx.lines.here()
      ctx.cw.bastore()
      // See `Array_set`: `bastore` leaves nothing, so `set`'s declared Unit
      // result needs a null materialized to match.
      ctx.cw.aconstNull()

    else if sym == runtime.ByteArray_size then
      compile(args.head); ctx.cw.checkcast(ByteArrayDesc)
      ctx.lines.here()
      ctx.cw.arraylength()

    else if runtime.nativeSpec(sym).isDefined then
      nativeCalls.compile(runtime.nativeSpec(sym).get, args, resultType, ctx.cw)

    else
      compileStaticCall(sym, args)

    if sym == runtime.throwAny then Flow.Terminal else Flow.FallsThrough

  /** Compile a call to an ordinary top-level Jo function (`invokestatic`
    * against `Main`, enqueuing it for compilation if not already reached).
    *
    * The call is always compiled against `sym`'s own, possibly-generic
    * `ProcType` — never a call site's `TypeApply` instantiation — so every
    * call site agrees with the one compiled method on its descriptor (see
    * the `jvmType` doc comment on why generic instantiation is irrelevant
    * to a JVM descriptor). That means a call to e.g. `getParam[Int](...)`
    * actually produces a generic `Ljava/lang/Object;`, even though *this*
    * call site's own static type is concretely `Int` — `expected` (the
    * caller's actual, possibly-instantiated JType) reconciles the two,
    * the same way `compileNativeCall` reconciles a raw JDK call's actual
    * stack effect against a Jo-declared type.
    */
  override def compileStaticCall(sym: Symbol, args: List[Word])(using ctx: MethodCtx): Unit =
    val target = context.topLevelLocation(sym)
    val procType = sym.tpe.asProcType
    args.foreach(compile)
    ctx.lines.here()
    ctx.cw.invokestatic(target.owner, target.name, JVMTypes.methodDescriptor(procType.paramTypes ++ procType.autoTypes, procType.resultType))

  /** `paramKey(id)` where `id` is (a possibly-encoded) `Ident` to a context
    * parameter symbol: emit the interned fully-qualified name as a String
    * constant. JVM string literals are interned by the class-file format
    * itself, so two `paramKey` call sites for the same parameter always
    * produce reference-equal keys, which is all `refEq`-based lookup needs.
    */
  private def compileParamKey(arg: Word)(using ctx: MethodCtx): Unit =
    def paramSymOf(w: Word): Symbol = w match
      case Ident(s) => s
      case Encoded(inner) => paramSymOf(inner)

      // `paramKey[T](id: T)` erases its argument to `Object`, so `Lowering`
      // wraps the parameter's `Ident` in a boxing intrinsic whenever the
      // parameter has a primitive representation. The parameter's identity
      // is the boxed operand; the box function is the same symbol for every
      // parameter of that representation, so descending into `fun` here
      // would give every `Int` parameter one shared key and make context
      // lookup return whichever binding was added last.
      case Apply(Ident(fun), boxed :: Nil, Nil) if runtime.isBoxIntrinsic(fun) => paramSymOf(boxed)

      case _ => throw new Exception("Unsupported argument to paramKey: " + w.show)

    val paramSym = paramSymOf(arg)
    ctx.cw.stringConst(paramSym.fullName)

  /** Emit a 0/1 int by branching on a JVM conditional-jump instruction. */
  override def boolFromBranch(branchIfTrue: ClassFile.Label => Unit)(using ctx: MethodCtx): Unit =
    val trueL = ctx.cw.newLabel()
    val endL = ctx.cw.newLabel()
    branchIfTrue(trueL)
    ctx.cw.iconst(0)
    ctx.cw.gotoL(endL)
    ctx.cw.mark(trueL)
    ctx.cw.iconst(1)
    ctx.cw.mark(endL)

  //----------------------------------------------------------------------------

  //----------------------------------------------------------------------------
  // Object construction and lambda calls
  //----------------------------------------------------------------------------

  private def compileNew(classType: Type, args: List[Word])(using ctx: MethodCtx): Unit =
    val classSym = classType.classSymbol

    // `new java.io.File(path)` is a real `<init>` on a real Java class, which
    // is exactly the `"special"` spec kind `NativeCalls` already emits.
    if classSym.isExternal then
      val ctor = classSym.classInfo.constructor
      val spec = runtime.nativeSpec(ctor).getOrElse(
        throw new Exception("No constructor binding for external class " + classSym.fullName))
      nativeCalls.compile(spec, args, classType, ctx.cw)
      return

    val className = context.requireClass(classSym)
    val cdef = context.classDef(classSym)
    val ctorParamTypes = cdef.funs.find(_.symbol.name == Names.Constructor).map(_.params.map(_.tpe)).getOrElse(Nil)
    ctx.cw.newObj(className)
    ctx.cw.dup()
    // Erasure has normalized each argument to its declared constructor type.
    args.foreach(compile)
    ctx.lines.here()
    ctx.cw.invokespecial(className, Names.Constructor, "(" + ctorParamTypes.map(descriptorOf).mkString + ")V")

end ExpressionEmitter
