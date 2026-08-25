package jvm

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import jvm.ClassFile.*
import jvm.JVMTypes.*

/** Method-body service required by class layout.
  *
  * `JVMCodeGen` implements this temporarily. The interface is the seam along
  * which the short-lived `JVMMethodCompiler` will replace it.
  */
trait JVMMethodCompilation:
  def compileConstructor(fdef: FunDef, owner: ClassDef, constants: ConstantPool): MethodOut
  def compileInstanceMethod(fdef: FunDef, self: Symbol, constants: ConstantPool): MethodOut
  def compileLambdaApply(fdef: FunDef, owner: ClassDef, constants: ConstantPool): MethodOut

/** Computes JVM class/interface layout and delegates every method body. */
final class JVMClassCompiler(
  backend: JVMBackendContext,
  methods: JVMMethodCompilation,
  jvmType: Type => JType,
  methodDesc: (List[Type], Type) => String,
  lambdaClass: String
)(using Definitions):

  def compileClass(cdef: ClassDef, constants: ConstantPool): (String, Array[Byte]) =
    val className = backend.className(cdef.symbol)
    val isLambda =
      cdef.symbol.is(Flags.Synthetic) && cdef.views.isEmpty &&
        cdef.funs.exists(_.symbol.name == "apply")

    val fields = cdef.vals.map { field =>
      FieldOut(AccessFlags.Public, field.symbol.name, descOf(jvmType(field.tpt.tpe)))
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
      .map(backend.requireClass)
    val interfaces = (if isLambda then lambdaClass :: Nil else Nil) ++ declaredInterfaces
    val bytes = ClassFile.write(
      constants, className, ObjectClass, interfaces, fields,
      constructor.toList ++ otherMethods
    )
    className -> bytes

  def compileInterface(idef: InterfaceDef, constants: ConstantPool): (String, Array[Byte]) =
    val name = backend.className(idef.symbol)
    val abstractMethods = idef.methods.collect {
      case fdef if fdef.symbol.is(Flags.Defer) =>
        val procType = fdef.symbol.tpe.asProcType
        val descriptor = methodDesc(
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
