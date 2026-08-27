package jvm

import sast.*
import sast.Trees.*
import sast.Symbols.*

import jvm.ClassFile.*
import jvm.JVMTypes.*

/** Computes JVM class/interface layout and delegates every method body. */
final class ClassBuilder(
  methods: ClassBuilder.MethodBodies,
  runtime: JVMRuntime
)(using defn: Definitions, context: JVMContext):

  def compileClass(cdef: ClassDef): (String, Array[Byte]) =
    val className = context.className(cdef.symbol)
    val isLambda = LambdaABI.isMarkerLambda(cdef)
    val isObject = cdef.symbol.is(Flags.Object)

    val instanceFields = cdef.vals.map { field =>
      FieldOut(AccessFlags.Public, field.symbol.name, JVMTypes.descriptorOf(field.tpt.tpe))
    }
    val singletonFields =
      if isObject then
        FieldOut(
          AccessFlags.Public | AccessFlags.Static | AccessFlags.Final,
          ClassBuilder.SingletonField, "L" + className + ";"
        ) :: Nil
      else Nil
    val fields = singletonFields ++ instanceFields

    val constructor = cdef.funs.find(_.symbol.name == Names.Constructor)
      .map(methods.compileConstructor(_, cdef))

    val otherMethods = cdef.funs.filter(_.symbol.name != Names.Constructor).map { fdef =>
      if isLambda && fdef.symbol.name == "apply" then
        methods.compileLambdaApply(fdef, cdef)
      else methods.compileInstanceMethod(fdef, cdef.self)
    }

    val viewSymbols = cdef.views.flatMap(view => JVMTypes.classOrInterfaceSymbol(view.tpe))
    val declaredInterfaces = viewSymbols.map(context.requireClass)
    val interfaces = LambdaABI.implementedInterfaces(cdef, declaredInterfaces)
    val classInitializer = if isObject then buildObjectInitializer(className) :: Nil else Nil
    val javaBridges = viewSymbols.filter(_.isExternal).flatMap(javaBridgesFor(className, cdef, _))
    val bytes = ClassFile.write(
      className, ObjectClass, interfaces, fields,
      constructor.toList ++ classInitializer ++ otherMethods ++ javaBridges,
      sourceFile = Some(sourceFileName(cdef.symbol))
    )
    className -> bytes

  /** Bridges from a Java interface's own descriptors to the Jo methods
    * implementing them.
    *
    * A Jo class implementing a Java interface — in practice a lambda literal
    * that `ElimCapture` lifted, since `Runnable` and friends are ordinary Jo
    * lambda interfaces once their abstract methods are marked deferred — gets
    * its method descriptors from *Jo* types. Those agree with Java's for most
    * of the mapping, and where they do this emits nothing. Three cases remain:
    *
    *   - Jo `Char` is a code point riding in an `I` slot, not a JVM `char`
    *     (see `JVMTypes.descOf`), so a `char` parameter reads as `I`;
    *   - `jvm.Array[T]` travels as `Object` rather than as `[Ljava/lang/String;`;
    *   - a bounded type variable erases to `Any`, while Java's descriptor names
    *     the bound.
    *
    * `InterfaceBridge` cannot close these: it compares two *Jo* signatures, and
    * both sides agree. The disagreement is with the class file Java actually
    * published, which only the reflected `NativeSpec` records.
    */
  private def javaBridgesFor(className: String, cdef: ClassDef, iface: Symbol): List[MethodOut] =
    for
      method <- iface.classInfo.allMethods if method.is(Flags.Defer)
      spec <- runtime.nativeSpec(method).toList
      impl <- cdef.funs.find(_.symbol.name == method.name).toList
      procType = impl.symbol.tpe.asProcType
      joDescriptor = JVMTypes.methodDescriptor(procType.paramTypes ++ procType.autoTypes, procType.resultType)
      if joDescriptor != spec.desc
    yield
      val javaParams = parseMethodParams(spec.desc)
      val javaResult = parseMethodReturn(spec.desc)
      val joParams = parseMethodParams(joDescriptor)
      val joResult = parseMethodReturn(joDescriptor)

      val cw = new CodeWriter
      cw.aload(0)
      // Slot 0 is `this`; a category-2 argument occupies two slots.
      var slot = 1
      javaParams.zip(joParams).foreach { (javaParam, joParam) =>
        load(javaParam, slot, cw)
        ValueAdaptation.emit(javaParam, joParam, cw)
        slot += (if javaParam == JType.J then 2 else 1)
      }
      cw.invokevirtual(className, method.name, joDescriptor)
      ValueAdaptation.emit(joResult, javaResult, cw)
      emitReturn(javaResult, cw)
      MethodOut(AccessFlags.Public, method.name, spec.desc, Some(cw))

  private def load(tpe: JType, slot: Int, cw: CodeWriter): Unit =
    if isIntCat(tpe) then cw.iload(slot)
    else if tpe == JType.J then cw.lload(slot)
    else cw.aload(slot)

  private def emitReturn(tpe: JType, cw: CodeWriter): Unit =
    tpe match
      case JType.V => cw.returnVoid()
      case JType.J => cw.lreturn()
      case t if isIntCat(t) => cw.ireturn()
      case _ => cw.areturn()

  def compileInterface(idef: InterfaceDef): (String, Array[Byte]) =
    val name = context.className(idef.symbol)
    val abstractMethods = idef.methods.collect {
      case fdef if fdef.symbol.is(Flags.Defer) =>
        val procType = fdef.symbol.tpe.asProcType
        val descriptor = JVMTypes.methodDescriptor(
          procType.paramTypes ++ procType.autoTypes,
          procType.resultType
        )
        MethodOut(AccessFlags.Public | AccessFlags.Abstract, fdef.symbol.name, descriptor, None)
    }
    val bytes = ClassFile.write(
      name, ObjectClass, Nil, Nil, abstractMethods,
      accessFlags = AccessFlags.Public | AccessFlags.Interface | AccessFlags.Abstract,
      sourceFile = Some(sourceFileName(idef.symbol))
    )
    name -> bytes

  /** The file name a class file's `SourceFile` attribute names, taken from
    * the same source the bodies' `LineNumberTable` entries are resolved
    * against (see `JVMContext.sourceOf`) so the two cannot disagree.
    */
  private def sourceFileName(sym: Symbol): String =
    java.nio.file.Paths.get(context.sourceOf(sym).file).getFileName.toString

  /** Eager singleton initialization. Jo rejects cyclic global
    * initialization, so this needs neither a lazy guard nor re-entrancy.
    */
  private def buildObjectInitializer(className: String): MethodOut =
    val writer = new CodeWriter
    writer.newObj(className)
    writer.dup()
    writer.invokespecial(className, Names.Constructor, "()V")
    writer.putstatic(className, ClassBuilder.SingletonField, "L" + className + ";")
    writer.returnVoid()
    MethodOut(AccessFlags.Static, "<clinit>", "()V", Some(writer))

object ClassBuilder:
  val SingletonField = "INSTANCE"

  /** Method-body service required by class layout. The consumer owns this
    * contract; method compilation does not depend on the class compiler's
    * concrete implementation.
    */
  trait MethodBodies:
    def compileConstructor(fdef: FunDef, owner: ClassDef): MethodOut
    def compileInstanceMethod(fdef: FunDef, self: Symbol): MethodOut
    def compileLambdaApply(fdef: FunDef, owner: ClassDef): MethodOut
