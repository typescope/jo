package jvm

import org.objectweb.asm.{ClassWriter, Opcodes}
import org.objectweb.asm.tree.*

/** ASM-backed JVM class-file construction. ASM computes constant-pool layout,
  * instruction sizes, branch offsets, maximums, and stack-map frames.
  */
object ClassFile:
  object AccessFlags:
    val Public = Opcodes.ACC_PUBLIC
    val Static = Opcodes.ACC_STATIC
    val Final = Opcodes.ACC_FINAL
    val Super = Opcodes.ACC_SUPER
    val Interface = Opcodes.ACC_INTERFACE
    val Abstract = Opcodes.ACC_ABSTRACT

  type Label = LabelNode

  final class CodeWriter:
    private[ClassFile] val instructions = new InsnList
    private def insn(opcode: Int): Unit = instructions.add(new InsnNode(opcode))
    private def jump(opcode: Int, target: Label): Unit = instructions.add(new JumpInsnNode(opcode, target))

    def newLabel(): Label = new LabelNode
    def mark(label: Label): Unit = instructions.add(label)
    def lineNumber(line: Int): Unit =
      val label = newLabel()
      mark(label)
      instructions.add(new LineNumberNode(line, label))
    def iconst(value: Int): Unit =
      if value >= -1 && value <= 5 then insn(Opcodes.ICONST_0 + value)
      else if value >= Byte.MinValue && value <= Byte.MaxValue then instructions.add(new IntInsnNode(Opcodes.BIPUSH, value))
      else if value >= Short.MinValue && value <= Short.MaxValue then instructions.add(new IntInsnNode(Opcodes.SIPUSH, value))
      else instructions.add(new LdcInsnNode(Integer.valueOf(value)))
    def lconst(value: Long): Unit =
      if value == 0L then insn(Opcodes.LCONST_0)
      else if value == 1L then insn(Opcodes.LCONST_1)
      else instructions.add(new LdcInsnNode(java.lang.Long.valueOf(value)))
    def stringConst(value: String): Unit = instructions.add(new LdcInsnNode(value))
    def aconstNull(): Unit = insn(Opcodes.ACONST_NULL)

    def iload(slot: Int): Unit = instructions.add(new VarInsnNode(Opcodes.ILOAD, slot))
    def lload(slot: Int): Unit = instructions.add(new VarInsnNode(Opcodes.LLOAD, slot))
    def aload(slot: Int): Unit = instructions.add(new VarInsnNode(Opcodes.ALOAD, slot))
    def istore(slot: Int): Unit = instructions.add(new VarInsnNode(Opcodes.ISTORE, slot))
    def lstore(slot: Int): Unit = instructions.add(new VarInsnNode(Opcodes.LSTORE, slot))
    def astore(slot: Int): Unit = instructions.add(new VarInsnNode(Opcodes.ASTORE, slot))

    def dup(): Unit = insn(Opcodes.DUP)
    def pop(): Unit = insn(Opcodes.POP)
    def swap(): Unit = insn(Opcodes.SWAP)
    def iadd(): Unit = insn(Opcodes.IADD)
    def isub(): Unit = insn(Opcodes.ISUB)
    def imul(): Unit = insn(Opcodes.IMUL)
    def idiv(): Unit = insn(Opcodes.IDIV)
    def irem(): Unit = insn(Opcodes.IREM)
    def ineg(): Unit = insn(Opcodes.INEG)
    def iand(): Unit = insn(Opcodes.IAND)
    def ior(): Unit = insn(Opcodes.IOR)
    def ixor(): Unit = insn(Opcodes.IXOR)
    def ishl(): Unit = insn(Opcodes.ISHL)
    def ishr(): Unit = insn(Opcodes.ISHR)
    def ladd(): Unit = insn(Opcodes.LADD)
    def lsub(): Unit = insn(Opcodes.LSUB)
    def lmul(): Unit = insn(Opcodes.LMUL)
    def ldiv(): Unit = insn(Opcodes.LDIV)
    def lrem(): Unit = insn(Opcodes.LREM)
    def lneg(): Unit = insn(Opcodes.LNEG)
    def land(): Unit = insn(Opcodes.LAND)
    def lor(): Unit = insn(Opcodes.LOR)
    def lxor(): Unit = insn(Opcodes.LXOR)
    def lshl(): Unit = insn(Opcodes.LSHL)
    def lshr(): Unit = insn(Opcodes.LSHR)
    def lcmp(): Unit = insn(Opcodes.LCMP)
    def l2i(): Unit = insn(Opcodes.L2I)
    def i2l(): Unit = insn(Opcodes.I2L)
    def i2b(): Unit = insn(Opcodes.I2B)

    def gotoL(target: Label): Unit = jump(Opcodes.GOTO, target)
    def ifeq(target: Label): Unit = jump(Opcodes.IFEQ, target)
    def ifne(target: Label): Unit = jump(Opcodes.IFNE, target)
    def ifnull(target: Label): Unit = jump(Opcodes.IFNULL, target)
    def ifnonnull(target: Label): Unit = jump(Opcodes.IFNONNULL, target)
    def ifIcmp(condition: String, target: Label): Unit = jump(conditionOpcode(condition, Opcodes.IF_ICMPEQ), target)
    def ifCond(condition: String, target: Label): Unit = jump(conditionOpcode(condition, Opcodes.IFEQ), target)
    def ifAcmp(condition: String, target: Label): Unit =
      jump(condition match
        case "eq" => Opcodes.IF_ACMPEQ
        case "ne" => Opcodes.IF_ACMPNE
        case other => throw new IllegalArgumentException("Unsupported reference comparison: " + other), target)

    private def conditionOpcode(condition: String, equalOpcode: Int): Int =
      equalOpcode + (condition match
        case "eq" => 0
        case "ne" => 1
        case "lt" => 2
        case "ge" => 3
        case "gt" => 4
        case "le" => 5
        case other => throw new IllegalArgumentException("Unsupported comparison: " + other))

    def ireturn(): Unit = insn(Opcodes.IRETURN)
    def lreturn(): Unit = insn(Opcodes.LRETURN)
    def areturn(): Unit = insn(Opcodes.ARETURN)
    def returnVoid(): Unit = insn(Opcodes.RETURN)
    def athrow(): Unit = insn(Opcodes.ATHROW)
    def newObj(owner: String): Unit = instructions.add(new TypeInsnNode(Opcodes.NEW, owner))
    def checkcast(owner: String): Unit = instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, owner))
    def instanceOf(owner: String): Unit = instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, owner))
    def anewarray(element: String): Unit = instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, element))
    def aaload(): Unit = insn(Opcodes.AALOAD)
    def aastore(): Unit = insn(Opcodes.AASTORE)
    def arraylength(): Unit = insn(Opcodes.ARRAYLENGTH)

    def getstatic(owner: String, name: String, desc: String): Unit = field(Opcodes.GETSTATIC, owner, name, desc)
    def putstatic(owner: String, name: String, desc: String): Unit = field(Opcodes.PUTSTATIC, owner, name, desc)
    def getfield(owner: String, name: String, desc: String): Unit = field(Opcodes.GETFIELD, owner, name, desc)
    def putfield(owner: String, name: String, desc: String): Unit = field(Opcodes.PUTFIELD, owner, name, desc)
    private def field(opcode: Int, owner: String, name: String, desc: String): Unit =
      instructions.add(new FieldInsnNode(opcode, owner, name, desc))

    def invokevirtual(owner: String, name: String, desc: String): Unit = method(Opcodes.INVOKEVIRTUAL, owner, name, desc, false)
    def invokespecial(owner: String, name: String, desc: String): Unit = method(Opcodes.INVOKESPECIAL, owner, name, desc, false)
    def invokestatic(owner: String, name: String, desc: String): Unit = method(Opcodes.INVOKESTATIC, owner, name, desc, false)
    def invokeinterface(owner: String, name: String, desc: String): Unit = method(Opcodes.INVOKEINTERFACE, owner, name, desc, true)
    private def method(opcode: Int, owner: String, name: String, desc: String, isInterface: Boolean): Unit =
      instructions.add(new MethodInsnNode(opcode, owner, name, desc, isInterface))

  final case class MethodOut(accessFlags: Int, name: String, desc: String, code: Option[CodeWriter])
  final case class FieldOut(accessFlags: Int, name: String, desc: String)

  def write(
    thisClass: String, superClass: String, interfaces: List[String],
    fields: List[FieldOut], methods: List[MethodOut],
    accessFlags: Int = AccessFlags.Public | AccessFlags.Super,
    sourceFile: Option[String] = None
  ): Array[Byte] =
    val writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS):
      override protected def getCommonSuperClass(left: String, right: String): String =
        if left == right then left else "java/lang/Object"
    writer.visit(Opcodes.V17, accessFlags, thisClass, null, superClass, interfaces.toArray)
    sourceFile.foreach(file => writer.visitSource(file, null))
    fields.foreach: field =>
      writer.visitField(field.accessFlags, field.name, field.desc, null, null).visitEnd()
    methods.foreach: method =>
      val visitor = writer.visitMethod(method.accessFlags, method.name, method.desc, null, null)
      method.code.foreach: code =>
        visitor.visitCode()
        code.instructions.accept(visitor)
        visitor.visitMaxs(0, 0)
      visitor.visitEnd()
    writer.visitEnd()
    writer.toByteArray
end ClassFile
