package jvm

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.Type

import jvm.ClassFile.*
import jvm.JVMTypes.*
import jvm.JVMTypes.JType.*

/** The JVM calling convention for closure-converted lambdas.
  *
  * Semantic closure conversion remains in `ElimCapture`; this object owns
  * only the backend ABI shared by class layout, adapter methods, and call
  * sites. Replacing the Object-array convention or adopting invokedynamic
  * should therefore change this boundary rather than general lowering.
  */
object LambdaABI:
  val interfaceName = "Lambda"
  val applyName = "apply"
  val applyDescriptor = "([Ljava/lang/Object;)Ljava/lang/Object;"

  /** The marker interface's own class file.
    *
    * The one class file the backend still writes by hand rather than
    * compiling from Jo source. Its `apply` takes a real `Object[]`, and no
    * Jo type has that representation — `jo.Array[T]` and `jvm.Array[T]`
    * both travel as `Object` (see `JVMTypes.representationOf`) — so
    * declaring this interface in `runtime/jvm/Runtime.jo` would weaken the
    * ABI to `(Ljava/lang/Object;)Ljava/lang/Object;` and cost every closure
    * call a `checkcast` on entry. It lives here, with the ABI that defines
    * it, rather than in a separate module of hand-written runtime classes.
    */
  def interfaceClassFile(): (String, Array[Byte]) =
    val applyMethod = MethodOut(
      AccessFlags.Public | AccessFlags.Abstract, applyName, applyDescriptor, None
    )
    val bytes = ClassFile.write(
      interfaceName, ObjectClass, Nil, Nil, List(applyMethod),
      accessFlags = AccessFlags.Public | AccessFlags.Interface | AccessFlags.Abstract
    )
    interfaceName -> bytes

  def isMarkerLambda(cdef: ClassDef): Boolean =
    cdef.symbol.is(Flags.Synthetic) && cdef.views.isEmpty &&
      cdef.funs.exists(_.symbol.name == applyName)

  def implementedInterfaces(cdef: ClassDef, declared: List[String]): List[String] =
    (if isMarkerLambda(cdef) then interfaceName :: Nil else Nil) ++ declared

  def unpackParameters(
    parameters: List[Symbol], slots: MethodSlots, argumentsSlot: Int,
    jvmType: Type => JType, writer: CodeWriter
  )(using Definitions): Unit =
    slots.reserveUpTo(2) // slot 0 = this, slot 1 = Object[] arguments
    parameters.zipWithIndex.foreach { (parameter, index) =>
      val parameterType = jvmType(parameter.tpe)
      val slot = slots.bind(parameter, parameterType)
      writer.aload(argumentsSlot)
      writer.iconst(index)
      writer.aaload()
      ValueAdaptation.emit(Ref(ObjectDesc), parameterType, writer)
      store(parameterType, slot, writer)
    }

  /** Emit a lambda call already normalized by `Lowering`.
    *
    * Every argument is already represented as Object. Result conversion is
    * explicit in the surrounding lowered tree. `atCallSite` re-asserts the
    * call's own source line once the arguments — which have moved it to
    * their own — are on the stack.
    */
  def emitLoweredCall(
    call: Word, compile: Word => MethodBuilder.Flow, atCallSite: () => Unit, writer: CodeWriter
  ): Unit =
    val Apply(function, arguments, automaticArguments) = call: @unchecked
    val allArguments = arguments ++ automaticArguments
    compile(function)
    writer.checkcast(interfaceName)
    writer.iconst(allArguments.size)
    writer.anewarray(ObjectClass)
    allArguments.zipWithIndex.foreach { (argument, index) =>
      writer.dup()
      writer.iconst(index)
      compile(argument)
      writer.aastore()
    }
    atCallSite()
    writer.invokeinterface(interfaceName, applyName, applyDescriptor)

  private def store(tpe: JType, slot: Int, writer: CodeWriter): Unit =
    if isIntCat(tpe) then writer.istore(slot)
    else if tpe == J then writer.lstore(slot)
    else if tpe == D then writer.dstore(slot)
    else writer.astore(slot)
