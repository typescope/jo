package native

import common.IO.ByteBuffer
import Assembly.*
import Assembler.*

import scala.collection.mutable

/**
  * Represents an assembler that translates assembly code to machine code.
  */
trait Assembler:
  /** Compile the data to byte stream. */
  def lowerData(data: List[Data])(using PatchableBuffer): Unit

  /** Compile the code to byte stream. */
  def lowerCode(code: List[Instr | Label])(using PatchableBuffer): Unit

object Assembler:
  private val VAL_STACK_SIZE = 4096

  private val SEG_DATA = "data"
  private val SEG_CODE = "code"
  private val SEG_HEAP = "heap"

  def continuousLayout(orderName: String, baseAddr: Int, align: Int): ELF32.Layout =
    val order =
      if orderName == "c1" then List(SEG_DATA, SEG_CODE, SEG_HEAP)
      else if orderName == "c2" then List(SEG_CODE, SEG_HEAP, SEG_DATA)
      else List(SEG_HEAP, SEG_CODE, SEG_DATA)

    new ELF32.ContinuousLayout(order, baseAddr, align)

  /**
    * Generate ELF with the given program and assembler
    *
    * The code is OS- and CPU-agnostic.
    */
  def lower(elf: ELF32, prog: Prog, heapStartLabel: Label, assembler: Assembler, linker: Linker): Unit =
    val labelMap: mutable.Map[Label, Int] = mutable.Map.empty

    /////////////// data segment ////////////

    // TODO: separate read-only and bss data into different segments
    elf.newSegment(SEG_DATA, ELF32.PT_LOAD, ELF32.PF_RW): baseAddr =>
      val pb = new PatchableBuffer(baseAddr, labelMap)
      linker.linkData()(using pb)
      assembler.lowerData(prog.data)(using pb)

      assert(pb.getPatches().isEmpty, "patch size non empty for data section")

      val chunk = ELF32.dataChunk(pb)
      val flags = ELF32.SHF_WRITE | ELF32.SHF_ALLOC
      val secIndex = elf.addSection(".bss", baseAddr, chunk, flags)

      for label <- pb.getDefinedLabels() do
        elf.addDataSymbol(label.name, labelMap(label), secIndex)

    /////////////// code segment ////////////

    elf.newSegment(SEG_CODE, ELF32.PT_LOAD, ELF32.PF_RX): baseAddr =>
      val pb = new PatchableBuffer(baseAddr, labelMap)
      linker.linkCode()(using pb)
      assembler.lowerCode(prog.instrs)(using pb)

      // The patches depend on labels of other segments, so they need to
      // be applied during ELF32 generation.
      val chunk = ELF32.dataChunk(pb)
      val flags = ELF32.SHF_EXEC | ELF32.SHF_ALLOC
      val secIndex = elf.addSection(".text", baseAddr, chunk, flags)

      for label <- pb.getDefinedLabels() do
        elf.addFunSymbol(label.name, labelMap(label), secIndex)

    /////////////// heap segment ////////////

    elf.newSegment(SEG_HEAP, ELF32.PT_LOAD, ELF32.PF_RW): baseAddr =>
      val flags = ELF32.SHF_ALLOC
      val chunk = new ELF32.DataChunk:
        def fileSize = 0
        def memorySize = VAL_STACK_SIZE
        def fileBytes() = new Array[Byte](0)

      val secIndex = elf.addSection(".heap", baseAddr, chunk, flags)

      elf.addDataSymbol(heapStartLabel.name, baseAddr, secIndex)
      labelMap(heapStartLabel) = baseAddr

    ////////////////// write file /////////////////

    val segments = elf.layoutSegments()

    labelMap.get(prog.entry) match
      case Some(entry) =>
        elf.write(entry, segments)

      case None =>
        throw new Exception("Entry point not found: " + prog.entry)
  end lower

  /**
    * Represents a patch to be applied after first pass of code generation.
    *
    * The patches are needed to resolve the address of mutually recursive functions.
    *
    * For simplicity, patches should not be nested.
    */
  case class Patch(offset: Int, size: Int, values: () => List[Byte]):
    def apply(update: (Int, Byte) => Unit): Unit =
      val bytes = this.values()
      if bytes.size != this.size then
        throw new Exception("Patch size mismatch, found = " +
            bytes.size + ", expect = " + this.size)
      end if
      var i = 0
      while i < this.size do
        update(this.offset + i, bytes(i))
        i += 1


  /**
    * A byte buffer which supports labels, patches and alignment.
    */
  class PatchableBuffer(
      baseAddr: Int,
      buffer  : mutable.ArrayBuffer[Byte],
      labelMap: mutable.Map[Label, Int],
      patches : mutable.ArrayBuffer[Patch]
  ) extends ByteBuffer:
    def this(baseAddr: Int, labelMap: mutable.Map[Label, Int]) =
      this(baseAddr, new mutable.ArrayBuffer, labelMap, new mutable.ArrayBuffer)

    /** New labels defined for the current PatchableBuffer */
    private  val newLabels : mutable.ArrayBuffer[Label] = new mutable.ArrayBuffer

    def addByte(data : Byte): Unit = buffer.addOne(data)

    def addPatch(patch: Patch): Unit =
      patches.addOne(patch)
      addZeros(patch.size)

    def getPatches(): List[Patch] = patches.toList

    def align(n: Int): Unit =
      while currentAddr() % n != 0 do
        addByte(0)

    def defineLabel(label: Label) =
      assert(!labelMap.contains(label))
      newLabels += label
      labelMap(label) = currentAddr()

    def getDefinedLabels(): List[Label] = newLabels.toList

    def resolve(label: Label): Option[Int] =
      labelMap.get(label)

    def currentAddr(): Int = baseAddr + currentOffset()

    def currentOffset(): Int = size

    def size: Int = buffer.size

    /** Applying patches and return result */
    def finish(): Array[Byte] =
      for patch <- patches do
        patch.apply { (i, b) => buffer(i) = b }
      buffer.toArray
  end PatchableBuffer

  /** Helper method to deal with patches (labels that are resolved late) */
  def withPatch
    (label: Label, size: Int)
    (fn: (ByteBuffer, Int) => Unit)
    (using pb: PatchableBuffer): Unit =

    pb.resolve(label) match
      case Some(loc) =>
        fn(pb, loc)

      case None =>
        val buffer = new mutable.ArrayBuffer[Byte]
        val bb: ByteBuffer = (b: Byte) => buffer.addOne(b)

        val patchFn: () => List[Byte] = () =>
          val Some(loc) = pb.resolve(label): @unchecked
          fn(bb, loc)
          buffer.toList

        pb.addPatch(Patch(pb.currentOffset(), size, patchFn))
