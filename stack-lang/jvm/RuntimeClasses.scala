package jvm

import jvm.ClassFile.*

/** The two hand-written `.class` files every compiled program needs,
  * independent of the user's Jo source:
  *
  *   - `Node`: a plain 3-field (next/key/value) cons cell, used by
  *     `jo.jvm.runtime.ParamSupport` (see runtime/jvm/Runtime.jo) to
  *     implement context-parameter lookup.
  *   - `Lambda`: the marker interface every ElimCapture-lifted lambda class
  *     implements, with a single arity-erased `Object apply(Object[])`
  *     method defined by [[LambdaABI]].
  * These are emitted directly via the low-level ClassFile writer rather than
  * compiled from Jo source, since the compiler itself (not user code) is
  * their only "caller" — `jo.jvm.runtime.Runtime.jo` references them purely
  * through `@extern` FFI declarations.
  */
object RuntimeClasses:
  def nodeClass(): (String, Array[Byte]) =
    val fields = List(
      FieldOut(AccessFlags.Public, "next", "Ljava/lang/Object;"),
      FieldOut(AccessFlags.Public, "key", "Ljava/lang/Object;"),
      FieldOut(AccessFlags.Public, "value", "Ljava/lang/Object;"),
      FieldOut(AccessFlags.Public | AccessFlags.Static, "NULL", "Ljava/lang/Object;"),
    )
    val cw = new CodeWriter
    cw.aload(0)
    cw.invokespecial("java/lang/Object", "<init>", "()V")
    cw.returnVoid()
    val ctor = MethodOut(AccessFlags.Public, "<init>", "()V", Some(cw))
    val bytes = ClassFile.write("Node", "java/lang/Object", Nil, fields, List(ctor))
    "Node" -> bytes

  def lambdaInterface(): (String, Array[Byte]) =
    val applyMethod = MethodOut(
      AccessFlags.Public | AccessFlags.Abstract, "apply", "([Ljava/lang/Object;)Ljava/lang/Object;", None
    )
    val bytes = ClassFile.write(
      "Lambda", "java/lang/Object", Nil, Nil, List(applyMethod),
      accessFlags = AccessFlags.Public | AccessFlags.Interface | AccessFlags.Abstract
    )
    "Lambda" -> bytes
end RuntimeClasses
