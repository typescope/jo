package jvm

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import jvm.ClassFile.*

import scala.collection.mutable

/** Translates Jo SAST to JVM class files.
  *
  * Scope of this prototype: enough of the SAST is handled to compile
  * `tests/pos/fact.jo` end to end (top-level functions, Int/Bool arithmetic,
  * `if`/`while`, recursion, non-capturing lambdas, and the small amount of
  * runtime plumbing `Predef.println` and context parameters need). See
  * docs/jips/jvm-backend.md for what is deliberately out of scope and how a
  * production version would extend this.
  *
  * Design in one paragraph: every non-primitive Jo type erases to
  * `java.lang.Object` (see `jvmType`); `Int`/`Bool`/`Byte`/`Char`/`Float`/
  * `Long` erase to genuine JVM primitives. `compile(word)` always leaves
  * exactly the value implied by `jvmType(word.tpe)` on the operand stack
  * (or nothing, for `Unit`) — every call site relies on that postcondition
  * instead of threading an expected-type parameter through recursion.
  */
class JVMCodeGen(runtime: JVMRuntime, rewire: Map[Symbol, Symbol])(using defn: Definitions):
  import JVMCodeGen.*

  //----------------------------------------------------------------------------
  // JVM type representation
  //----------------------------------------------------------------------------

  enum JType:
    case I, Z, B, C, F, J, V
    case Ref(desc: String)

  import JType.*

  val ObjectClass = "java/lang/Object"
  val ObjectDesc  = "Ljava/lang/Object;"
  val StringClass = "java/lang/String"
  val StringDesc  = "Ljava/lang/String;"
  val ThrowableClass = "java/lang/Throwable"
  val LambdaClass = "Lambda" // hand-written marker interface, see JVMRuntimeClasses
  val ObjectArrayDesc = "[Ljava/lang/Object;" // Array[T]'s real representation

  def isIntCat(t: JType): Boolean = t match
    case I | Z | B | C => true
    case _ => false

  def isRef(t: JType): Boolean = t.isInstanceOf[Ref]

  def descOf(t: JType): String = t match
    case I => "I"
    case Z => "Z"
    case B => "B"
    case C => "C"
    case F => "F"
    case J => "J"
    case V => "V"
    case Ref(d) => d

  def internalNameOf(t: JType): String = t match
    case Ref(d) if d.startsWith("L") && d.endsWith(";") => d.substring(1, d.length - 1)
    // Array descriptors (e.g. "[Ljava/lang/Object;") are used as-is — the
    // strip-L-and-semicolon convention above only applies to plain classes.
    case Ref(d) if d.startsWith("[") => d
    case _ => ObjectClass

  def jvmType(tp: Type): JType =
    // Only the SAST's *internal* `VoidType` marker (statement-context/
    // dropped-value typing: `Assign`/`While`'s own `.tpe`, and what
    // `dropValue` wraps a discarded statement's type as) means "truly zero
    // bytecode representation, nothing is ever produced." Jo's user-visible
    // `Unit` (a real nominal type with a value, `jo_pass`) and `Bottom` are
    // both genuine *value* types (`Type.isValueType` holds for both) — they
    // fall through to the catch-all `Ref(ObjectDesc)` below like any other
    // non-primitive type, with `null` as their runtime representation.
    // `jo_pass()` compiles to `aconst_null` accordingly. A `Bottom`-typed
    // subexpression's "result" is skipped rather than adapted wherever its
    // *compiled form* is already guaranteed to leave nothing behind — see
    // `isTerminal`, which answers that as a code-generation fact instead of
    // asking this erased type (a `Bottom`-typed value and a compiled form
    // that's actually terminal are related but distinct: an ordinary call to
    // a `Bottom`-declared function, e.g. `abort(...)`, is semantically
    // non-returning but compiles to a plain `invokestatic` the verifier
    // still expects a value from — see `isTerminal`'s doc comment).
    if tp.isVoidType then V
    else
      // `.approx` dealiases and widens term references (e.g. an `Ident`'s
      // `.tpe` is a `StaticRef` to the *symbol*, not its value type).
      tp.approx match
        case StaticRef(sym) if sym == defn.Int_type    => I
        case StaticRef(sym) if sym == defn.Bool_type   => Z
        case StaticRef(sym) if sym == defn.Byte_type   => B
        case StaticRef(sym) if sym == defn.Char_type   => C
        case StaticRef(sym) if sym == defn.Float_type  => F
        case StaticRef(sym) if sym == defn.Long_type   => J
        case StaticRef(sym) if sym == defn.String_type => Ref(StringDesc)
        // A concrete class/interface reference (including a generic class's
        // own instantiation, e.g. `Pair[Bool, Int]`) keeps its own compiled
        // identity through `Erasure` — see `Erasure.EraseTypeMap`'s
        // `AppliedType(tctor, _) => StaticRef(tctor)` for a class/interface
        // `tctor`, which only collapses a genuinely unresolved type
        // parameter to `Any`. Mirror that here instead of collapsing every
        // non-primitive type to `Object` uniformly: a field/method receiver
        // whose declared type is a real class already carries the right
        // owner without a defensive `checkcast` (see `compileFieldReceiver`/
        // `compileMethodCall`), and `Erasure`'s own bridge-method synthesis
        // (`compileClass`) only produces a distinct descriptor from the
        // natural method's when this is precise — collapsing both to
        // `Object` uniformly made them collide as duplicate methods.
        //
        // `Array[T]` is excluded: it's intrinsified directly as a real JVM
        // `Object[]` (`RefArray`'s create/get/set/size/clone), with no
        // `ClassDef` for `enqueueClass` to ever find and compile.
        case _ =>
          classOrInterfaceSymbol(tp) match
            case Some(sym) if sym != defn.Array_class => Ref("L" + enqueueClass(sym) + ";")
            case _ => Ref(ObjectDesc)

  def methodDesc(paramTypes: List[Type], resultType: Type): String =
    "(" + paramTypes.map(t => descOf(jvmType(t))).mkString + ")" + descOf(jvmType(resultType))

  //----------------------------------------------------------------------------
  // Descriptor parsing (needed to interpret @extern `desc` strings)
  //----------------------------------------------------------------------------

  def parseFieldDesc(desc: String): JType = charToJType(desc, 0)._1

  def parseMethodParams(desc: String): List[JType] =
    val end = desc.indexOf(')')
    val params = new mutable.ArrayBuffer[JType]()
    var i = 1
    while i < end do
      val (t, next) = charToJType(desc, i)
      params += t
      i = next
    params.toList

  def parseMethodReturn(desc: String): JType =
    charToJType(desc, desc.indexOf(')') + 1)._1

  private def charToJType(desc: String, at: Int): (JType, Int) =
    desc(at) match
      case 'I' => (I, at + 1)
      case 'Z' => (Z, at + 1)
      case 'B' => (B, at + 1)
      case 'C' => (C, at + 1)
      case 'F' => (F, at + 1)
      case 'J' => (J, at + 1)
      case 'V' => (V, at + 1)
      case 'L' =>
        val semi = desc.indexOf(';', at)
        (Ref(desc.substring(at, semi + 1)), semi + 1)
      case '[' =>
        var j = at
        while desc(j) == '[' do j += 1
        val (_, next) = charToJType(desc, j)
        (Ref(ObjectDesc), next) // arrays are treated as opaque Object references here
      case c => throw new Exception("Unexpected descriptor char '" + c + "' in " + desc)

  //----------------------------------------------------------------------------
  // Program-wide state: reachability worklists, name assignment
  //----------------------------------------------------------------------------

  private val funDefOf   = mutable.Map.empty[Symbol, FunDef]
  private val classDefOf = mutable.Map.empty[Symbol, ClassDef]
  private val interfaceDefOf = mutable.Map.empty[Symbol, InterfaceDef]

  private val topLevelWork = new mutable.ArrayDeque[Symbol]()
  private val topLevelSeen = mutable.Set.empty[Symbol]
  private val topLevelName = mutable.Map.empty[Symbol, String]

  // Pre-reserved so a user class/function can never collide with the
  // synthetic entry-point class or the hand-written runtime classes, which
  // are merged into the output by Compiler.writeClassFiles without going
  // through classSimpleName/enqueueTopLevel's own dedup (see
  // JVMRuntimeClasses).
  // (Literal "Main", not `MainClassName`: that val is declared later in this
  // class body and would still be null at this field's initialization time.)
  private val usedNames = mutable.Set[String]("Main", "Node", "Lambda")

  private val classWork = new mutable.ArrayDeque[Symbol]()
  private val classSeen = mutable.Set.empty[Symbol]
  private val classFiles = mutable.LinkedHashMap.empty[String, Array[Byte]]

  private def resolve(sym: Symbol): Symbol = rewire.getOrElse(sym, sym)

  private def sanitize(s: String): String =
    val b = new StringBuilder
    for c <- s do
      if c.isLetterOrDigit || c == '_' || c == '$' then b += c else b += '_'
    if b.isEmpty || b.head.isDigit then b.insert(0, '_')
    b.toString

  private def enqueueTopLevel(sym: Symbol): String =
    val r = resolve(sym)
    topLevelName.get(r) match
      case Some(n) => n
      case None =>
        val base = sanitize(r.fullName.replace('.', '$'))
        var name = base
        var i = 0
        while usedNames.contains(name) do { i += 1; name = base + "_" + i }
        usedNames += name
        topLevelName(r) = name
        topLevelWork.append(r)
        name

  private def enqueueClass(sym: Symbol): String =
    if !classSeen(sym) then
      classSeen += sym
      classWork.append(sym)
    classSimpleName(sym)

  private val classNameOf = mutable.Map.empty[Symbol, String]
  private def classSimpleName(sym: Symbol): String =
    classNameOf.get(sym) match
      case Some(n) => n
      case None =>
        // ElimCapture's `flatName` is not guaranteed unique across sibling
        // lambdas (it has no per-occurrence counter, unlike e.g. the Ruby
        // backend's UniqueName-based dedup) — two anonymous lambdas in the
        // same enclosing function can compute the identical string. Dedupe
        // here the same way enqueueTopLevel dedupes method names.
        val base = sanitize(sym.name.replace('.', '$'))
        var name = base
        var i = 0
        while usedNames.contains(name) do { i += 1; name = base + "_" + i }
        usedNames += name
        classNameOf(sym) = name
        name

  //----------------------------------------------------------------------------
  // Entry point
  //----------------------------------------------------------------------------

  /** @return output class files, keyed by (JVM internal) class name, plus the
    *         name of the class holding `public static void main`.
    */
  def generate(units: List[FileUnit]): (Map[String, Array[Byte]], String) =
    for unit <- units do collectDefs(unit.defs)

    enqueueTopLevel(runtime.start)
    val mainClassMethods = new mutable.ArrayBuffer[MethodOut]()
    val cp = new ConstantPool

    // A single interleaved loop, not "drain topLevelWork, then drain
    // classWork": compiling a class's methods (e.g. a lifted lambda's
    // `apply`) can discover new top-level function calls — reaching a
    // context parameter pulls in whatever function provides it, say — and
    // compiling a top-level function can likewise discover new classes via
    // `New`. Draining the two queues in sequence would silently drop
    // whichever queue gained new work after its own pass already finished.
    while topLevelWork.nonEmpty || classWork.nonEmpty do
      while topLevelWork.nonEmpty do
        val sym = topLevelWork.removeHead()
        if !topLevelSeen(sym) then
          topLevelSeen += sym
          if !isNativeOrIntrinsic(sym) then
            mainClassMethods += compileTopLevelFunDef(funDefOf(sym), cp)

      if classWork.nonEmpty then
        val sym = classWork.removeHead()
        classDefOf.get(sym) match
          case Some(cdef) => compileClass(cdef, cp)
          case None => compileInterface(interfaceDefOf(sym), cp)

    // Synthetic entry point: `public static void main(String[] args)`
    mainClassMethods += buildJavaMain(cp)

    val mainBytes = ClassFile.write(cp, MainClassName, ObjectClass, Nil, Nil, mainClassMethods.toList)
    (classFiles.toMap + (MainClassName -> mainBytes), MainClassName)

  private def buildJavaMain(cp: ConstantPool): MethodOut =
    val startSym = resolve(runtime.start)
    val startProcType = startSym.tpe.asProcType
    val startDesc = methodDesc(startProcType.paramTypes ++ startProcType.autoTypes, startProcType.resultType)
    val cw = new CodeWriter(cp)
    cw.touchLocal(0) // String[] args
    cw.invokestatic(MainClassName, topLevelName(startSym), startDesc)
    // `start`'s Jo-level return type is `Unit`, which — like any other Jo
    // value type — now erases to `Ref(Object)` (see the `jvmType` doc
    // comment), not `V`; the real Java `main` truly is `void`, so discard it.
    if jvmType(startProcType.resultType) != V then cw.pop()
    cw.returnVoid()
    val (code, ms, ml) = cw.finish()
    MethodOut(AccessFlags.Public | AccessFlags.Static, "main", "([Ljava/lang/String;)V", Some((code, ms, ml)))

  private def isNativeOrIntrinsic(sym: Symbol): Boolean =
    runtime.nativeSpec(sym).isDefined || sym.hasAnnotation(defn.intrinsic)

  private def collectDefs(defs: List[Def]): Unit =
    for d <- defs do
      d match
        case fdef: FunDef =>
          funDefOf(fdef.symbol) = fdef
        case cdef: ClassDef =>
          classDefOf(cdef.symbol) = cdef
          collectDefs(cdef.funs)
        case idef: InterfaceDef =>
          interfaceDefOf(idef.symbol) = idef
          collectDefs(idef.methods)
        case sec: Section =>
          collectDefs(sec.defs)
        case _ =>

  //----------------------------------------------------------------------------
  // Local variable slots
  //----------------------------------------------------------------------------

  private class Slots:
    private val slot = mutable.Map.empty[Symbol, Int]
    private var next = 0

    def reserveUpTo(n: Int): Unit = if n > next then next = n

    def bind(sym: Symbol, t: JType): Int =
      val s = next
      slot(sym) = s
      next += (if t == J then 2 else 1)
      s

    def apply(sym: Symbol): Int = slot(sym)
    def contains(sym: Symbol): Boolean = slot.contains(sym)

    /** Total local-variable slots handed out so far. `CodeWriter` computes
      * `max_locals` from observed `iload`/`istore`/etc. references only, so
      * a trailing parameter that's never read in the body (e.g. an
      * intentionally unused one) would otherwise leave `max_locals` too
      * small for the method descriptor — the JVM's own "arguments can't fit
      * into locals" `ClassFormatError`. Callers touch this many slots
      * explicitly right after binding parameters to rule that out.
      */
    def used: Int = next

  //----------------------------------------------------------------------------
  // Per-method compilation context
  //----------------------------------------------------------------------------

  /** `selfSym`, when set, is the ClassDef's `self` symbol and always lives in
    * local slot 0 (`this`). `argsArraySlot`, when set, is the local slot of
    * the incoming `Object[] args` array for a uniform-convention lambda
    * `apply` method. `localLabels` maps a `Labeled` block's label symbol to
    * its end-of-block jump target — used to compile `TailCallOpt`'s
    * `_tco_loop` labeled blocks and local `Return(label, ...)` "break out
    * of this block" jumps, as opposed to a `Return` to the enclosing
    * function itself. (No declared result type is tracked alongside the
    * label: `Erasure`'s own `Return`/`Labeled` handling already erases the
    * jump's value against that block's own recorded type before this
    * backend ever sees it — see the `Return` case's doc comment.)
    */
  private class MethodCtx(
    val cw: CodeWriter, val slots: Slots, val returnType: JType,
    val selfSym: Option[Symbol], val argsArraySlot: Option[Int] = None,
    val localLabels: mutable.Map[Symbol, ClassFile.Label] = mutable.Map.empty
  )

  //----------------------------------------------------------------------------
  // Top-level (static) function compilation
  //----------------------------------------------------------------------------

  private def compileTopLevelFunDef(fdef: FunDef, cp: ConstantPool): MethodOut =
    val sym = fdef.symbol
    val procType = sym.tpe.asProcType
    val cw = new CodeWriter(cp)
    val slots = new Slots

    // A concrete (default) method declared inside an `interface` body — see
    // compileInterface — is compiled here as an ordinary static function
    // with the receiver bound as an extra leading local, rather than as a
    // genuine JVM interface default method: that would need class file
    // version 52 (illegal below it — see ClassFile's version-49 design
    // note) plus StackMapTable frames for any branch inside it, which this
    // hand-rolled writer doesn't compute. Only the interface's own abstract
    // members (`hasNext`, `next`, ...) get real `invokeinterface` dispatch —
    // see compileMethodCall.
    val selfOpt = if sym.owner.isInterface then Some(interfaceDefOf(sym.owner).self) else None
    for self <- selfOpt do slots.bind(self, Ref(ObjectDesc))

    for param <- fdef.allParams do slots.bind(param, jvmType(param.tpe))
    if slots.used > 0 then cw.touchLocal(slots.used - 1) // see Slots.used
    val localTypes = for local <- fdef.locals yield local -> slots.bind(local, jvmType(local.tpe))

    val resType = jvmType(procType.resultType)
    given MethodCtx = new MethodCtx(cw, slots, resType, selfSym = selfOpt)

    emitLocalDefaults(localTypes, cw)
    compile(fdef.body)
    // A function whose body already ends in a terminal instruction (e.g. an
    // `abort`-stubbed function whose body is a direct `throwAny` call, or one
    // ending in a real `Return`) needs no epilogue at all — emitting a
    // trailing return after it would be dead code following an instruction
    // (`athrow` or another return) that already left nothing on the stack.
    // No `adaptTo` here either: `Erasure.transformFunDef` already erases
    // `fdef.body` against the function's own result type (`resType`), so a
    // genuine mismatch already arrives wrapped in `Encoded` — consumed by
    // `compile`'s own `Encoded` case — and a non-wrapped body's erased type
    // already *is* `resType`. See `isTerminal`'s doc comment for the
    // terminal-instruction reasoning.
    if !isTerminal(fdef.body) then emitReturn(resType, cw)

    val (code, maxStack, maxLocals) = cw.finish()
    val paramTypes = procType.paramTypes ++ procType.autoTypes
    val desc =
      val selfDesc = if selfOpt.isDefined then "Ljava/lang/Object;" else ""
      "(" + selfDesc + paramTypes.map(t => descOf(jvmType(t))).mkString + ")" + descOf(jvmType(procType.resultType))
    MethodOut(AccessFlags.Public | AccessFlags.Static, topLevelName(sym), desc, Some((code, maxStack, maxLocals)))

  private def emitReturn(t: JType, cw: CodeWriter): Unit =
    t match
      case V => cw.returnVoid()
      case r if isIntCat(r) => cw.ireturn()
      case Ref(_) => cw.areturn()
      case J => cw.lreturn()
      case F => throw new Exception("float return not supported in this prototype")

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
  private def emitLocalDefaults(locals: List[(Symbol, Int)], cw: CodeWriter): Unit =
    for (local, slot) <- locals do
      jvmType(local.tpe) match
        case V => ()
        case t if isIntCat(t) => cw.iconst(0); storeLocal(t, slot, cw)
        case t @ Ref(_) => cw.aconstNull(); storeLocal(t, slot, cw)
        case J => cw.lconst(0L); storeLocal(J, slot, cw)
        case F => throw new Exception("float locals not supported in this prototype")

  //----------------------------------------------------------------------------
  // Lambda-lifted class compilation (ElimCapture output)
  //----------------------------------------------------------------------------

  private def compileClass(cdef: ClassDef, cp: ConstantPool): Unit =
    val className = classSimpleName(cdef.symbol)
    // ElimCapture.liftLambda always names the lifted class's single method
    // "apply" when the lambda isn't converted to some user SAM interface
    // (`lambdaInterfaceOpt = None`, so `directViewTypes = Nil`) — but a
    // lambda *converted* to a user interface whose own abstract member also
    // happens to be called "apply" (e.g. `interface Transform[T] def
    // apply(x: T): T`, see tests/pos/erasure-lambda-wrap.jo) produces the
    // exact same method name while needing the natural/bridge compilation
    // path below, not the arity-erased `Object apply(Object[])` marker
    // convention. `cdef.views.isEmpty` (only true for the marker case)
    // disambiguates the two; `Flags.Synthetic` guards against an unrelated
    // ordinary class that happens to define its own no-view `apply` method.
    val isLambda = cdef.symbol.is(Flags.Synthetic) && cdef.views.isEmpty && cdef.funs.exists(f => f.symbol.name == "apply")

    val fieldOuts = cdef.vals.map(f => FieldOut(AccessFlags.Public, f.symbol.name, descOf(jvmType(f.tpt.tpe))))

    val ctorFdefOpt = cdef.funs.find(_.symbol.name == Names.Constructor)
    val ctorOut = ctorFdefOpt.map(compileConstructor(_, cdef, cp))

    val otherMethods = cdef.funs.filter(_.symbol.name != Names.Constructor).map { fdef =>
      if isLambda && fdef.symbol.name == "apply" then compileLambdaApply(fdef, cdef, cp)
      else compileInstanceMethod(fdef, cdef.self, cp)
    }

    // `view Foo` declares this class implements interface `Foo` — enqueue
    // it so it actually gets compiled, same as any other reachable type.
    val declaredInterfaces = cdef.views.flatMap(v => classOrInterfaceSymbol(v.tpe)).map(enqueueClass)

    // A class method implementing an interface's abstract member may have
    // narrower parameter/result types than the interface's own (generic,
    // fully Object-erased) descriptor — e.g. a lambda literal converted to
    // `Ord[String]` (see ElimCapture.liftLambda's `lambdaInterfaceOpt`)
    // compiles its `compare` method with natural `(String, String)`
    // parameters, but `invokeinterface Ord.compare` always resolves against
    // the erased `(Object, Object)` descriptor. The standard JVM fix for
    // this generics-erasure mismatch is a synthetic bridge method with the
    // interface's own erased signature that adapts and forwards to the
    // natural-typed one.
    //
    // `Erasure` (see Compiler.scala) already synthesizes this bridge
    // directly into `cdef.funs` for any *ordinary* user-declared class with
    // `view`s — `Erasure.transformClassDef`/`createBridges` run against the
    // class's `ClassInfo`, which exists before `ElimCapture` does anything.
    // The one case `Erasure` can't see is a lambda literal lifted into a
    // class *by* `ElimCapture`, which runs after `Erasure` — that class
    // doesn't exist yet when bridges are synthesized. So this stays scoped
    // to exactly that case (`Flags.Synthetic` + declared views); an ordinary
    // class relies entirely on `Erasure`'s own bridge, already sitting in
    // `cdef.funs` and compiled by `otherMethods` above like any other
    // method — recomputing one here for it would risk emitting a second,
    // colliding bridge with the same name and descriptor.
    val bridgeMethods =
      if cdef.symbol.is(Flags.Synthetic) && cdef.views.nonEmpty then
        val ifaceAbstractMethods = cdef.views.flatMap(v => classOrInterfaceSymbol(v.tpe))
          .flatMap(_.classInfo.allMethods.filter(_.is(Flags.Defer)))
        cdef.funs.filter(_.symbol.name != Names.Constructor).flatMap { fdef =>
          ifaceAbstractMethods.find(m => m.name == fdef.symbol.name && bridgeNeeded(fdef, m))
            .map(compileSamBridgeMethod(fdef, cdef, _, cp))
        }
      else Nil

    val interfaces = (if isLambda then LambdaClass :: Nil else Nil) ++ declaredInterfaces
    val methods = ctorOut.toList ++ otherMethods ++ bridgeMethods
    val bytes = ClassFile.write(cp, className, ObjectClass, interfaces, fieldOuts, methods)
    classFiles(className) = bytes

  private def bridgeNeeded(fdef: FunDef, ifaceMethodSym: Symbol): Boolean =
    val ifaceProcType = ifaceMethodSym.tpe.asProcType
    val naturalProcType = fdef.symbol.tpe.asProcType
    (ifaceProcType.paramTypes ++ ifaceProcType.autoTypes).map(jvmType) != (naturalProcType.paramTypes ++ naturalProcType.autoTypes).map(jvmType) ||
      jvmType(ifaceProcType.resultType) != jvmType(naturalProcType.resultType)

  /** See `bridgeNeeded`'s doc comment. `ifaceMethodSym` is the interface's
    * own (uninstantiated) abstract member, so its `ProcType` gives the
    * exact erased descriptor `invokeinterface` will look for.
    */
  private def compileSamBridgeMethod(fdef: FunDef, cdef: ClassDef, ifaceMethodSym: Symbol, cp: ConstantPool): MethodOut =
    val className = classSimpleName(cdef.symbol)
    val cw = new CodeWriter(cp)
    val ifaceProcType = ifaceMethodSym.tpe.asProcType
    val erasedParamTypes = (ifaceProcType.paramTypes ++ ifaceProcType.autoTypes).map(jvmType)
    val erasedResultType = jvmType(ifaceProcType.resultType)
    val naturalProcType = fdef.symbol.tpe.asProcType
    val naturalParamTypes = (naturalProcType.paramTypes ++ naturalProcType.autoTypes).map(jvmType)
    val naturalResultType = jvmType(naturalProcType.resultType)

    if (erasedResultType :: naturalResultType :: erasedParamTypes ++ naturalParamTypes).exists(t => t == J || t == F) then
      throw new Exception("long/float bridge parameters not supported in this prototype")

    cw.aload(0)
    var slot = 1
    for (et, nt) <- erasedParamTypes.zip(naturalParamTypes) do
      loadLocal(et, slot, cw)
      adaptTo(et, nt, cw)
      slot += 1
    cw.touchLocal(slot - 1)
    val naturalDesc = methodDesc(naturalProcType.paramTypes ++ naturalProcType.autoTypes, naturalProcType.resultType)
    cw.invokevirtual(className, fdef.symbol.name, naturalDesc)
    adaptTo(naturalResultType, erasedResultType, cw)
    emitReturn(erasedResultType, cw)

    val (code, maxStack, maxLocals) = cw.finish()
    val bridgeDesc = "(" + erasedParamTypes.map(descOf).mkString + ")" + descOf(erasedResultType)
    MethodOut(AccessFlags.Public, ifaceMethodSym.name, bridgeDesc, Some((code, maxStack, maxLocals)))

  /** A real JVM interface: only its `Flags.Defer` members (`hasNext`,
    * `next`, ...) become actual interface methods (abstract, no `Code`
    * attribute), dispatched via genuine `invokeinterface`.
    *
    * Jo interfaces can also carry concrete default-implemented methods
    * (`map`, `fold`, ...); a real JVM interface *default method* would need
    * class file version 52 (illegal below it) plus StackMapTable frames for
    * any branch inside it, which this hand-rolled writer doesn't compute
    * (see ClassFile's version-49 design note). So — like the native/Ruby/JS
    * backends' `MaterializeView` lifting — these are instead compiled as
    * ordinary top-level static functions taking the receiver as an extra
    * argument; see `compileTopLevelFunDef`'s self-binding and
    * `compileMethodCall`'s dispatch to them.
    */
  private def compileInterface(idef: InterfaceDef, cp: ConstantPool): Unit =
    val ifaceName = classSimpleName(idef.symbol)
    val methods = idef.methods.collect {
      case fdef if fdef.symbol.is(Flags.Defer) =>
        val procType = fdef.symbol.tpe.asProcType
        val desc = methodDesc(procType.paramTypes ++ procType.autoTypes, procType.resultType)
        MethodOut(AccessFlags.Public | AccessFlags.Abstract, fdef.symbol.name, desc, None)
    }
    val bytes = ClassFile.write(
      cp, ifaceName, ObjectClass, Nil, Nil, methods,
      accessFlags = AccessFlags.Public | AccessFlags.Interface | AccessFlags.Abstract
    )
    classFiles(ifaceName) = bytes

  private def classOrInterfaceSymbol(tp: Type): Option[Symbol] =
    tp.approx.typeSymbolOpt.filter(_.isOneOf(Flags.Class | Flags.Interface))

  /** Jo constructors are modeled as functions that return the constructed
    * `this` (see ElimCapture); a JVM `<init>` is void and operates on the
    * object `new` already allocated. We translate the specific shape
    * ElimCapture always produces: a `Block` of `FieldAssign`s (self.field =
    * ctorParam) followed by a trailing `Ident(self)`.
    */
  private def compileConstructor(fdef: FunDef, cdef: ClassDef, cp: ConstantPool): MethodOut =
    val cw = new CodeWriter(cp)
    val slots = new Slots
    slots.bind(cdef.self, Ref(ObjectDesc)) // reserve slot 0 for `this`

    val ctorParams = fdef.params // constructor's own explicit params are the field values
    for p <- ctorParams do slots.bind(p, jvmType(p.tpe))

    // A field initializer can itself contain local `val`s (e.g. `val flags:
    // String = val prefixLen = ...; if prefixLen > 0 then ... else ...`,
    // see lib/regex/Validator.jo) — `fdef.locals` finds every such local
    // anywhere in the constructor body via the same census the ordinary
    // function/method compilers use, so they get slots before `emitInit`
    // (via `compileInline`) compiles any code that references them.
    val localTypes = for local <- fdef.locals yield local -> slots.bind(local, jvmType(local.tpe))
    if slots.used > 0 then cw.touchLocal(slots.used - 1) // see Slots.used

    cw.aload(0)
    cw.invokespecial(ObjectClass, Names.Constructor, "()V")
    emitLocalDefaults(localTypes, cw)

    // ElimCapture's usual shape is a `Block` of `FieldAssign`s (self.field =
    // ctorParam) followed by a trailing `Ident(self)`, but an explicit
    // user-written constructor can contain arbitrary statements too — a
    // local `Assign`, a side-effecting call like `println`, and so on (see
    // tests/pos/constructor-flexible-init.jo, constructor-init-order.jo).
    // `FieldAssign` (self-qualified) and ordinary statements both already
    // compile correctly through the general `compile` dispatcher — see
    // `compileFieldReceiver`'s `ctx.selfSym`-aware fast path — so only the
    // trailing bare `self` result (a JVM `<init>` has nothing to return)
    // and `Block` flattening need special-casing here.
    def emitInit(word: Word): Unit =
      word match
        case Block(words) =>
          words.foreach(emitInit)
        case _: Ident =>
          () // trailing `self` result — a JVM <init> has nothing to return
        case other =>
          compileInline(other, slots, cw, cdef.self)

    emitInit(fdef.body)
    cw.returnVoid()

    val (code, maxStack, maxLocals) = cw.finish()
    val desc = "(" + ctorParams.map(p => descOf(jvmType(p.tpe))).mkString + ")V"
    MethodOut(AccessFlags.Public, Names.Constructor, desc, Some((code, maxStack, maxLocals)))

  /** Compile a class instance method with the natural signature implied by
    * its Jo parameter/result types (slot 0 = `this`).
    */
  private def compileInstanceMethod(fdef: FunDef, self: Symbol, cp: ConstantPool): MethodOut =
    val sym = fdef.symbol
    val procType = sym.tpe.asProcType
    val cw = new CodeWriter(cp)
    val slots = new Slots
    slots.bind(self, Ref(ObjectDesc))
    for param <- fdef.allParams do slots.bind(param, jvmType(param.tpe))
    if slots.used > 0 then cw.touchLocal(slots.used - 1) // see Slots.used
    val localTypes = for local <- fdef.locals yield local -> slots.bind(local, jvmType(local.tpe))

    val resType = jvmType(procType.resultType)
    given MethodCtx = new MethodCtx(cw, slots, resType, selfSym = Some(self))

    emitLocalDefaults(localTypes, cw)
    compile(fdef.body)
    // See the matching check in `compileTopLevelFunDef`.
    if !isTerminal(fdef.body) then emitReturn(resType, cw)

    val (code, maxStack, maxLocals) = cw.finish()
    val desc = methodDesc(procType.paramTypes ++ procType.autoTypes, procType.resultType)
    // `Erasure` names a synthesized bridge method `<name>$bridge` (see
    // Names.BridgeSuffix) — that convention is native's own, which looks
    // bridges up explicitly by that suffixed name in its own itable
    // (native/runtime/InterfaceTable.scala), not a real JVM requirement.
    // Real `invokeinterface` dispatch needs the bridge to carry the exact
    // same name as the interface method it bridges — the JVM tells it apart
    // from the natural-typed method purely by descriptor (return type is
    // part of a JVM method's identity, even though Java source can't
    // overload on it alone), so stripping the suffix here is enough.
    val bytecodeName = sym.name.stripSuffix(Names.BridgeSuffix)
    MethodOut(AccessFlags.Public, bytecodeName, desc, Some((code, maxStack, maxLocals)))

  /** Every lambda-lifted class implements the shared marker interface
    * `Lambda` with a single arity-erased method `Object apply(Object[])`,
    * so a call site that only knows a value's abstract lambda type can still
    * dispatch to it via `invokeinterface` without knowing the concrete
    * class. This method adapts that uniform entry point to the lambda's
    * natural per-parameter types.
    */
  private def compileLambdaApply(fdef: FunDef, cdef: ClassDef, cp: ConstantPool): MethodOut =
    val cw = new CodeWriter(cp)
    val slots = new Slots
    slots.bind(cdef.self, Ref(ObjectDesc)) // slot 0 = this
    val argsSlot = 1
    cw.touchLocal(argsSlot) // reserve slot 1 = incoming Object[] args

    val resType = jvmType(fdef.resultType.tpe)
    given MethodCtx = new MethodCtx(cw, slots, resType, selfSym = Some(cdef.self), argsArraySlot = Some(argsSlot))

    // Unpack args[i] into locals matching the lambda's own parameter slots.
    // Locals start at slot 2 (0 = this, 1 = args array).
    slots.reserveUpTo(2)
    for (param, i) <- fdef.params.zipWithIndex do
      val pt = jvmType(param.tpe)
      val s = slots.bind(param, pt)
      cw.aload(argsSlot)
      cw.iconst(i)
      cw.aaload()
      adaptTo(Ref(ObjectDesc), pt, cw)
      storeLocal(pt, s, cw)

    val localTypes = for local <- fdef.locals yield local -> slots.bind(local, jvmType(local.tpe))

    emitLocalDefaults(localTypes, cw)
    compile(fdef.body)
    // Box the natural result up to Object for the uniform `apply` signature.
    if resType == V then cw.aconstNull() else adaptTo(resType, Ref(ObjectDesc), cw)
    cw.areturn()

    val (code, maxStack, maxLocals) = cw.finish()
    MethodOut(AccessFlags.Public, "apply", "([Ljava/lang/Object;)Ljava/lang/Object;", Some((code, maxStack, maxLocals)))

  //----------------------------------------------------------------------------
  // Statement/expression compilation
  //
  // Postcondition: compile(word) leaves exactly jvmType(word.tpe) on the
  // operand stack (nothing, for VoidType).
  //----------------------------------------------------------------------------

  /** Whether compiling `word` is guaranteed to already leave the current
    * instruction stream ended in a genuine JVM control-transfer instruction
    * (`xreturn`/`athrow` via `Return`, or the inlined `throwAny`'s
    * `checkcast`+`athrow`) — i.e. nothing placed immediately after it, in
    * this position, can ever execute.
    *
    * This used to be approximated by asking the *type checker* whether
    * `word`'s type was `Bottom` (`Type.isBottomType`, `this ==
    * BottomType`). That's the wrong question, answered by accident: the
    * only tree node whose `.tpe` is ever the literal `BottomType` singleton
    * is `Return` itself (a hardcoded override — see `Trees.scala`). Every
    * other `Bottom`-declared expression, including an inlined `throwAny`
    * call, carries `StaticRef` to the `Bottom` *alias* symbol instead, which
    * the raw check never matches — so it was really testing "is this
    * literally a `Return` node", not "is this provably terminal", and
    * happened to stay safe only because always falling back to "emit the
    * epilogue" is harmless here: this project targets a pre-StackMapTable
    * classfile version (49, the legacy type-inferencing verifier), which
    * never visits code with no incoming control-flow edge, so a redundant
    * epilogue after a genuinely terminal instruction is inert (confirmed
    * directly: `abort`'s own compiled body is `athrow` followed by a dead
    * `areturn`, and it loads and runs fine even under `-Xverify:all`). An
    * *ordinary call* to a `Bottom`-declared function (`abort(...)`, a plain
    * `invokestatic`) needing the epilogue anyway is the case that actually
    * matters: the verifier has no interprocedural knowledge that `abort`
    * never returns, so it still expects the call's declared return value to
    * be there — confirmed by regression (`ParamSupport.getParam` falling
    * off the end of the code when this was once conflated with the
    * `Return` case; `SetTree.insert`'s pattern-match exhaustiveness
    * fallback landing in `Assign` via `TailCallOpt` needing the same thing).
    *
    * This instead mirrors `compile`'s own case dispatch directly for the
    * handful of shapes that end in a real terminal instruction, and must be
    * kept in sync with any case in `compile` that can end control flow
    * outright. Under-reporting (`false` for something actually terminal)
    * only costs a few bytes of dead code, per the paragraph above; a wrong
    * `true` would be a real bug (skipping control flow that's actually
    * needed), so this stays conservative and defaults to `false`.
    */
  private def isTerminal(word: Word): Boolean =
    word match
      case _: Return => true

      case Apply(funRaw, _, _) =>
        stripTypeApply(funRaw) match
          case Ident(sym) => resolve(sym) == runtime.throwAny
          case _ => false

      case If(_, thenp, elsep) =>
        !elsep.isEmpty && isTerminal(thenp) && isTerminal(elsep)

      case Block(words) =>
        words.nonEmpty && isTerminal(words.last)

      case Encoded(repr) => isTerminal(repr)

      case _ => false

  private def compile(word: Word)(using ctx: MethodCtx): Unit =
    word match
      case Literal(c) => compileLiteral(c, jvmType(word.tpe))

      case Ident(sym) => compileIdent(sym, jvmType(word.tpe))

      case Assign(Ident(sym), rhs, _) =>
        // A `rhs` whose compiled form is directly terminal (a `Return`, or
        // an inlined `throwAny`) never actually completes, so there's no
        // value to store and the store never executes.
        //
        // No local `adaptTo` needed for the non-terminal case either:
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
        // `pt`. See `isTerminal`'s doc comment for the terminal-instruction
        // reasoning this case still relies on.
        val pt = jvmType(sym.tpe)
        compile(rhs)
        if !isTerminal(rhs) then storeLocal(pt, ctx.slots(sym), ctx.cw)

      case FieldAssign(sel @ Select(qual, name), rhs) =>
        val owner = compileFieldReceiver(qual)
        // The field's *declared* type on the class (e.g. `b: T` in a
        // generic `class Pair[S, T]`) — not `rhs.tpe`, the call site's own
        // (possibly further-instantiated, e.g. `Int`) type — is what the
        // class's own field descriptor was written with in `compileClass`;
        // see `fieldDeclaredType`'s doc comment. No local `adaptTo` needed:
        // `Erasure`'s `FieldAssign` case already erases `rhs` against this
        // exact declared type — same reasoning as `Assign`'s local case
        // above, not re-derived here every time.
        val declared = fieldDeclaredType(sel)
        compile(rhs)
        if isTerminal(rhs) then ctx.cw.pop() // discard the now-orphaned `qual` receiver; see above
        else ctx.cw.putfield(owner, name, descOf(declared))

      case If(cond, thenp, elsep) => compileIf(cond, thenp, elsep)

      case While(cond, body) => compileWhile(cond, body)

      case Block(words) =>
        words match
          case Nil => ()
          case init :+ last =>
            init.foreach(compile)
            compile(last)

      case Labeled(label, _, body) =>
        val endL = ctx.cw.newLabel()
        ctx.localLabels(label) = endL
        compile(body)
        ctx.cw.mark(endL)

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

      case Encoded(repr) =>
        val target = jvmType(word.tpe)
        compile(repr)
        // A terminal `repr` (typically a `Return`, e.g. this exact shape is
        // how the frontend adapts a `Return` used as an if-branch statement,
        // `Encoded(Return(...))(VoidType)`) never reaches here with a value
        // to adapt — see `isTerminal`'s doc comment.
        if !isTerminal(repr) then adaptTo(jvmType(repr.tpe), target, ctx.cw)

      case apply: Apply => compileApply(apply)

      case TypeApply(fun, _) => compile(fun)

      case sel @ Select(qual, name) =>
        // A bare field read (not part of an Apply's function position), e.g.
        // a lifted lambda reading one of its captured fields, or a pattern
        // match's `$o.field` destructuring.
        val owner = compileFieldReceiver(qual)
        val declared = fieldDeclaredType(sel)
        ctx.cw.getfield(owner, name, descOf(declared))
        adaptTo(declared, jvmType(sel.tpe), ctx.cw)

      case ClassTest(value, classSym) =>
        compile(value); adaptTo(jvmType(value.tpe), Ref(ObjectDesc), ctx.cw)
        ctx.cw.instanceOf(classTestOwnerName(classSym))

      case other =>
        throw new Exception("JVM backend prototype: unsupported node " + other.getClass.getSimpleName + " -- " + other.show)

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
    else if classSym == defn.Char_type then "java/lang/Character"
    else if classSym == defn.Float_type then "java/lang/Float"
    else if classSym == defn.Long_type then "java/lang/Long"
    // `Array[T]` is represented as a genuine JVM `Object[]` (see the
    // RefArray intrinsics), never as an instance of the library's own
    // `class Array[T]` wrapper that `patternType.classSymbol` names here —
    // testing against that compiled-but-never-instantiated class would
    // always fail.
    else if classSym == defn.Array_class then ObjectArrayDesc
    else enqueueClass(classSym)

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
    * reconciled with `adaptTo` at each call site" rule already applied to
    * generic function/method calls. Using the call site's own (possibly
    * narrower, e.g. `Int`) type instead would build a `Fieldref` the class
    * doesn't actually have, `NoSuchFieldError` at runtime.
    */
  private def fieldDeclaredType(sel: Select): JType =
    sel.tpe match
      case MemberRef(_, sym) => jvmType(sym.tpe)
      case _ => throw new Exception("Cannot resolve field symbol for ." + sel.name)

  private def compileFieldReceiver(qual: Word)(using ctx: MethodCtx): String =
    compile(qual)
    qual match
      case Ident(sym) if ctx.selfSym.contains(sym) =>
        classSimpleName(sym.owner)
      case _ =>
        val owner = classOrInterfaceSymbol(qual.tpe) match
          case Some(sym) => enqueueClass(sym)
          case None => internalNameOf(jvmType(qual.tpe))
        // `jvmType` now keeps a concrete class/interface's own identity
        // through erasure (see its doc comment), so `qual`'s erased type is
        // already `owner` in the common case — `Erasure`'s own `Select`
        // handling (`eraseWord`) already adapts a receiver that needed
        // narrowing from a generic position before this point is ever
        // reached. `adaptTo`'s existing `(Ref(_), Ref(d))` case still
        // covers the genuine mismatch (with an actual `checkcast`) when one
        // remains, and is a no-op — not a redundant explicit `checkcast` —
        // when it's already exactly `owner`.
        adaptTo(jvmType(qual.tpe), Ref("L" + owner + ";"), ctx.cw)
        owner

  /** Compile a sub-expression using an already-open CodeWriter/Slots without
    * needing a full MethodCtx (used by the constructor emitter, which has a
    * restricted body shape and no control flow).
    */
  private def compileInline(word: Word, slots: Slots, cw: CodeWriter, selfSym: Symbol): Unit =
    given MethodCtx = new MethodCtx(cw, slots, V, selfSym = Some(selfSym))
    compile(word)

  private def compileLiteral(c: Constant, t: JType)(using ctx: MethodCtx): Unit =
    c match
      case Constant.Bool(b) => ctx.cw.iconst(if b then 1 else 0)
      // `Constant.Int` holds a `BigInt` and represents every integer-typed
      // literal, `Long` included (there's no separate `Constant.Long` — see
      // sast.Constant) — `t` (the literal's target JVM type, e.g. `J` for a
      // `val x: Long = 5`) is what actually picks the right representation.
      case Constant.Int(n)  => if t == J then ctx.cw.lconst(n.toLong) else ctx.cw.iconst(n.toInt)
      case Constant.String(s) => ctx.cw.ldc(cpOf(ctx).stringConst(s))
      case Constant.Float(_) => throw new Exception("float literals not supported in this prototype")

  private def cpOf(ctx: MethodCtx): ConstantPool = ctx.cw.constants

  private def compileIdent(sym: Symbol, t: JType)(using ctx: MethodCtx): Unit =
    if ctx.selfSym.contains(sym) then ctx.cw.aload(0)
    else if ctx.slots.contains(sym) then loadLocal(t, ctx.slots(sym), ctx.cw)
    else
      // A field of the enclosing class, accessed as a bare Ident (self implicit)
      throw new Exception("Unsupported free identifier: " + sym.fullName)

  private def loadLocal(t: JType, slot: Int, cw: CodeWriter): Unit =
    if isIntCat(t) then cw.iload(slot)
    else if t == J then cw.lload(slot)
    else cw.aload(slot)

  private def storeLocal(t: JType, slot: Int, cw: CodeWriter): Unit =
    if isIntCat(t) then cw.istore(slot)
    else if t == J then cw.lstore(slot)
    else cw.astore(slot)

  private def compileIf(cond: Word, thenp: Word, elsep: Word)(using ctx: MethodCtx): Unit =
    // No `adaptTo` needed: `Erasure`'s `If` case always erases `cond`
    // against `Bool` explicitly (`eraseWord(cond, expectedType = BoolType,
    // ...)`), so it already arrives as a real `Z`, or wrapped in `Encoded`
    // reconciled by `compile`'s own `Encoded` case.
    compile(cond)
    val elseL = ctx.cw.newLabel()
    val endL = ctx.cw.newLabel()
    val hasElse = !elsep.isEmpty
    ctx.cw.ifeq(if hasElse then elseL else endL)
    val afterCond = ctx.cw.currentStack
    compile(thenp)
    // A branch that's already terminal (e.g. ending in `Return`, or an
    // inlined `throwAny`) never actually reaches `endL` — see `isTerminal`'s
    // doc comment — so jumping to the merge point would be dead code, and no
    // `adaptTo` is needed for a non-terminal branch either: `Erasure`'s `If`
    // case erases both branches against this whole `If`'s own (erased)
    // type, so each branch's compiled value already matches directly, or
    // arrives wrapped in `Encoded` (reconciled by `compile`'s own `Encoded`
    // case) wherever it doesn't. Unlike the other `isTerminal` call sites,
    // getting `thenTerminal` wrong in the *unsafe* direction (treating a
    // branch that actually falls through as terminal) would still be a real
    // bug: `endL` is a live merge point with a real second incoming edge
    // from `elsep`, so a wrongly-omitted `gotoL` here would fall straight
    // into `elsep`'s code instead of jumping past it.
    val thenTerminal = isTerminal(thenp)
    if hasElse then
      if !thenTerminal then ctx.cw.gotoL(endL)
      ctx.cw.mark(elseL)
      ctx.cw.setStack(afterCond)
      compile(elsep)
    ctx.cw.mark(endL)

  private def compileWhile(cond: Word, body: Word)(using ctx: MethodCtx): Unit =
    val beginL = ctx.cw.newLabel()
    val endL = ctx.cw.newLabel()
    ctx.cw.mark(beginL)
    // See `compileIf`'s matching comment: `Erasure`'s `While` case also
    // erases `cond` against `Bool` explicitly.
    compile(cond)
    ctx.cw.ifeq(endL)
    compile(body)
    ctx.cw.gotoL(beginL)
    ctx.cw.mark(endL)

  //----------------------------------------------------------------------------
  // Apply / calls
  //----------------------------------------------------------------------------

  private def stripTypeApply(w: Word): Word = w match
    case TypeApply(f, _) => f
    case f => f

  private def compileApply(apply: Apply)(using ctx: MethodCtx): Unit =
    val Apply(funRaw, args, autos) = apply
    val allArgs = args ++ autos
    val fun = stripTypeApply(funRaw)

    if funRaw.tpe.isLambdaType then
      compileLambdaCall(fun, allArgs, jvmType(apply.tpe))
    else
      fun match
        case Ident(symRaw) =>
          val sym = resolve(symRaw)
          compileIdentApply(sym, allArgs, apply.tpe)

        case Select(qual, name) if isPrimitiveOwner(qual.tpe) =>
          compilePrimitiveOp(qual, name, allArgs, jvmType(apply.tpe))

        case Select(qual, name) if isStringOwner(qual.tpe) =>
          compileStringOp(qual, name, allArgs, jvmType(apply.tpe))

        case Select(newExpr @ New(tpt), Names.Constructor) =>
          compileNew(tpt.tpe, allArgs)

        case Select(qual, name) if qual.tpe.isClassInfoType =>
          compileMethodCall(apply, qual, name, allArgs)

        case other =>
          throw new Exception("JVM backend prototype: unsupported call target " + other)

  /** A method call on a user class or interface instance (`p.sum`,
    * `iter.hasNext`), the general counterpart to
    * `compilePrimitiveOp`/`compileStringOp`. A class receiver, or an
    * interface receiver calling one of the interface's own abstract
    * members, uses real `invokevirtual`/`invokeinterface` — the JVM's own
    * dispatch resolves overrides correctly from the static receiver type,
    * exactly as for real Java, unlike native's hand-built itable (see
    * docs/jips/jvm-backend.md). A default (concrete) interface method is
    * instead a call to the static helper `compileTopLevelFunDef` compiles
    * it as — see that method's doc comment for why.
    */
  private def compileMethodCall(apply: Apply, qual: Word, name: String, args: List[Word])(using ctx: MethodCtx): Unit =
    // `.classSymbol` throws for anything but a plain class (in particular
    // for an interface, or a generic instantiation like `Iterator[Int]` —
    // an `AppliedType`, not a bare `StaticRef`); `classOrInterfaceSymbol`
    // (built on `typeSymbolOpt`) handles both.
    val classSym = classOrInterfaceSymbol(qual.tpe).getOrElse(
      throw new Exception("Cannot resolve receiver class/interface for ." + name + " on " + qual.tpe.show))
    val isIface = classSym.isInterface

    val methodSym = apply.memberSymbol.getOrElse(
      throw new Exception("Cannot resolve method symbol for ." + name))
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
      val fnName = enqueueTopLevel(methodSym)
      compile(qual); adaptTo(jvmType(qual.tpe), Ref(ObjectDesc), ctx.cw)
      args.foreach(compile)
      val desc = "(Ljava/lang/Object;" + paramTypes.map(t => descOf(jvmType(t))).mkString + ")" + descOf(jvmType(procType.resultType))
      ctx.cw.invokestatic(MainClassName, fnName, desc)

    else
      val ownerName = enqueueClass(classSym)
      // See `compileFieldReceiver`'s doc comment: `jvmType(qual.tpe)` is
      // already `ownerName` in the common case now, so this is a no-op, not
      // a redundant `checkcast`, whenever no real mismatch remains.
      compile(qual); adaptTo(jvmType(qual.tpe), Ref("L" + ownerName + ";"), ctx.cw)
      args.foreach(compile)
      val desc = methodDesc(paramTypes, procType.resultType)
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
  private def compileIdentApply(sym: Symbol, args: List[Word], resultType: Type)(using ctx: MethodCtx): Unit =
    if sym == runtime.cast then
      compile(args.head)

    else if sym.is(Flags.Object) then
      // Singleton-object accessor synthesized by `desugarObjectDef` as
      // `def A: A = ...` (a stub body every backend must special-case, see
      // Desugaring.scala). Union cases with no fields (e.g. `Empty`, `None`)
      // desugar the same way. Pattern matching on these always compiles to
      // `ClassTest`/`instanceof` (never reference equality), so — unlike
      // the JS/Ruby backends' cached static-field singleton — a fresh
      // instance per access is simplest and just as correct here.
      val classSym = sym.tpe.asProcType.resultType.classSymbol
      val className = enqueueClass(classSym)
      ctx.cw.newObj(className)
      ctx.cw.dup()
      ctx.cw.invokespecial(className, Names.Constructor, "()V")
      adaptTo(Ref(ObjectDesc), jvmType(resultType), ctx.cw)

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
      ctx.cw.checkcast(ThrowableClass)
      ctx.cw.athrow()

    else if sym == defn.jo_pass then
      ctx.cw.aconstNull() // the one value of Unit, materialized as null (Ref(ObjectDesc))

    else if sym == runtime.Array_create then
      // `size: Int` is an ordinary concrete param (Erasure-covered, no
      // `adaptTo` needed). No result reconciliation either: `create[T]`'s
      // generic `Array[T]` result erases (like `clone`'s, below) to
      // `Ref(ObjectDesc)` via `jvmType`'s own `Array_class` exclusion (see
      // its doc comment) — exactly `anewarray`'s actual `Ref(ObjectArrayDesc)`
      // widened, which `adaptTo`'s `(Ref(_), Ref(ObjectDesc)) => ()` rule
      // already treats as free.
      compile(args.head)
      ctx.cw.anewarray(ObjectClass)

    else if sym == runtime.Array_get then
      // See `Array_create`: the receiver conversion is the genuine,
      // backend-specific exception; the `Int` index isn't.
      compile(args.head); adaptTo(jvmType(args.head.tpe), Ref(ObjectArrayDesc), ctx.cw)
      compile(args(1))
      ctx.cw.aaload()

    else if sym == runtime.Array_set then
      compile(args.head); adaptTo(jvmType(args.head.tpe), Ref(ObjectArrayDesc), ctx.cw)
      compile(args(1))
      compile(args(2))
      ctx.cw.aastore()
      // `aastore` itself leaves nothing (V); `set`'s declared Unit result
      // needs a real null materialized to match (same reconciliation
      // `compileNativeCall` does for e.g. `psPrint`) — a genuine opcode/Jo
      // semantics gap, not anything `Erasure` could have closed.
      adaptTo(V, jvmType(resultType), ctx.cw)

    else if sym == runtime.Array_size then
      compile(args.head); adaptTo(jvmType(args.head.tpe), Ref(ObjectArrayDesc), ctx.cw)
      ctx.cw.arraylength()

    else if sym == runtime.Array_clone then
      compile(args.head); adaptTo(jvmType(args.head.tpe), Ref(ObjectArrayDesc), ctx.cw)
      ctx.cw.invokevirtual(ObjectArrayDesc, "clone", "()Ljava/lang/Object;")
      ctx.cw.checkcast(ObjectArrayDesc)

    else if runtime.nativeSpec(sym).isDefined then
      compileNativeCall(runtime.nativeSpec(sym).get, args, resultType)

    else
      compileStaticCall(sym, args, jvmType(resultType))

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
  private def compileStaticCall(sym: Symbol, args: List[Word], expected: JType)(using ctx: MethodCtx): Unit =
    val name = enqueueTopLevel(sym)
    val procType = sym.tpe.asProcType
    for (arg, pt) <- args.zip(procType.paramTypes ++ procType.autoTypes) do
      compile(arg)
      adaptTo(jvmType(arg.tpe), jvmType(pt), ctx.cw)
    ctx.cw.invokestatic(MainClassName, name, methodDesc(procType.paramTypes ++ procType.autoTypes, procType.resultType))
    adaptTo(jvmType(procType.resultType), expected, ctx.cw)

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
      case Apply(inner, _, _) => paramSymOf(inner)
      case _ => throw new Exception("Unsupported argument to paramKey: " + w.show)
    val paramSym = paramSymOf(arg)
    ctx.cw.ldc(cpOf(ctx).stringConst(paramSym.fullName))

  /** Emit a 0/1 int by branching on a JVM conditional-jump instruction. */
  private def boolFromBranch(branchIfTrue: ClassFile.Label => Unit)(using ctx: MethodCtx): Unit =
    val trueL = ctx.cw.newLabel()
    val endL = ctx.cw.newLabel()
    branchIfTrue(trueL)
    val afterBranch = ctx.cw.currentStack // depth once both compared operands are popped
    ctx.cw.iconst(0)
    ctx.cw.gotoL(endL)
    ctx.cw.mark(trueL)
    ctx.cw.setStack(afterBranch) // trueL is reached with the pre-`iconst(0)` depth, not the false arm's
    ctx.cw.iconst(1)
    ctx.cw.mark(endL)

  /** `declaredResultType` is the calling Jo symbol's own declared return
    * type, e.g. `Unit` for `psPrint`/`setNodeValue`. It's frequently *not*
    * the same JVM representation as what the raw JDK member actually
    * produces — a `putfield`/`void`-`virtual` call produces nothing, but a
    * `Unit`-declared Jo function now erases to `Ref(Object)` (see the
    * `jvmType` doc comment), so a real `null` needs materializing to match.
    * Every kind below is adapted uniformly at the end from what it actually
    * left on the stack to `jvmType(declaredResultType)`, the same way any
    * other call's result is reconciled, rather than relying on informal
    * "close enough" reasoning about widening.
    */
  private def compileNativeCall(spec: runtime.NativeSpec, args: List[Word], declaredResultType: Type)(using ctx: MethodCtx): Unit =
    val cw = ctx.cw
    val actual: JType = spec.kind match
      case "static" =>
        val paramTs = parseMethodParams(spec.desc)
        for (a, pt) <- args.zip(paramTs) do { compile(a); adaptTo(jvmType(a.tpe), pt, cw) }
        cw.invokestatic(spec.owner, spec.member, spec.desc)
        parseMethodReturn(spec.desc)

      case "virtual" | "interface" =>
        val recv :: rest = args: @unchecked
        compile(recv); adaptTo(jvmType(recv.tpe), Ref(ObjectDesc), cw); cw.checkcast(spec.owner)
        val paramTs = parseMethodParams(spec.desc)
        for (a, pt) <- rest.zip(paramTs) do { compile(a); adaptTo(jvmType(a.tpe), pt, cw) }
        if spec.kind == "virtual" then cw.invokevirtual(spec.owner, spec.member, spec.desc)
        else cw.invokeinterface(spec.owner, spec.member, spec.desc)
        parseMethodReturn(spec.desc)

      case "special" =>
        cw.newObj(spec.owner)
        cw.dup()
        val paramTs = parseMethodParams(spec.desc)
        for (a, pt) <- args.zip(paramTs) do { compile(a); adaptTo(jvmType(a.tpe), pt, cw) }
        cw.invokespecial(spec.owner, "<init>", spec.desc)
        // Constructor descriptors are always declared `(...)V`, but `new`+
        // `dup`+`invokespecial <init>` actually leaves the new instance —
        // of the constructed class specifically, not `V` — on the stack.
        Ref("L" + spec.owner + ";")

      case "getstatic" =>
        cw.getstatic(spec.owner, spec.member, spec.desc)
        parseFieldDesc(spec.desc)

      case "putstatic" =>
        val t = parseFieldDesc(spec.desc)
        compile(args.head); adaptTo(jvmType(args.head.tpe), t, cw)
        cw.putstatic(spec.owner, spec.member, spec.desc)
        V

      case "getfield" =>
        compile(args.head); adaptTo(jvmType(args.head.tpe), Ref(ObjectDesc), cw); cw.checkcast(spec.owner)
        cw.getfield(spec.owner, spec.member, spec.desc)
        parseFieldDesc(spec.desc)

      case "putfield" =>
        compile(args.head); adaptTo(jvmType(args.head.tpe), Ref(ObjectDesc), cw); cw.checkcast(spec.owner)
        val t = parseFieldDesc(spec.desc)
        compile(args(1)); adaptTo(jvmType(args(1).tpe), t, cw)
        cw.putfield(spec.owner, spec.member, spec.desc)
        V

      case other =>
        throw new Exception("Unknown @extern kind: " + other)

    adaptTo(actual, jvmType(declaredResultType), cw)

  //----------------------------------------------------------------------------
  // Primitive numeric/boolean operators (Int/Bool/Byte/Char intrinsics)
  //----------------------------------------------------------------------------

  private def compilePrimitiveOp(qual: Word, name: String, args: List[Word], resultType: JType)(using ctx: MethodCtx): Unit =
    if jvmType(qual.tpe) == J then
      compileLongOp(qual, name, args)
    else
      compileIntCatPrimitiveOp(qual, name, args)

  /** `Int`/`Bool`/`Byte`/`Char`/`Float` intrinsics — every one of these
    * (except `Float`, not supported in this prototype) shares the `I`
    * representation, so they're compiled uniformly here. `Long` doesn't (a
    * category-2 JVM value, distinct opcodes for everything), so it's
    * dispatched to `compileLongOp` instead, from `compilePrimitiveOp`.
    *
    * No `adaptTo` needed anywhere below, unlike most other call/argument
    * sites: `qual`'s type is already pinned by `compilePrimitiveOp`'s own
    * dispatch (this function is only ever reached when `jvmType(qual.tpe)`
    * is one of `I`/`Z`/`B`/`C`, per `isPrimitiveOwner`), and every operator
    * here (`+`, `&&`, `toByte`, ...) has a concrete, non-generic primitive
    * parameter type, so `Erasure`'s own `Apply` case (which erases each
    * argument against that exact declared parameter type) already leaves
    * `args.head` erased to the same bucket, or wrapped in `Encoded`
    * (reconciled by `compile`'s own `Encoded` case). Source and target are
    * therefore always both in `{I, Z, B, C}`, which `adaptTo` itself treats
    * as a no-op (`isIntCat(a) && isIntCat(b) => ()`) — there is no case left
    * for it to actually do anything.
    */
  private def compileIntCatPrimitiveOp(qual: Word, name: String, args: List[Word])(using ctx: MethodCtx): Unit =
    val cw = ctx.cw
    val qt = jvmType(qual.tpe)

    def binIntOp(emit: () => Unit): Unit =
      compile(qual)
      compile(args.head)
      emit()

    def cmpOp(cond: String): Unit =
      compile(qual)
      compile(args.head)
      boolFromBranch(l => cw.ifIcmp(cond, l))

    name match
      case "+" => binIntOp(cw.iadd)
      case "-" if args.nonEmpty => binIntOp(cw.isub)
      case "-" | "~-" => compile(qual); cw.ineg()
      case "~~" => compile(qual); cw.iconst(-1); cw.ixor()
      case "*" => binIntOp(cw.imul)
      case "/" => binIntOp(cw.idiv)
      case "%" => binIntOp(cw.irem)
      case "&" => binIntOp(cw.iand)
      case "|" => binIntOp(cw.ior)
      case "^" => binIntOp(cw.ixor)
      case "<<" => binIntOp(cw.ishl)
      case ">>" => binIntOp(cw.ishr)
      case "==" => cmpOp("eq")
      case "!=" => cmpOp("ne")
      case ">" => cmpOp("gt")
      case "<" => cmpOp("lt")
      case ">=" => cmpOp("ge")
      case "<=" => cmpOp("le")
      case "!" | "~!" => compile(qual); boolNot()
      case "&&" =>
        // short-circuiting and/or are already lowered to If by earlier phases
        // in practice, but handle directly in case they reach here.
        compile(qual)
        val elseL = cw.newLabel(); val endL = cw.newLabel()
        cw.ifeq(elseL)
        compile(args.head)
        cw.gotoL(endL)
        cw.mark(elseL); cw.iconst(0)
        cw.mark(endL)
      case "||" =>
        compile(qual)
        val elseL = cw.newLabel(); val endL = cw.newLabel()
        cw.ifeq(elseL)
        cw.iconst(1)
        cw.gotoL(endL)
        cw.mark(elseL)
        compile(args.head)
        cw.mark(endL)
      case "toChar" | "toInt" =>
        // Char shares Int's full range in this backend (no truncation to
        // 16 bits — Jo's Char is a full Unicode code point, not a UTF-16
        // code unit), and Byte->Int/Char->Int are already the same
        // representation, so both are genuine no-ops.
        compile(qual)
      case "toByte" =>
        // Jo's Byte is unsigned 8-bit ([0, 255], lib/Byte.jo) sharing Int's
        // representation, so converting *to* Byte must mask to 8 bits
        // (unlike JVM's own signed `i2b`, which would sign-extend and give
        // the wrong answer for values >= 128).
        compile(qual)
        cw.iconst(0xFF); cw.iand()
      case "toLong" =>
        compile(qual); cw.i2l()
      case "toString" =>
        val (owner, desc) =
          // Character.toString(char) truncates to 16 bits — wrong for Jo's
          // Char, a full Unicode code point (up to 0x10FFFF, e.g. emoji).
          // Character.toString(int codePoint) (Java 11+) handles the full
          // range correctly, including encoding a supplementary character
          // as a surrogate pair.
          if qt == C then ("java/lang/Character", "(I)Ljava/lang/String;")
          else if qt == Z then ("java/lang/Boolean", "(Z)Ljava/lang/String;")
          else if qt == B then ("java/lang/Byte", "(B)Ljava/lang/String;")
          else ("java/lang/Integer", "(I)Ljava/lang/String;")
        compile(qual)
        cw.invokestatic(owner, "toString", desc)
      case other =>
        throw new Exception("JVM backend prototype: unsupported primitive operator " + other)

  /** `Long`'s intrinsics — a genuine category-2 JVM value (2 operand-stack
    * words, 2 local-variable slots), so unlike `Int`/`Bool`/`Byte`/`Char`
    * (all sharing the `I` representation, see `compileIntCatPrimitiveOp`)
    * it needs its own opcodes throughout, not just a wider range of `I`.
    *
    * No `adaptTo` needed here either, for the same reason as
    * `compileIntCatPrimitiveOp` — `compilePrimitiveOp` only dispatches here
    * when `jvmType(qual.tpe)` is already exactly `J`, and every operator's
    * declared parameter type is `Long`, so `Erasure`'s `Apply` case already
    * leaves `args.head` erased to `J` too (directly, or via `Encoded`
    * consumed by `compile`'s own case). Source and target are always both
    * `J`, and `adaptTo`'s very first check (`actual == expected`) already
    * makes that a no-op.
    */
  private def compileLongOp(qual: Word, name: String, args: List[Word])(using ctx: MethodCtx): Unit =
    val cw = ctx.cw

    def binLongOp(emit: () => Unit): Unit =
      compile(qual)
      compile(args.head)
      emit()

    // `lshl`/`lshr`'s shift-*amount* operand must be a plain `int` (JVMS),
    // but `Long.<<`/`Long.>>` declare their count parameter as `Long`
    // (lib/Long.jo) — compile it as `Long` like any other argument, then
    // narrow with `l2i` right before the shift opcode (only the low 6 bits
    // matter for a 64-bit shift count anyway, so truncation is harmless).
    def shiftLongOp(emit: () => Unit): Unit =
      compile(qual)
      compile(args.head); cw.l2i()
      emit()

    // No `if_lcmp<cond>` branch family exists — `lcmp` reduces the
    // comparison to a category-1 int (-1/0/1), then an ordinary
    // int-vs-zero branch (`ifCond`) reads off the result.
    def cmpOp(cond: String): Unit =
      compile(qual)
      compile(args.head)
      cw.lcmp()
      boolFromBranch(l => cw.ifCond(cond, l))

    name match
      case "+" => binLongOp(cw.ladd)
      case "-" if args.nonEmpty => binLongOp(cw.lsub)
      case "-" | "~-" => compile(qual); cw.lneg()
      case "~~" => compile(qual); cw.lconst(-1L); cw.lxor()
      case "*" => binLongOp(cw.lmul)
      case "/" => binLongOp(cw.ldiv)
      case "%" => binLongOp(cw.lrem)
      case "&" => binLongOp(cw.land)
      case "|" => binLongOp(cw.lor)
      case "^" => binLongOp(cw.lxor)
      case "<<" => shiftLongOp(cw.lshl)
      case ">>" => shiftLongOp(cw.lshr)
      case "==" => cmpOp("eq")
      case "!=" => cmpOp("ne")
      case ">" => cmpOp("gt")
      case "<" => cmpOp("lt")
      case ">=" => cmpOp("ge")
      case "<=" => cmpOp("le")
      case "toInt" =>
        compile(qual); cw.l2i()
      case "toLong" =>
        compile(qual)
      case "toString" =>
        compile(qual)
        cw.invokestatic("java/lang/Long", "toString", "(J)Ljava/lang/String;")
      case other =>
        throw new Exception("JVM backend prototype: unsupported Long operator " + other)

  private def boolNot()(using ctx: MethodCtx): Unit =
    boolFromBranch(l => ctx.cw.ifeq(l))

  /** `String`'s `@intrinsic` methods.
    *
    * `+`/`==`/`toLower`/`toUpper` have a direct 1:1 `java.lang.String`
    * counterpart (no semantic gap), so they're compiled the same way
    * Int/Bool arithmetic is: a thin, direct translation, right here.
    *
    * `size`/`get`/`substring`/`indexOf` don't: Jo's contract is explicit
    * that string indices and lengths are Unicode *code point* units, but
    * `java.lang.String` is UTF-16 *code unit* indexed. Bridging that gap is
    * API-level behavior, not language semantics, so it's pushed out to
    * `jo.jvm.runtime.StringOps` (ordinary Jo code over thin `@extern`
    * primitives, in runtime/jvm/Runtime.jo) — compiled here as nothing more
    * than an ordinary static call with `qual` prepended as the first
    * argument, the same as any other function call.
    *
    * `iterator` constructs a real `Iterator[Char]`-implementing object
    * (`jo.jvm.runtime.StringOps.StringIterator`, an ordinary Jo class), so
    * it's dispatched the same way as `size`/`get`/`substring`/`indexOf`.
    */
  private def compileStringOp(qual: Word, name: String, args: List[Word], resultType: JType)(using ctx: MethodCtx): Unit =
    val cw = ctx.cw

    // No `adaptTo` needed in either helper, for the same reason as
    // `compileIntCatPrimitiveOp`: `isStringOwner` already gates this whole
    // function on `jvmType(qual.tpe) == Ref(StringDesc)`, and `+`/`==`'s
    // declared parameter is concretely `String`, so `Erasure`'s `Apply`
    // case already leaves the other operand erased to `String` too.
    def receiver(): Unit = compile(qual)
    def stringArg(w: Word): Unit = compile(w)

    name match
      case "size" => compileStaticCall(runtime.String_size, qual :: Nil, resultType)
      case "get" => compileStaticCall(runtime.String_get, qual :: args, resultType)
      case "substring" => compileStaticCall(runtime.String_substring, qual :: args, resultType)
      case "indexOf" =>
        val from = if args.size > 1 then args(1) else IntLit(0)(args.head.span)
        compileStaticCall(runtime.String_indexOf, qual :: args.head :: from :: Nil, resultType)

      case "+" =>
        receiver(); stringArg(args.head)
        cw.invokevirtual(StringClass, "concat", "(Ljava/lang/String;)Ljava/lang/String;")

      case "==" =>
        receiver(); stringArg(args.head)
        cw.invokevirtual(StringClass, "equals", "(Ljava/lang/Object;)Z")

      case "toLower" =>
        receiver(); cw.invokevirtual(StringClass, "toLowerCase", "()Ljava/lang/String;")

      case "toUpper" =>
        receiver(); cw.invokevirtual(StringClass, "toUpperCase", "()Ljava/lang/String;")

      case "iterator" => compileStaticCall(runtime.String_iterator, qual :: Nil, resultType)

      case other =>
        throw new Exception("JVM backend prototype: unsupported String operator " + other)

  //----------------------------------------------------------------------------
  // Object construction and lambda calls
  //----------------------------------------------------------------------------

  private def compileNew(classType: Type, args: List[Word])(using ctx: MethodCtx): Unit =
    val classSym = classType.classSymbol
    val className = enqueueClass(classSym)
    val cdef = classDefOf(classSym)
    val ctorParamTypes = cdef.funs.find(_.symbol.name == Names.Constructor).map(_.params.map(_.tpe)).getOrElse(Nil)
    ctx.cw.newObj(className)
    ctx.cw.dup()
    // No `adaptTo` needed: a constructor call is an ordinary `Apply` as far
    // as `Erasure` is concerned (`Select(New(tpt), Constructor)` applied to
    // `args`), so each argument is already erased against `ctorParamTypes`
    // — same reasoning as `compileMethodCall`'s argument loop.
    args.foreach(compile)
    ctx.cw.invokespecial(className, Names.Constructor, "(" + ctorParamTypes.map(t => descOf(jvmType(t))).mkString + ")V")

  private def compileLambdaCall(fun: Word, args: List[Word], resultType: JType)(using ctx: MethodCtx): Unit =
    val cw = ctx.cw
    compile(fun); adaptTo(jvmType(fun.tpe), Ref(ObjectDesc), cw)
    cw.checkcast(LambdaClass)
    cw.iconst(args.size)
    cw.anewarray(ObjectClass)
    for (a, i) <- args.zipWithIndex do
      cw.dup()
      cw.iconst(i)
      compile(a)
      adaptTo(jvmType(a.tpe), Ref(ObjectDesc), cw)
      cw.aastore()
    cw.invokeinterface(LambdaClass, "apply", "([Ljava/lang/Object;)Ljava/lang/Object;")
    adaptTo(Ref(ObjectDesc), resultType, cw) // handles the value-drop (pop) when resultType is V

  //----------------------------------------------------------------------------
  // Representation adaptation (boxing / unboxing / checkcast / value-drop)
  //----------------------------------------------------------------------------

  private def adaptTo(actual: JType, expected: JType, cw: CodeWriter): Unit =
    if actual == expected then ()
    else
      (actual, expected) match
        case (_, V) => if actual != V then cw.pop() // value drop
        case (V, Ref(_)) => cw.aconstNull() // e.g. Jo's Unit value used where an Object is expected
        case (V, _) => () // nothing was pushed; caller error if it expected a value

        case (a, Ref(ObjectDesc)) if isIntCat(a) => box(a, cw)
        case (Ref(d), b) if isIntCat(b) && d == ObjectDesc => unbox(b, cw)

        case (a, b) if isIntCat(a) && isIntCat(b) => () // all int-category values share representation

        case (Ref(_), Ref(ObjectDesc)) => () // widening upcast, always safe
        case (Ref(_), Ref(d)) => cw.checkcast(internalNameOf(Ref(d)))

        case _ =>
          throw new Exception("JVM backend prototype: no conversion from " + actual + " to " + expected)

  private def box(t: JType, cw: CodeWriter): Unit =
    val (owner, desc) = t match
      case I => ("java/lang/Integer", "(I)Ljava/lang/Integer;")
      case Z => ("java/lang/Boolean", "(Z)Ljava/lang/Boolean;")
      case B => ("java/lang/Byte", "(B)Ljava/lang/Byte;")
      case C => ("java/lang/Character", "(C)Ljava/lang/Character;")
      case F => ("java/lang/Float", "(F)Ljava/lang/Float;")
      case J => ("java/lang/Long", "(J)Ljava/lang/Long;")
      case _ => throw new Exception("cannot box " + t)
    cw.invokestatic(owner, "valueOf", desc)

  private def unbox(t: JType, cw: CodeWriter): Unit =
    val (owner, meth, desc) = t match
      case I => ("java/lang/Integer", "intValue", "()I")
      case Z => ("java/lang/Boolean", "booleanValue", "()Z")
      case B => ("java/lang/Byte", "byteValue", "()B")
      case C => ("java/lang/Character", "charValue", "()C")
      case F => ("java/lang/Float", "floatValue", "()F")
      case J => ("java/lang/Long", "longValue", "()J")
      case _ => throw new Exception("cannot unbox " + t)
    cw.checkcast(owner)
    cw.invokevirtual(owner, meth, desc)
end JVMCodeGen

object JVMCodeGen:
  val MainClassName = "Main"
