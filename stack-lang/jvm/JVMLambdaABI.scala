package jvm

import sast.*
import sast.Trees.*
import sast.Symbols.*
import sast.Types.*

import jvm.JVMInstructionEmitter
import jvm.JVMTypes.*
import jvm.JVMTypes.JType.*

/** The JVM calling convention for closure-converted lambdas.
  *
  * Semantic closure conversion remains in `ElimCapture`; this object owns
  * only the backend ABI shared by class layout, adapter methods, and call
  * sites. Replacing the Object-array convention or adopting invokedynamic
  * should therefore change this boundary rather than general lowering.
  */
final class JVMLambdaABI(using Definitions):
  val interfaceName = "Lambda"
  val applyName = "apply"
  val applyDescriptor = "([Ljava/lang/Object;)Ljava/lang/Object;"

  def isMarkerLambda(cdef: ClassDef): Boolean =
    cdef.symbol.is(Flags.Synthetic) && cdef.views.isEmpty &&
      cdef.funs.exists(_.symbol.name == applyName)

  def implementedInterfaces(cdef: ClassDef, declared: List[String]): List[String] =
    (if isMarkerLambda(cdef) then interfaceName :: Nil else Nil) ++ declared

  def unpackParameters(
    parameters: List[Symbol], slots: JVMMethodSlots, argumentsSlot: Int,
    jvmType: Type => JType, adapt: (JType, JType, JVMInstructionEmitter) => Unit,
    writer: JVMInstructionEmitter
  ): Unit =
    slots.reserveUpTo(2) // slot 0 = this, slot 1 = Object[] arguments
    parameters.zipWithIndex.foreach { (parameter, index) =>
      val parameterType = jvmType(parameter.tpe)
      val slot = slots.bind(parameter, parameterType)
      writer.aload(argumentsSlot)
      writer.iconst(index)
      writer.aaload()
      adapt(Ref(ObjectDesc), parameterType, writer)
      store(parameterType, slot, writer)
    }

  def emitCall(
    function: Word, arguments: List[Word], resultType: JType,
    compile: Word => Unit, jvmType: Type => JType,
    adapt: (JType, JType, JVMInstructionEmitter) => Unit, writer: JVMInstructionEmitter
  ): Unit =
    compile(function)
    adapt(jvmType(function.tpe), Ref(ObjectDesc), writer)
    writer.checkcast(interfaceName)
    writer.iconst(arguments.size)
    writer.anewarray(ObjectClass)
    arguments.zipWithIndex.foreach { (argument, index) =>
      writer.dup()
      writer.iconst(index)
      compile(argument)
      adapt(jvmType(argument.tpe), Ref(ObjectDesc), writer)
      writer.aastore()
    }
    writer.invokeinterface(interfaceName, applyName, applyDescriptor)
    adapt(Ref(ObjectDesc), resultType, writer)

  private def store(tpe: JType, slot: Int, writer: JVMInstructionEmitter): Unit =
    if isIntCat(tpe) then writer.istore(slot)
    else if tpe == J then writer.lstore(slot)
    else writer.astore(slot)
