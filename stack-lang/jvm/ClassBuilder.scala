package jvm

import sast.*
import sast.Trees.*
import sast.Symbols.*

import jvm.ClassFile.*
import jvm.JVMTypes.*

/** Computes JVM class/interface layout and delegates every method body. */
final class ClassBuilder(
  methods: ClassBuilder.MethodBodies
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

    val declaredInterfaces = cdef.views
      .flatMap(view => JVMTypes.classOrInterfaceSymbol(view.tpe))
      .map(context.requireClass)
    val interfaces = LambdaABI.implementedInterfaces(cdef, declaredInterfaces)
    val classInitializer = if isObject then buildObjectInitializer(className) :: Nil else Nil
    val bytes = ClassFile.write(
      className, ObjectClass, interfaces, fields,
      constructor.toList ++ classInitializer ++ otherMethods,
      sourceFile = Some(java.nio.file.Paths.get(cdef.symbol.source.file).getFileName.toString)
    )
    className -> bytes

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
      sourceFile = Some(java.nio.file.Paths.get(idef.symbol.source.file).getFileName.toString)
    )
    name -> bytes

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
