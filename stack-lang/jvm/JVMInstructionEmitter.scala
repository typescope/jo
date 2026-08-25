package jvm

/** Instruction-level service consumed by JVM lowering.
  *
  * The current implementation is [[ClassFile.CodeWriter]]. Keeping this
  * contract here makes bytecode encoding replaceable without letting a
  * future emitter library dictate lowering or class-layout policy.
  */
trait JVMInstructionEmitter:
  def newLabel(): ClassFile.Label
  def mark(label: ClassFile.Label): Unit
  def touchLocal(slot: Int, widthTwo: Boolean = false): Unit
  def setStack(depth: Int): Unit
  def currentStack: Int
  def iconst(value: Int): Unit
  def lconst(value: Long): Unit
  def stringConst(value: String): Unit
  def aconstNull(): Unit
  def iload(slot: Int): Unit
  def lload(slot: Int): Unit
  def aload(slot: Int): Unit
  def istore(slot: Int): Unit
  def lstore(slot: Int): Unit
  def astore(slot: Int): Unit
  def dup(): Unit
  def pop(): Unit
  def swap(): Unit
  def iadd(): Unit
  def isub(): Unit
  def imul(): Unit
  def idiv(): Unit
  def irem(): Unit
  def ineg(): Unit
  def iand(): Unit
  def ior(): Unit
  def ixor(): Unit
  def ishl(): Unit
  def ishr(): Unit
  def ladd(): Unit
  def lsub(): Unit
  def lmul(): Unit
  def ldiv(): Unit
  def lrem(): Unit
  def lneg(): Unit
  def land(): Unit
  def lor(): Unit
  def lxor(): Unit
  def lshl(): Unit
  def lshr(): Unit
  def lcmp(): Unit
  def l2i(): Unit
  def i2l(): Unit
  def gotoL(target: ClassFile.Label): Unit
  def ifeq(target: ClassFile.Label): Unit
  def ifne(target: ClassFile.Label): Unit
  def ifnull(target: ClassFile.Label): Unit
  def ifnonnull(target: ClassFile.Label): Unit
  def ifIcmp(condition: String, target: ClassFile.Label): Unit
  def ifAcmp(condition: String, target: ClassFile.Label): Unit
  def ifCond(condition: String, target: ClassFile.Label): Unit
  def ireturn(): Unit
  def lreturn(): Unit
  def areturn(): Unit
  def returnVoid(): Unit
  def athrow(): Unit
  def newObj(internalClassName: String): Unit
  def checkcast(internalClassName: String): Unit
  def instanceOf(internalClassName: String): Unit
  def anewarray(elementInternalClassName: String): Unit
  def aaload(): Unit
  def aastore(): Unit
  def arraylength(): Unit
  def getstatic(owner: String, name: String, descriptor: String): Unit
  def putstatic(owner: String, name: String, descriptor: String): Unit
  def getfield(owner: String, name: String, descriptor: String): Unit
  def putfield(owner: String, name: String, descriptor: String): Unit
  def invokevirtual(owner: String, name: String, descriptor: String): Unit
  def invokespecial(owner: String, name: String, descriptor: String): Unit
  def invokestatic(owner: String, name: String, descriptor: String): Unit
  def invokeinterface(owner: String, name: String, descriptor: String): Unit
  def finish(): (Array[Byte], Int, Int)
