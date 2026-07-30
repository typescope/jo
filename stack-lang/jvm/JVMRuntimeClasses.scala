package jvm

import jvm.ClassFile.*

/** The two hand-written `.class` files every compiled program needs,
  * independent of the user's Jo source:
  *
  *   - `Node`: a plain 3-field (next/key/value) cons cell, used by
  *     `jo.jvm.runtime.ParamSupport` (see runtime/jvm/Runtime.jo) to
  *     implement context-parameter lookup without needing general JVM array
  *     codegen in this prototype.
  *   - `Lambda`: the marker interface every ElimCapture-lifted lambda class
  *     implements, with a single arity-erased `Object apply(Object[])`
  *     method — see JVMCodeGen's design note on lambda calls.
  *
  * These are emitted directly via the low-level ClassFile writer rather than
  * compiled from Jo source, since the compiler itself (not user code) is
  * their only "caller" — `jo.jvm.runtime.Runtime.jo` references them purely
  * through `@extern` FFI declarations.
  */
object JVMRuntimeClasses:
  def nodeClass(): (String, Array[Byte]) =
    val cp = new ConstantPool
    val fields = List(
      FieldOut(AccessFlags.Public, "next", "Ljava/lang/Object;"),
      FieldOut(AccessFlags.Public, "key", "Ljava/lang/Object;"),
      FieldOut(AccessFlags.Public, "value", "Ljava/lang/Object;"),
      FieldOut(AccessFlags.Public | AccessFlags.Static, "NULL", "Ljava/lang/Object;"),
    )
    val cw = new CodeWriter(cp)
    cw.aload(0)
    cw.invokespecial("java/lang/Object", "<init>", "()V")
    cw.returnVoid()
    val (code, ms, ml) = cw.finish()
    val ctor = MethodOut(AccessFlags.Public, "<init>", "()V", Some((code, ms, ml)))
    val bytes = ClassFile.write(cp, "Node", "java/lang/Object", Nil, fields, List(ctor))
    "Node" -> bytes

  def lambdaInterface(): (String, Array[Byte]) =
    val cp = new ConstantPool
    val applyMethod = MethodOut(
      AccessFlags.Public | AccessFlags.Abstract, "apply", "([Ljava/lang/Object;)Ljava/lang/Object;", None
    )
    val bytes = ClassFile.write(
      cp, "Lambda", "java/lang/Object", Nil, Nil, List(applyMethod),
      accessFlags = AccessFlags.Public | AccessFlags.Interface | AccessFlags.Abstract
    )
    "Lambda" -> bytes
end JVMRuntimeClasses
