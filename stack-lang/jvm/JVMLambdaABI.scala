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
    jvmType: Type => JType, writer: JVMInstructionEmitter
  ): Unit =
    slots.reserveUpTo(2) // slot 0 = this, slot 1 = Object[] arguments
    parameters.zipWithIndex.foreach { (parameter, index) =>
      val parameterType = jvmType(parameter.tpe)
      val slot = slots.bind(parameter, parameterType)
      writer.aload(argumentsSlot)
      writer.iconst(index)
      writer.aaload()
      JVMAdaptation.emit(Ref(ObjectDesc), parameterType, writer)
      store(parameterType, slot, writer)
    }

  /** Emit a lambda call already normalized by `JVMLowering`.
    *
    * Every argument is already represented as Object. Result conversion is
    * explicit in the surrounding lowered tree.
    */
  def emitLoweredCall(call: Word, compile: Word => Unit, writer: JVMInstructionEmitter): Unit =
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
    writer.invokeinterface(interfaceName, applyName, applyDescriptor)

  private def store(tpe: JType, slot: Int, writer: JVMInstructionEmitter): Unit =
    if isIntCat(tpe) then writer.istore(slot)
    else if tpe == J then writer.lstore(slot)
    else writer.astore(slot)
