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
    case _ => ObjectClass

  def jvmType(tp: Type): JType =
    // Jo's user-visible `Unit` (a real nominal type with a value, `jo_pass`)
    // is distinct from the SAST's internal `VoidType` marker (used for
    // statement-context/dropped-value typing), but both need zero bytecode
    // representation in this backend, so they're treated identically here.
    //
    // `BottomType` (e.g. a `Return`'s own `.tpe`) also maps to `V`: a
    // Bottom-typed word never completes normally — it always ends in a
    // terminal instruction (`ireturn`/`areturn`/`athrow`) — so nothing is
    // ever actually produced for a caller to adapt or drop. Concretely,
    // this matters for e.g. `Encoded(Return(...))(VoidType)`, which the
    // frontend inserts to adapt a `Return` used as an if-branch statement:
    // without this case, `adaptTo` would (wrongly) believe a real value
    // needs popping after the `Return` already consumed the whole stack via
    // its own return instruction, underflowing.
    if tp.isVoidType || tp.isBottomType then V
    else
      // `.approx` dealiases and widens term references (e.g. an `Ident`'s
      // `.tpe` is a `StaticRef` to the *symbol*, not its value type).
      tp.approx match
        case StaticRef(sym) if sym == defn.Unit_type   => V
        case StaticRef(sym) if sym == defn.Int_type    => I
        case StaticRef(sym) if sym == defn.Bool_type   => Z
        case StaticRef(sym) if sym == defn.Byte_type   => B
        case StaticRef(sym) if sym == defn.Char_type   => C
        case StaticRef(sym) if sym == defn.Float_type  => F
        case StaticRef(sym) if sym == defn.Long_type   => J
        case StaticRef(sym) if sym == defn.String_type => Ref(StringDesc)
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

  private val topLevelWork = new mutable.ArrayDeque[Symbol]()
  private val topLevelSeen = mutable.Set.empty[Symbol]
  private val topLevelName = mutable.Map.empty[Symbol, String]
  private val usedNames    = mutable.Set.empty[String]

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

    while topLevelWork.nonEmpty do
      val sym = topLevelWork.removeHead()
      if !topLevelSeen(sym) then
        topLevelSeen += sym
        if !isNativeOrIntrinsic(sym) then
          mainClassMethods += compileTopLevelFunDef(funDefOf(sym), cp)

    while classWork.nonEmpty do
      val sym = classWork.removeHead()
      compileClass(classDefOf(sym), cp)

    // Synthetic entry point: `public static void main(String[] args)`
    mainClassMethods += buildJavaMain(cp)

    val mainBytes = ClassFile.write(cp, MainClassName, ObjectClass, Nil, Nil, mainClassMethods.toList)
    (classFiles.toMap + (MainClassName -> mainBytes), MainClassName)

  private def buildJavaMain(cp: ConstantPool): MethodOut =
    val cw = new CodeWriter(cp)
    cw.touchLocal(0) // String[] args
    cw.invokestatic(MainClassName, topLevelName(resolve(runtime.start)), "()V")
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
    * (its end-of-block jump target, its declared result JType) — used to
    * compile `TailCallOpt`'s `_tco_loop` labeled blocks and local
    * `Return(label, ...)` "break out of this block" jumps, as opposed to a
    * `Return` to the enclosing function itself.
    */
  private class MethodCtx(
    val cw: CodeWriter, val slots: Slots, val returnType: JType,
    val selfSym: Option[Symbol], val argsArraySlot: Option[Int] = None,
    val localLabels: mutable.Map[Symbol, (ClassFile.Label, JType)] = mutable.Map.empty
  )

  //----------------------------------------------------------------------------
  // Top-level (static) function compilation
  //----------------------------------------------------------------------------

  private def compileTopLevelFunDef(fdef: FunDef, cp: ConstantPool): MethodOut =
    val sym = fdef.symbol
    val procType = sym.tpe.asProcType
    val cw = new CodeWriter(cp)
    val slots = new Slots

    for param <- fdef.allParams do slots.bind(param, jvmType(param.tpe))
    if slots.used > 0 then cw.touchLocal(slots.used - 1) // see Slots.used
    val localTypes = for local <- fdef.locals yield local -> slots.bind(local, jvmType(local.tpe))

    val resType = jvmType(procType.resultType)
    given MethodCtx = new MethodCtx(cw, slots, resType, selfSym = None)

    emitLocalDefaults(localTypes, cw)
    compile(fdef.body)
    // A `Bottom`-returning body ends in a genuine non-returning instruction
    // (currently always `athrow`, via `throwAny`); emitting a trailing
    // return after it would be unreachable bytecode operating on an empty
    // stack, which the legacy verifier can reject.
    if !procType.resultType.isBottomType then emitReturn(resType)

    val (code, maxStack, maxLocals) = cw.finish()
    val desc = methodDesc(procType.paramTypes ++ procType.autoTypes, procType.resultType)
    MethodOut(AccessFlags.Public | AccessFlags.Static, topLevelName(sym), desc, Some((code, maxStack, maxLocals)))

  private def emitReturn(t: JType)(using ctx: MethodCtx): Unit =
    t match
      case V => ctx.cw.returnVoid()
      case r if isIntCat(r) => ctx.cw.ireturn()
      case Ref(_) => ctx.cw.areturn()
      case J | F => throw new Exception("long/float return not supported in this prototype")

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
        case J | F => throw new Exception("long/float locals not supported in this prototype")

  //----------------------------------------------------------------------------
  // Lambda-lifted class compilation (ElimCapture output)
  //----------------------------------------------------------------------------

  private def compileClass(cdef: ClassDef, cp: ConstantPool): Unit =
    val className = classSimpleName(cdef.symbol)
    val isLambda = cdef.funs.exists(f => f.symbol.name == "apply")

    val fieldOuts = cdef.vals.map(f => FieldOut(AccessFlags.Public, f.symbol.name, descOf(jvmType(f.tpt.tpe))))

    val ctorFdefOpt = cdef.funs.find(_.symbol.name == Names.Constructor)
    val ctorOut = ctorFdefOpt.map(compileConstructor(_, cdef, cp))

    val otherMethods = cdef.funs.filter(_.symbol.name != Names.Constructor).map { fdef =>
      if isLambda && fdef.symbol.name == "apply" then compileLambdaApply(fdef, cdef, cp)
      else compileInstanceMethod(fdef, cdef, cp)
    }

    val interfaces = if isLambda then LambdaClass :: Nil else Nil
    val methods = ctorOut.toList ++ otherMethods
    val bytes = ClassFile.write(cp, className, ObjectClass, interfaces, fieldOuts, methods)
    classFiles(className) = bytes

  /** Jo constructors are modeled as functions that return the constructed
    * `this` (see ElimCapture); a JVM `<init>` is void and operates on the
    * object `new` already allocated. We translate the specific shape
    * ElimCapture always produces: a `Block` of `FieldAssign`s (self.field =
    * ctorParam) followed by a trailing `Ident(self)`.
    */
  private def compileConstructor(fdef: FunDef, cdef: ClassDef, cp: ConstantPool): MethodOut =
    val className = classSimpleName(cdef.symbol)
    val cw = new CodeWriter(cp)
    val slots = new Slots
    slots.bind(cdef.self, Ref(ObjectDesc)) // reserve slot 0 for `this`

    val ctorParams = fdef.params // constructor's own explicit params are the field values
    for p <- ctorParams do slots.bind(p, jvmType(p.tpe))
    if slots.used > 0 then cw.touchLocal(slots.used - 1) // see Slots.used

    cw.aload(0)
    cw.invokespecial(ObjectClass, Names.Constructor, "()V")

    def emitInit(word: Word): Unit =
      word match
        case Block(words) =>
          words.foreach(emitInit)
        case FieldAssign(Select(_, fname), rhs) =>
          cw.aload(0)
          compileInline(rhs, slots, cw, cdef.self)
          cw.putfield(className, fname, descOf(jvmType(rhs.tpe)))
        case _: Ident =>
          () // trailing `self` result — a JVM <init> has nothing to return
        case other =>
          throw new Exception("Unexpected shape in synthesized constructor body: " + other)

    emitInit(fdef.body)
    cw.returnVoid()

    val (code, maxStack, maxLocals) = cw.finish()
    val desc = "(" + ctorParams.map(p => descOf(jvmType(p.tpe))).mkString + ")V"
    MethodOut(AccessFlags.Public, Names.Constructor, desc, Some((code, maxStack, maxLocals)))

  /** Compile a class instance method with the natural signature implied by
    * its Jo parameter/result types (slot 0 = `this`).
    */
  private def compileInstanceMethod(fdef: FunDef, cdef: ClassDef, cp: ConstantPool): MethodOut =
    val sym = fdef.symbol
    val procType = sym.tpe.asProcType
    val cw = new CodeWriter(cp)
    val slots = new Slots
    slots.bind(cdef.self, Ref(ObjectDesc))
    for param <- fdef.allParams do slots.bind(param, jvmType(param.tpe))
    if slots.used > 0 then cw.touchLocal(slots.used - 1) // see Slots.used
    val localTypes = for local <- fdef.locals yield local -> slots.bind(local, jvmType(local.tpe))

    val resType = jvmType(procType.resultType)
    given MethodCtx = new MethodCtx(cw, slots, resType, selfSym = Some(cdef.self))

    emitLocalDefaults(localTypes, cw)
    compile(fdef.body)
    emitReturn(resType)

    val (code, maxStack, maxLocals) = cw.finish()
    val desc = methodDesc(procType.paramTypes ++ procType.autoTypes, procType.resultType)
    MethodOut(AccessFlags.Public, sym.name, desc, Some((code, maxStack, maxLocals)))

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

  private def compile(word: Word)(using ctx: MethodCtx): Unit =
    word match
      case Literal(c) => compileLiteral(c, jvmType(word.tpe))

      case Ident(sym) => compileIdent(sym, jvmType(word.tpe))

      case Assign(Ident(sym), rhs, _) =>
        val pt = jvmType(sym.tpe)
        compile(rhs)
        adaptTo(jvmType(rhs.tpe), pt, ctx.cw)
        storeLocal(pt, ctx.slots(sym), ctx.cw)

      case FieldAssign(Select(qual, name), rhs) =>
        compile(qual)
        val owner = fieldOwnerName(qual)
        val fdesc = descOf(jvmType(rhs.tpe))
        compile(rhs)
        ctx.cw.putfield(owner, name, fdesc)

      case If(cond, thenp, elsep) => compileIf(cond, thenp, elsep, jvmType(word.tpe))

      case While(cond, body) => compileWhile(cond, body)

      case Block(words) =>
        words match
          case Nil => ()
          case init :+ last =>
            init.foreach(compile)
            compile(last)

      case Labeled(label, resultType, body) =>
        val endL = ctx.cw.newLabel()
        val rt = jvmType(resultType)
        ctx.localLabels(label) = (endL, rt)
        compile(body)
        ctx.cw.mark(endL)

      case Return(label, value) =>
        if label.is(Flags.Fun) then
          compile(value)
          adaptTo(jvmType(value.tpe), ctx.returnType, ctx.cw)
          emitReturn(ctx.returnType)
        else
          // Local "break out of this Labeled block" jump (e.g. one iteration
          // of a TailCallOpt `_tco_loop`), not a function return.
          val (target, rt) = ctx.localLabels(label)
          compile(value)
          adaptTo(jvmType(value.tpe), rt, ctx.cw)
          ctx.cw.gotoL(target)

      case Encoded(repr) =>
        val target = jvmType(word.tpe)
        compile(repr)
        adaptTo(jvmType(repr.tpe), target, ctx.cw)

      case apply: Apply => compileApply(apply)

      case TypeApply(fun, _) => compile(fun)

      case sel @ Select(qual, name) =>
        // A bare field read (not part of an Apply's function position), e.g.
        // a lifted lambda reading one of its captured fields.
        compile(qual)
        val owner = fieldOwnerName(qual)
        ctx.cw.getfield(owner, name, descOf(jvmType(sel.tpe)))

      case other =>
        throw new Exception("JVM backend prototype: unsupported node " + other.getClass.getSimpleName + " -- " + other.show)

  /** The JVM owner class for a field access. `jvmType`'s "erase everything
    * non-primitive to Object" rule (right for method params/results, which
    * this backend dispatches by symbol, not by the JVM's own type system)
    * is wrong here: `getfield`/`putfield` need the *exact* declaring class.
    * Fields are currently only reachable on `self` (a lambda reading/
    * writing one of its own captured-variable fields) — general field
    * access on an arbitrary object needs full class support (see "Known
    * limitations" in docs/jips/jvm-backend.md) and isn't handled yet.
    */
  private def fieldOwnerName(qual: Word)(using ctx: MethodCtx): String =
    qual match
      case Ident(sym) if ctx.selfSym.contains(sym) => classSimpleName(sym.owner)
      case _ => internalNameOf(jvmType(qual.tpe))

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
      case Constant.Int(n)  => ctx.cw.iconst(n.toInt)
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
    if isIntCat(t) then cw.iload(slot) else cw.aload(slot)

  private def storeLocal(t: JType, slot: Int, cw: CodeWriter): Unit =
    if isIntCat(t) then cw.istore(slot) else cw.astore(slot)

  private def compileIf(cond: Word, thenp: Word, elsep: Word, resultType: JType)(using ctx: MethodCtx): Unit =
    compile(cond)
    adaptTo(jvmType(cond.tpe), Z, ctx.cw)
    val elseL = ctx.cw.newLabel()
    val endL = ctx.cw.newLabel()
    val hasElse = !elsep.isEmpty
    ctx.cw.ifeq(if hasElse then elseL else endL)
    val afterCond = ctx.cw.currentStack
    compile(thenp)
    // A `Bottom`-typed branch (e.g. ending in `abort(...)`) never actually
    // reaches `endL` — it always ends in its own terminal instruction
    // (`athrow`/a function `return`) first. Adapting its value or jumping
    // to the merge point anyway would be dead code that disagrees with the
    // other branch about the stack depth/contents at `endL`, which the
    // verifier rejects even though that disagreement can never be observed
    // at runtime.
    val thenTerminal = thenp.tpe.isBottomType
    if !thenTerminal then adaptTo(jvmType(thenp.tpe), resultType, ctx.cw)
    if hasElse then
      if !thenTerminal then ctx.cw.gotoL(endL)
      ctx.cw.mark(elseL)
      ctx.cw.setStack(afterCond)
      compile(elsep)
      if !elsep.tpe.isBottomType then adaptTo(jvmType(elsep.tpe), resultType, ctx.cw)
    ctx.cw.mark(endL)

  private def compileWhile(cond: Word, body: Word)(using ctx: MethodCtx): Unit =
    val beginL = ctx.cw.newLabel()
    val endL = ctx.cw.newLabel()
    ctx.cw.mark(beginL)
    compile(cond)
    adaptTo(jvmType(cond.tpe), Z, ctx.cw)
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

        case other =>
          throw new Exception("JVM backend prototype: unsupported call target " + other)

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

  private def compileIdentApply(sym: Symbol, args: List[Word], resultType: Type)(using ctx: MethodCtx): Unit =
    if sym == runtime.cast then
      compile(args.head)
      adaptTo(jvmType(args.head.tpe), jvmType(resultType), ctx.cw)

    else if sym == runtime.paramKey then
      compileParamKey(args.head)

    else if sym == runtime.refEq then
      compile(args.head); adaptTo(jvmType(args.head.tpe), Ref(ObjectDesc), ctx.cw)
      compile(args(1));   adaptTo(jvmType(args(1).tpe), Ref(ObjectDesc), ctx.cw)
      boolFromBranch(l => ctx.cw.ifAcmp("eq", l))

    else if sym == runtime.isNull then
      compile(args.head); adaptTo(jvmType(args.head.tpe), Ref(ObjectDesc), ctx.cw)
      boolFromBranch(l => ctx.cw.ifnull(l))

    else if sym == runtime.throwAny then
      compile(args.head)
      ctx.cw.checkcast(ThrowableClass)
      ctx.cw.athrow()

    else if sym == defn.jo_pass then
      () // Unit's only value maps to V (nothing) in this backend, same as
         // any other Unit-typed expression — nothing to push. `adaptTo`
         // already knows how to widen a V into a real Object (aconst_null)
         // wherever a caller needs the Unit value as data.

    else if runtime.nativeSpec(sym).isDefined then
      compileNativeCall(runtime.nativeSpec(sym).get, args)

    else
      compileStaticCall(sym, args)

  /** Compile a call to an ordinary top-level Jo function (`invokestatic`
    * against `Main`, enqueuing it for compilation if not already reached).
    */
  private def compileStaticCall(sym: Symbol, args: List[Word])(using ctx: MethodCtx): Unit =
    val name = enqueueTopLevel(sym)
    val procType = sym.tpe.asProcType
    for (arg, pt) <- args.zip(procType.paramTypes ++ procType.autoTypes) do
      compile(arg)
      adaptTo(jvmType(arg.tpe), jvmType(pt), ctx.cw)
    ctx.cw.invokestatic(MainClassName, name, methodDesc(procType.paramTypes ++ procType.autoTypes, procType.resultType))

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

  private def compileNativeCall(spec: runtime.NativeSpec, args: List[Word])(using ctx: MethodCtx): Unit =
    val cw = ctx.cw
    spec.kind match
      case "static" =>
        val paramTs = parseMethodParams(spec.desc)
        for (a, pt) <- args.zip(paramTs) do { compile(a); adaptTo(jvmType(a.tpe), pt, cw) }
        cw.invokestatic(spec.owner, spec.member, spec.desc)

      case "virtual" | "interface" =>
        val recv :: rest = args: @unchecked
        compile(recv); adaptTo(jvmType(recv.tpe), Ref(ObjectDesc), cw); cw.checkcast(spec.owner)
        val paramTs = parseMethodParams(spec.desc)
        for (a, pt) <- rest.zip(paramTs) do { compile(a); adaptTo(jvmType(a.tpe), pt, cw) }
        if spec.kind == "virtual" then cw.invokevirtual(spec.owner, spec.member, spec.desc)
        else cw.invokeinterface(spec.owner, spec.member, spec.desc)

      case "special" =>
        cw.newObj(spec.owner)
        cw.dup()
        val paramTs = parseMethodParams(spec.desc)
        for (a, pt) <- args.zip(paramTs) do { compile(a); adaptTo(jvmType(a.tpe), pt, cw) }
        cw.invokespecial(spec.owner, "<init>", spec.desc)

      case "getstatic" =>
        cw.getstatic(spec.owner, spec.member, spec.desc)

      case "putstatic" =>
        val t = parseFieldDesc(spec.desc)
        compile(args.head); adaptTo(jvmType(args.head.tpe), t, cw)
        cw.putstatic(spec.owner, spec.member, spec.desc)

      case "getfield" =>
        compile(args.head); adaptTo(jvmType(args.head.tpe), Ref(ObjectDesc), cw); cw.checkcast(spec.owner)
        cw.getfield(spec.owner, spec.member, spec.desc)

      case "putfield" =>
        compile(args.head); adaptTo(jvmType(args.head.tpe), Ref(ObjectDesc), cw); cw.checkcast(spec.owner)
        val t = parseFieldDesc(spec.desc)
        compile(args(1)); adaptTo(jvmType(args(1).tpe), t, cw)
        cw.putfield(spec.owner, spec.member, spec.desc)

      case other =>
        throw new Exception("Unknown @extern kind: " + other)

  //----------------------------------------------------------------------------
  // Primitive numeric/boolean operators (Int/Bool/Byte/Char intrinsics)
  //----------------------------------------------------------------------------

  private def compilePrimitiveOp(qual: Word, name: String, args: List[Word], resultType: JType)(using ctx: MethodCtx): Unit =
    val cw = ctx.cw
    val qt = jvmType(qual.tpe)

    def binIntOp(emit: () => Unit): Unit =
      compile(qual); adaptTo(jvmType(qual.tpe), I, cw)
      compile(args.head); adaptTo(jvmType(args.head.tpe), I, cw)
      emit()

    def cmpOp(cond: String): Unit =
      compile(qual); adaptTo(jvmType(qual.tpe), I, cw)
      compile(args.head); adaptTo(jvmType(args.head.tpe), I, cw)
      boolFromBranch(l => cw.ifIcmp(cond, l))

    name match
      case "+" => binIntOp(cw.iadd)
      case "-" if args.nonEmpty => binIntOp(cw.isub)
      case "-" | "~-" => compile(qual); adaptTo(jvmType(qual.tpe), I, cw); cw.ineg()
      case "~~" => compile(qual); adaptTo(jvmType(qual.tpe), I, cw); cw.iconst(-1); cw.ixor()
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
      case "!" | "~!" => compile(qual); adaptTo(jvmType(qual.tpe), Z, cw); boolNot()
      case "&&" =>
        // short-circuiting and/or are already lowered to If by earlier phases
        // in practice, but handle directly in case they reach here.
        compile(qual); adaptTo(jvmType(qual.tpe), Z, cw)
        val elseL = cw.newLabel(); val endL = cw.newLabel()
        cw.ifeq(elseL)
        compile(args.head); adaptTo(jvmType(args.head.tpe), Z, cw)
        cw.gotoL(endL)
        cw.mark(elseL); cw.iconst(0)
        cw.mark(endL)
      case "||" =>
        compile(qual); adaptTo(jvmType(qual.tpe), Z, cw)
        val elseL = cw.newLabel(); val endL = cw.newLabel()
        cw.ifeq(elseL)
        cw.iconst(1)
        cw.gotoL(endL)
        cw.mark(elseL)
        compile(args.head); adaptTo(jvmType(args.head.tpe), Z, cw)
        cw.mark(endL)
      case "toChar" | "toInt" =>
        // Char shares Int's full range in this backend (no truncation to
        // 16 bits — Jo's Char is a full Unicode code point, not a UTF-16
        // code unit), and Byte->Int/Char->Int are already the same
        // representation, so both are genuine no-ops.
        compile(qual); adaptTo(jvmType(qual.tpe), I, cw)
      case "toByte" =>
        // Jo's Byte is unsigned 8-bit ([0, 255], lib/Byte.jo) sharing Int's
        // representation, so converting *to* Byte must mask to 8 bits
        // (unlike JVM's own signed `i2b`, which would sign-extend and give
        // the wrong answer for values >= 128).
        compile(qual); adaptTo(jvmType(qual.tpe), I, cw)
        cw.iconst(0xFF); cw.iand()
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
        compile(qual); adaptTo(jvmType(qual.tpe), I, cw)
        cw.invokestatic(owner, "toString", desc)
      case other =>
        throw new Exception("JVM backend prototype: unsupported primitive operator " + other)

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
    * `iterator` needs a real `Iterator[Char]`-implementing object and isn't
    * implemented here (see "Known limitations" in docs/jips/jvm-backend.md).
    */
  private def compileStringOp(qual: Word, name: String, args: List[Word], resultType: JType)(using ctx: MethodCtx): Unit =
    val cw = ctx.cw

    def receiver(): Unit = { compile(qual); adaptTo(jvmType(qual.tpe), Ref(StringDesc), cw) }
    def stringArg(w: Word): Unit = { compile(w); adaptTo(jvmType(w.tpe), Ref(StringDesc), cw) }

    name match
      case "size" => compileStaticCall(runtime.String_size, qual :: Nil)
      case "get" => compileStaticCall(runtime.String_get, qual :: args)
      case "substring" => compileStaticCall(runtime.String_substring, qual :: args)
      case "indexOf" =>
        val from = if args.size > 1 then args(1) else IntLit(0)(args.head.span)
        compileStaticCall(runtime.String_indexOf, qual :: args.head :: from :: Nil)

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
    for (a, pt) <- args.zip(ctorParamTypes) do
      compile(a); adaptTo(jvmType(a.tpe), jvmType(pt), ctx.cw)
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
