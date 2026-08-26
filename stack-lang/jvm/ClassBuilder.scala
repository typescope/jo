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

  def compileClass(cdef: ClassDef, constants: ConstantPool): (String, Array[Byte]) =
    val className = context.className(cdef.symbol)
    val isLambda = LambdaABI.isMarkerLambda(cdef)

    val fields = cdef.vals.map { field =>
      FieldOut(AccessFlags.Public, field.symbol.name, JVMTypes.descriptorOf(field.tpt.tpe))
    }

    val constructor = cdef.funs.find(_.symbol.name == Names.Constructor)
      .map(methods.compileConstructor(_, cdef, constants))

    val otherMethods = cdef.funs.filter(_.symbol.name != Names.Constructor).map { fdef =>
      if isLambda && fdef.symbol.name == "apply" then
        methods.compileLambdaApply(fdef, cdef, constants)
      else methods.compileInstanceMethod(fdef, cdef.self, constants)
    }

    val declaredInterfaces = cdef.views
      .flatMap(view => JVMTypes.classOrInterfaceSymbol(view.tpe))
      .map(context.requireClass)
    val interfaces = LambdaABI.implementedInterfaces(cdef, declaredInterfaces)
    val bytes = ClassFile.write(
      constants, className, ObjectClass, interfaces, fields,
      constructor.toList ++ otherMethods
    )
    className -> bytes

  def compileInterface(idef: InterfaceDef, constants: ConstantPool): (String, Array[Byte]) =
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
      constants, name, ObjectClass, Nil, Nil, abstractMethods,
      accessFlags = AccessFlags.Public | AccessFlags.Interface | AccessFlags.Abstract
    )
    name -> bytes

object ClassBuilder:
  /** Method-body service required by class layout. The consumer owns this
    * contract; method compilation does not depend on the class compiler's
    * concrete implementation.
    */
  trait MethodBodies:
    def compileConstructor(fdef: FunDef, owner: ClassDef, constants: ConstantPool): MethodOut
    def compileInstanceMethod(fdef: FunDef, self: Symbol, constants: ConstantPool): MethodOut
    def compileLambdaApply(fdef: FunDef, owner: ClassDef, constants: ConstantPool): MethodOut
