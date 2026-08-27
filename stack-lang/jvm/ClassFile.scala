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
    private def insn(opcode: Int): Unit = add(new InsnNode(opcode))
    private def jump(opcode: Int, target: Label): Unit = add(new JumpInsnNode(opcode, target))

    /** The source line the instructions being appended are attributed to,
      * and the one waiting to take over. -1 means "none".
      */
    private var currentLine = -1
    private var pendingLine = -1

    /** Append an instruction, first materializing any line marker waiting
      * for one to attach to.
      *
      * Deferring the marker this way keeps the `LineNumberTable` free of
      * entries that cover no instruction: expression lowering asks for a
      * line on entering every node, and a node whose operands start on a
      * later line than the node itself would otherwise put two entries at
      * the same bytecode offset.
      */
    private def add(node: AbstractInsnNode): Unit =
      if pendingLine >= 0 then
        val label = new LabelNode
        instructions.add(label)
        instructions.add(new LineNumberNode(pendingLine, label))
        currentLine = pendingLine
        pendingLine = -1

      instructions.add(node)

    def newLabel(): Label = new LabelNode

    def mark(label: Label): Unit =
      instructions.add(label)
      // A marked label is a jump target, so control can arrive here from any
      // predecessor and the line covering the preceding instruction says
      // nothing about this position. Forget it, so that the next
      // `lineNumber` writes a real entry instead of being deduplicated
      // against the fall-through predecessor's line.
      currentLine = -1
      pendingLine = -1

    /** Attribute the instructions that follow to `line`.
      *
      * Callers re-assert a line freely — an instruction emitted after its
      * operands has to put its own line back (see `LineNumbers`) — so this
      * is a no-op when `line` already covers this position.
      */
    def lineNumber(line: Int): Unit =
      pendingLine = if line == currentLine then -1 else line

    def iconst(value: Int): Unit =
      if value >= -1 && value <= 5 then insn(Opcodes.ICONST_0 + value)
      else if value >= Byte.MinValue && value <= Byte.MaxValue then add(new IntInsnNode(Opcodes.BIPUSH, value))
      else if value >= Short.MinValue && value <= Short.MaxValue then add(new IntInsnNode(Opcodes.SIPUSH, value))
      else add(new LdcInsnNode(Integer.valueOf(value)))
    def lconst(value: Long): Unit =
      if value == 0L then insn(Opcodes.LCONST_0)
      else if value == 1L then insn(Opcodes.LCONST_1)
      else add(new LdcInsnNode(java.lang.Long.valueOf(value)))
    def dconst(value: Double): Unit =
      // Raw bits, not `== 0.0`: that is also true of -0.0, which `dconst_0`
      // does not produce and which `1.0 / x` can tell apart.
      if java.lang.Double.doubleToRawLongBits(value) == 0L then insn(Opcodes.DCONST_0)
      else if value == 1.0 then insn(Opcodes.DCONST_1)
      else add(new LdcInsnNode(java.lang.Double.valueOf(value)))
    def stringConst(value: String): Unit = add(new LdcInsnNode(value))
    def aconstNull(): Unit = insn(Opcodes.ACONST_NULL)

    def iload(slot: Int): Unit = add(new VarInsnNode(Opcodes.ILOAD, slot))
    def lload(slot: Int): Unit = add(new VarInsnNode(Opcodes.LLOAD, slot))
    def dload(slot: Int): Unit = add(new VarInsnNode(Opcodes.DLOAD, slot))
    def aload(slot: Int): Unit = add(new VarInsnNode(Opcodes.ALOAD, slot))
    def istore(slot: Int): Unit = add(new VarInsnNode(Opcodes.ISTORE, slot))
    def lstore(slot: Int): Unit = add(new VarInsnNode(Opcodes.LSTORE, slot))
    def dstore(slot: Int): Unit = add(new VarInsnNode(Opcodes.DSTORE, slot))
    def astore(slot: Int): Unit = add(new VarInsnNode(Opcodes.ASTORE, slot))

    def dup(): Unit = insn(Opcodes.DUP)
    def pop(): Unit = insn(Opcodes.POP)
    // A category-2 value (`long`, `double`) occupies two operand-stack words.
    def pop2(): Unit = insn(Opcodes.POP2)
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
    def dadd(): Unit = insn(Opcodes.DADD)
    def dsub(): Unit = insn(Opcodes.DSUB)
    def dmul(): Unit = insn(Opcodes.DMUL)
    def ddiv(): Unit = insn(Opcodes.DDIV)
    def drem(): Unit = insn(Opcodes.DREM)
    def dneg(): Unit = insn(Opcodes.DNEG)
    // `dcmpg` yields 1 for NaN and `dcmpl` yields -1, so which one is correct
    // depends on the comparison: each is chosen so an unordered pair answers
    // false. See `PrimitiveOps.compileFloatOp`.
    def dcmpg(): Unit = insn(Opcodes.DCMPG)
    def dcmpl(): Unit = insn(Opcodes.DCMPL)
    def i2d(): Unit = insn(Opcodes.I2D)
    def l2d(): Unit = insn(Opcodes.L2D)
    def d2i(): Unit = insn(Opcodes.D2I)
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
    def dreturn(): Unit = insn(Opcodes.DRETURN)
    def areturn(): Unit = insn(Opcodes.ARETURN)
    def returnVoid(): Unit = insn(Opcodes.RETURN)
    def athrow(): Unit = insn(Opcodes.ATHROW)
    def newObj(owner: String): Unit = add(new TypeInsnNode(Opcodes.NEW, owner))
    def checkcast(owner: String): Unit = add(new TypeInsnNode(Opcodes.CHECKCAST, owner))
    def instanceOf(owner: String): Unit = add(new TypeInsnNode(Opcodes.INSTANCEOF, owner))
    def anewarray(element: String): Unit = add(new TypeInsnNode(Opcodes.ANEWARRAY, element))
    def newByteArray(): Unit = add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE))
    def aaload(): Unit = insn(Opcodes.AALOAD)
    def aastore(): Unit = insn(Opcodes.AASTORE)
    def baload(): Unit = insn(Opcodes.BALOAD)
    def bastore(): Unit = insn(Opcodes.BASTORE)
    def arraylength(): Unit = insn(Opcodes.ARRAYLENGTH)

    def getstatic(owner: String, name: String, desc: String): Unit = field(Opcodes.GETSTATIC, owner, name, desc)
    def putstatic(owner: String, name: String, desc: String): Unit = field(Opcodes.PUTSTATIC, owner, name, desc)
    def getfield(owner: String, name: String, desc: String): Unit = field(Opcodes.GETFIELD, owner, name, desc)
    def putfield(owner: String, name: String, desc: String): Unit = field(Opcodes.PUTFIELD, owner, name, desc)
    private def field(opcode: Int, owner: String, name: String, desc: String): Unit =
      add(new FieldInsnNode(opcode, owner, name, desc))

    def invokevirtual(owner: String, name: String, desc: String): Unit = method(Opcodes.INVOKEVIRTUAL, owner, name, desc, false)
    def invokespecial(owner: String, name: String, desc: String): Unit = method(Opcodes.INVOKESPECIAL, owner, name, desc, false)
    def invokestatic(owner: String, name: String, desc: String): Unit = method(Opcodes.INVOKESTATIC, owner, name, desc, false)
    def invokeinterface(owner: String, name: String, desc: String): Unit = method(Opcodes.INVOKEINTERFACE, owner, name, desc, true)
    private def method(opcode: Int, owner: String, name: String, desc: String, isInterface: Boolean): Unit =
      add(new MethodInsnNode(opcode, owner, name, desc, isInterface))

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
