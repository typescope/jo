package jvm

import scala.collection.mutable

/** A minimal, dependency-free JVM class file writer.
  *
  * Only the subset of the class file format needed by [[JVMCodeGen]] is
  * implemented: no generics/signature attributes, no annotations, no
  * StackMapTable.
  *
  * Class files are emitted with major version 49 (Java 5). That predates the
  * requirement (introduced in version 50 / Java 6) for methods to carry an
  * explicit `StackMapTable` attribute, so the JVM falls back to its legacy
  * type-inferring verifier. Every current JVM still accepts old class file
  * versions, so this sidesteps implementing stack-map-frame computation
  * entirely — the same trade-off toy/education bytecode emitters commonly
  * make. A production backend would emit modern class files with computed
  * frames instead (see docs/jips/jvm-backend.md).
  */
object ClassFile:
  val MinorVersion = 0
  val MajorVersion = 49 // Java 5 — legacy verifier, no StackMapTable required

  object AccessFlags:
    val Public    = 0x0001
    val Static    = 0x0008
    val Final     = 0x0010
    val Super     = 0x0020
    val Interface = 0x0200
    val Abstract  = 0x0400

  //----------------------------------------------------------------------------
  // Constant pool
  //----------------------------------------------------------------------------

  sealed trait ConstantPoolEntry:
    /** Number of constant-pool slots this entry occupies (Long/Double take 2). */
    def width: Int = 1

  object ConstantPoolEntry:
    case class Utf8(s: String) extends ConstantPoolEntry
    case class ClassRef(nameIdx: Int) extends ConstantPoolEntry
    case class NameAndType(nameIdx: Int, descIdx: Int) extends ConstantPoolEntry
    case class Fieldref(classIdx: Int, ntIdx: Int) extends ConstantPoolEntry
    case class Methodref(classIdx: Int, ntIdx: Int) extends ConstantPoolEntry
    case class InterfaceMethodref(classIdx: Int, ntIdx: Int) extends ConstantPoolEntry
    case class StringRef(utf8Idx: Int) extends ConstantPoolEntry
    case class IntegerEntry(v: Int) extends ConstantPoolEntry
    case class FloatEntry(v: Float) extends ConstantPoolEntry
    case class LongEntry(v: Long) extends ConstantPoolEntry:
      override def width = 2
    case class DoubleEntry(v: Double) extends ConstantPoolEntry:
      override def width = 2

  /** Builds a class file constant pool, deduplicating entries. */
  final class ConstantPool:
    // Slot 0 is reserved by the JVM spec; real entries start at index 1.
    private val entries = new mutable.ArrayBuffer[ConstantPoolEntry]()
    private val index = mutable.Map.empty[ConstantPoolEntry, Int]
    private var nextIdx = 1

    private def intern(e: ConstantPoolEntry): Int =
      index.get(e) match
        case Some(i) => i
        case None =>
          val i = nextIdx
          entries += e
          index(e) = i
          nextIdx += e.width
          i

    def utf8(s: String): Int = intern(ConstantPoolEntry.Utf8(s))
    def classRef(internalName: String): Int = intern(ConstantPoolEntry.ClassRef(utf8(internalName)))
    def nameAndType(name: String, desc: String): Int = intern(ConstantPoolEntry.NameAndType(utf8(name), utf8(desc)))
    def fieldref(owner: String, name: String, desc: String): Int =
      intern(ConstantPoolEntry.Fieldref(classRef(owner), nameAndType(name, desc)))
    def methodref(owner: String, name: String, desc: String): Int =
      intern(ConstantPoolEntry.Methodref(classRef(owner), nameAndType(name, desc)))
    def interfaceMethodref(owner: String, name: String, desc: String): Int =
      intern(ConstantPoolEntry.InterfaceMethodref(classRef(owner), nameAndType(name, desc)))
    def stringConst(s: String): Int = intern(ConstantPoolEntry.StringRef(utf8(s)))
    def intConst(v: Int): Int = intern(ConstantPoolEntry.IntegerEntry(v))
    def floatConst(v: Float): Int = intern(ConstantPoolEntry.FloatEntry(v))
    def longConst(v: Long): Int = intern(ConstantPoolEntry.LongEntry(v))

    private[ClassFile] def write(out: ByteWriter): Unit =
      out.u2(nextIdx) // constant_pool_count = highest index + 1
      for e <- entries do
        e match
          case ConstantPoolEntry.Utf8(s) =>
            out.u1(1); out.utf8Bytes(s)
          case ConstantPoolEntry.IntegerEntry(v) =>
            out.u1(3); out.u4(v)
          case ConstantPoolEntry.FloatEntry(v) =>
            out.u1(4); out.u4(java.lang.Float.floatToIntBits(v))
          case ConstantPoolEntry.LongEntry(v) =>
            out.u1(5); out.u8(v)
          case ConstantPoolEntry.DoubleEntry(v) =>
            out.u1(6); out.u8(java.lang.Double.doubleToLongBits(v))
          case ConstantPoolEntry.ClassRef(nameIdx) =>
            out.u1(7); out.u2(nameIdx)
          case ConstantPoolEntry.StringRef(utf8Idx) =>
            out.u1(8); out.u2(utf8Idx)
          case ConstantPoolEntry.Fieldref(c, nt) =>
            out.u1(9); out.u2(c); out.u2(nt)
          case ConstantPoolEntry.Methodref(c, nt) =>
            out.u1(10); out.u2(c); out.u2(nt)
          case ConstantPoolEntry.InterfaceMethodref(c, nt) =>
            out.u1(11); out.u2(c); out.u2(nt)
          case ConstantPoolEntry.NameAndType(n, d) =>
            out.u1(12); out.u2(n); out.u2(d)
  end ConstantPool

  //----------------------------------------------------------------------------
  // Byte output helper
  //----------------------------------------------------------------------------

  final class ByteWriter:
    val buf = new mutable.ArrayBuffer[Byte]()

    def pos: Int = buf.size

    def u1(v: Int): Unit = buf += v.toByte
    def u2(v: Int): Unit = { buf += ((v >>> 8) & 0xff).toByte; buf += (v & 0xff).toByte }
    def u4(v: Int): Unit =
      buf += ((v >>> 24) & 0xff).toByte
      buf += ((v >>> 16) & 0xff).toByte
      buf += ((v >>> 8) & 0xff).toByte
      buf += (v & 0xff).toByte
    def u8(v: Long): Unit = { u4(((v >>> 32) & 0xffffffffL).toInt); u4((v & 0xffffffffL).toInt) }

    def utf8Bytes(s: String): Unit =
      val bytes = s.getBytes("UTF-8")
      u2(bytes.length)
      buf ++= bytes

    /** Patch a previously-written u2 (big-endian) at an absolute position. */
    def patchU2(at: Int, v: Int): Unit =
      buf(at) = ((v >>> 8) & 0xff).toByte
      buf(at + 1) = (v & 0xff).toByte

    def toByteArray: Array[Byte] = buf.toArray
  end ByteWriter

  //----------------------------------------------------------------------------
  // Labels and code buffer (bytecode assembler)
  //----------------------------------------------------------------------------

  final class Label
  private case class Patch(operandPos: Int, opcodePos: Int, target: Label)

  /** Accumulates bytecode for a single method body, tracking stack depth and
    * resolving branch targets after the fact (a simple two-pass assembler,
    * mirroring the approach in native/Assembly.scala but for JVM opcodes).
    */
  final class CodeWriter(val constants: ConstantPool):
    private val cp = constants
    private val out = new ByteWriter
    private var curStack = 0
    private var maxStackSeen = 0
    private var maxLocalsSeen = 0
    private val labelPos = mutable.Map.empty[Label, Int]
    private val patches = new mutable.ArrayBuffer[Patch]()

    def pos: Int = out.pos
    def newLabel(): Label = new Label
    def mark(l: Label): Unit = labelPos(l) = out.pos

    def touchLocal(slot: Int, widthTwo: Boolean = false): Unit =
      val top = slot + (if widthTwo then 2 else 1)
      if top > maxLocalsSeen then maxLocalsSeen = top

    def maxLocals: Int = maxLocalsSeen

    /** Adjust the tracked operand-stack depth. Call after each instruction. */
    def stackDelta(d: Int): Unit =
      curStack += d
      assert(curStack >= 0, "stack underflow, depth = " + curStack)
      if curStack > maxStackSeen then maxStackSeen = curStack

    /** Explicitly note the stack depth at a forward jump target reached only
      * via `goto`/branch (i.e. where fall-through analysis can't see it) —
      * used at the start of else/end blocks so maxStack stays adequate even
      * though our tracker is a simple linear pass, not real dataflow.
      */
    def setStack(n: Int): Unit = curStack = n
    def currentStack: Int = curStack

    private def op(b: Int): Unit = out.u1(b)

    // ---- constants ----
    def iconst(n: Int): Unit =
      n match
        case -1              => op(2); stackDelta(1)
        case _ if n >= 0 && n <= 5 => op(3 + n); stackDelta(1)
        case _ if n >= Byte.MinValue && n <= Byte.MaxValue => op(16); out.u1(n); stackDelta(1)
        case _ if n >= Short.MinValue && n <= Short.MaxValue => op(17); out.u2(n & 0xffff); stackDelta(1)
        case _ => ldc(cp.intConst(n))

    def ldc(cpIndex: Int): Unit =
      if cpIndex <= 0xff then { op(18); out.u1(cpIndex) }
      else { op(19); out.u2(cpIndex) }
      stackDelta(1)

    def aconstNull(): Unit = { op(1); stackDelta(1) }

    // ---- locals ----
    def iload(slot: Int): Unit = { touchLocal(slot); loadOp(21, slot); stackDelta(1) }
    def aload(slot: Int): Unit = { touchLocal(slot); loadOp(25, slot); stackDelta(1) }
    def istore(slot: Int): Unit = { touchLocal(slot); storeOp(54, slot); stackDelta(-1) }
    def astore(slot: Int): Unit = { touchLocal(slot); storeOp(58, slot); stackDelta(-1) }

    private def loadOp(baseWide: Int, slot: Int): Unit =
      val shortBase = if baseWide == 21 then 26 else 42 // iload_0/aload_0
      if slot <= 3 then op(shortBase + slot)
      else { op(baseWide); out.u1(slot) }

    private def storeOp(baseWide: Int, slot: Int): Unit =
      val shortBase = if baseWide == 54 then 59 else 75 // istore_0/astore_0
      if slot <= 3 then op(shortBase + slot)
      else { op(baseWide); out.u1(slot) }

    // ---- stack ops ----
    def dup(): Unit = { op(89); stackDelta(1) }
    def pop(): Unit = { op(87); stackDelta(-1) }
    def swap(): Unit = { op(95); stackDelta(0) }

    // ---- arithmetic (int) ----
    def iadd(): Unit = { op(96); stackDelta(-1) }
    def isub(): Unit = { op(100); stackDelta(-1) }
    def imul(): Unit = { op(104); stackDelta(-1) }
    def idiv(): Unit = { op(108); stackDelta(-1) }
    def irem(): Unit = { op(112); stackDelta(-1) }
    def ineg(): Unit = { op(116); stackDelta(0) }
    def iand(): Unit = { op(126); stackDelta(-1) }
    def ior(): Unit  = { op(128); stackDelta(-1) }
    def ixor(): Unit = { op(130); stackDelta(-1) }
    def ishl(): Unit = { op(120); stackDelta(-1) }
    def ishr(): Unit = { op(122); stackDelta(-1) }

    // ---- control flow ----
    private def branch(opcode: Int, target: Label, stackEffect: Int): Unit =
      val opcodePos = out.pos
      op(opcode)
      val operandPos = out.pos
      out.u2(0) // placeholder, patched in resolve()
      patches += Patch(operandPos, opcodePos, target)
      stackDelta(stackEffect)

    def gotoL(target: Label): Unit = branch(167, target, 0)
    def ifeq(target: Label): Unit = branch(153, target, -1)
    def ifne(target: Label): Unit = branch(154, target, -1)
    def ifnull(target: Label): Unit = branch(198, target, -1)
    def ifnonnull(target: Label): Unit = branch(199, target, -1)
    def ifIcmp(cond: String, target: Label): Unit =
      val opcode = cond match
        case "eq" => 159
        case "ne" => 160
        case "lt" => 161
        case "ge" => 162
        case "gt" => 163
        case "le" => 164
      branch(opcode, target, -2)
    def ifAcmp(cond: String, target: Label): Unit =
      val opcode = cond match
        case "eq" => 165
        case "ne" => 166
      branch(opcode, target, -2)

    def ireturn(): Unit = { op(172); stackDelta(-1) }
    def areturn(): Unit = { op(176); stackDelta(-1) }
    def returnVoid(): Unit = { op(177); stackDelta(0) }
    def athrow(): Unit = { op(191); stackDelta(0) }

    // ---- objects ----
    def newObj(internalClassName: String): Unit = { op(187); out.u2(cp.classRef(internalClassName)); stackDelta(1) }
    def checkcast(internalClassName: String): Unit = { op(192); out.u2(cp.classRef(internalClassName)); stackDelta(0) }
    def instanceOf(internalClassName: String): Unit = { op(193); out.u2(cp.classRef(internalClassName)); stackDelta(0) }

    def anewarray(elemInternalClassName: String): Unit = { op(189); out.u2(cp.classRef(elemInternalClassName)); stackDelta(0) }
    def aaload(): Unit = { op(50); stackDelta(-1) }
    def aastore(): Unit = { op(83); stackDelta(-3) }

    def getstatic(owner: String, name: String, desc: String): Unit =
      op(178); out.u2(cp.fieldref(owner, name, desc)); stackDelta(descWords(desc))
    def putstatic(owner: String, name: String, desc: String): Unit =
      op(179); out.u2(cp.fieldref(owner, name, desc)); stackDelta(-descWords(desc))
    def getfield(owner: String, name: String, desc: String): Unit =
      op(180); out.u2(cp.fieldref(owner, name, desc)); stackDelta(descWords(desc) - 1)
    def putfield(owner: String, name: String, desc: String): Unit =
      op(181); out.u2(cp.fieldref(owner, name, desc)); stackDelta(-descWords(desc) - 1)

    def invokevirtual(owner: String, name: String, desc: String): Unit =
      op(182); out.u2(cp.methodref(owner, name, desc)); stackDelta(invokeEffect(desc, hasReceiver = true))
    def invokespecial(owner: String, name: String, desc: String): Unit =
      op(183); out.u2(cp.methodref(owner, name, desc)); stackDelta(invokeEffect(desc, hasReceiver = true))
    def invokestatic(owner: String, name: String, desc: String): Unit =
      op(184); out.u2(cp.methodref(owner, name, desc)); stackDelta(invokeEffect(desc, hasReceiver = false))
    def invokeinterface(owner: String, name: String, desc: String): Unit =
      val argWords = paramWords(desc)
      op(185); out.u2(cp.interfaceMethodref(owner, name, desc)); out.u1(argWords + 1); out.u1(0)
      stackDelta(invokeEffect(desc, hasReceiver = true))

    /** Descriptor width in operand-stack words (0 for `V`, 2 for J/D, else 1). */
    private def descWords(desc: String): Int =
      desc match
        case "V" => 0
        case "J" | "D" => 2
        case _ => 1

    private def paramWords(methodDesc: String): Int =
      val params = methodDesc.substring(1, methodDesc.indexOf(')'))
      var i = 0
      var words = 0
      while i < params.length do
        params(i) match
          case 'J' | 'D' => words += 2; i += 1
          case 'L' => i = params.indexOf(';', i) + 1; words += 1
          case '[' =>
            var j = i
            while params(j) == '[' do j += 1
            if params(j) == 'L' then j = params.indexOf(';', j)
            i = j + 1
            words += 1
          case _ => i += 1; words += 1
      words

    private def invokeEffect(methodDesc: String, hasReceiver: Boolean): Int =
      val retIdx = methodDesc.indexOf(')') + 1
      val retDesc = methodDesc.substring(retIdx)
      val argWords = paramWords(methodDesc)
      val ret = descWords(retDesc)
      ret - argWords - (if hasReceiver then 1 else 0)

    /** Resolve all recorded branch targets and return the finished bytecode
      * along with computed max_stack / max_locals.
      */
    def finish(): (Array[Byte], Int, Int) =
      for Patch(operandPos, opcodePos, target) <- patches do
        val t = labelPos.getOrElse(target, throw new Exception("Unresolved label"))
        val offset = t - opcodePos
        out.patchU2(operandPos, offset & 0xffff)
      (out.toByteArray, math.max(maxStackSeen, 1), math.max(maxLocalsSeen, 1))
  end CodeWriter

  //----------------------------------------------------------------------------
  // Class / field / method assembly
  //----------------------------------------------------------------------------

  final case class MethodOut(
    accessFlags: Int, name: String, desc: String,
    code: Option[(Array[Byte], Int, Int)] // (bytecode, maxStack, maxLocals); None for abstract
  )

  final case class FieldOut(accessFlags: Int, name: String, desc: String)

  /** Assemble and return the bytes of a complete `.class` file.
    *
    * `cp` must be the same [[ConstantPool]] instance used by the
    * [[CodeWriter]]s that produced `methods`' bytecode, since method bodies
    * reference constants by index into it.
    */
  def write(
    cp: ConstantPool,
    thisClass: String,
    superClass: String,
    interfaces: List[String],
    fields: List[FieldOut],
    methods: List[MethodOut],
    accessFlags: Int = AccessFlags.Public | AccessFlags.Super
  ): Array[Byte] =
    val thisIdx = cp.classRef(thisClass)
    val superIdx = cp.classRef(superClass)
    val ifaceIdxs = interfaces.map(cp.classRef)

    // Pre-touch all UTF8/name/desc/code entries so the pool is fully built
    // before we serialize it (methods reference cp indices computed above).
    val codeAttrNameIdx = cp.utf8("Code")

    val fieldBytes = fields.map { f =>
      val nameIdx = cp.utf8(f.name)
      val descIdx = cp.utf8(f.desc)
      (f.accessFlags, nameIdx, descIdx)
    }

    val methodBytes = methods.map { m =>
      val nameIdx = cp.utf8(m.name)
      val descIdx = cp.utf8(m.desc)
      (m.accessFlags, nameIdx, descIdx, m.code)
    }

    val out = new ByteWriter
    out.u4(0xCAFEBABE)
    out.u2(MinorVersion)
    out.u2(MajorVersion)

    cp.write(out)

    out.u2(accessFlags)
    out.u2(thisIdx)
    out.u2(superIdx)
    out.u2(ifaceIdxs.size)
    for i <- ifaceIdxs do out.u2(i)

    out.u2(fieldBytes.size)
    for (flags, nameIdx, descIdx) <- fieldBytes do
      out.u2(flags); out.u2(nameIdx); out.u2(descIdx); out.u2(0) // no attributes

    out.u2(methodBytes.size)
    for (flags, nameIdx, descIdx, codeOpt) <- methodBytes do
      out.u2(flags); out.u2(nameIdx); out.u2(descIdx)
      codeOpt match
        case None =>
          out.u2(0) // no attributes (abstract/interface method)
        case Some((bytecode, maxStack, maxLocals)) =>
          out.u2(1) // one attribute: Code
          out.u2(codeAttrNameIdx)
          val codeAttrLen = 2 + 2 + 4 + bytecode.length + 2 + 0 + 2 + 0 // stack+locals+codeLen+code+excTable+attrs
          out.u4(codeAttrLen)
          out.u2(maxStack)
          out.u2(maxLocals)
          out.u4(bytecode.length)
          out.buf ++= bytecode
          out.u2(0) // exception table
          out.u2(0) // no code attributes

    out.u2(0) // no class attributes

    out.toByteArray
end ClassFile
