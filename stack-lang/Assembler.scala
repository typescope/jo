import Assembly.*
import Assembler.*
import IO.PatchableBuffer

import scala.collection.mutable

/**
  * The assembler that translates assembly to machine code.
  */
trait Assembler:
  def lowerData(data: List[Data | Attr])(using PatchableBuffer): Unit
  def lowerCode(code: List[Instr | Label])(using PatchableBuffer): Unit

object Assembler:
  private val VAL_STACK_SIZE = 4096

  private val SEG_DATA = "data"
  private val SEG_CODE = "code"
  private val SEG_HEAP = "heap"

  def continuousLayout(baseVirtAddr: Int, align: Int): ELF32.Layout =
    val order = List(SEG_DATA, SEG_CODE, SEG_HEAP)
    new ELF32.ContinuousLayout(order, baseVirtAddr, align)

  /**
    * Generate ELF with the given program and assembler
    */
  def lower(elf: ELF32, prog: Prog, heapStartLabel: Label, assembler: Assembler): Unit =
    val labelMap: mutable.Map[Label, Int] = mutable.Map.empty

    /////////////// data section ////////////

    elf.newSegment(SEG_DATA, ELF32.PT_LOAD, ELF32.PF_R | ELF32.PF_W): virtAddr =>
      val pb = new PatchableBuffer(virtAddr, labelMap)
      assembler.lowerData(prog.data)(using pb)

      assert(pb.getPatches().isEmpty, "patch size non empty for data section")

      val chunk = new ELF32.DataChunk:
        val fileSize = pb.size
        val memorySize = pb.size
        def fileBytes() = pb.finish()

      val flags = ELF32.SHF_WRITE | ELF32.SHF_ALLOC
      val secIndex = elf.addSection(".bss", ELF32.SHT_PROGBITS, virtAddr, chunk, flags)

      for label <- pb.getDefinedLabels() do
        elf.addDataSymbol(label.name, labelMap(label), secIndex)

    /////////////// code section ////////////

    elf.newSegment(SEG_CODE, ELF32.PT_LOAD, ELF32.PF_X | ELF32.PF_R | ELF32.PF_W): virtAddr =>
      val pb = new PatchableBuffer(virtAddr, labelMap)

      assembler.lowerCode(prog.instrs)(using pb)

      val chunk = new ELF32.DataChunk:
        val fileSize = pb.size
        val memorySize = pb.size
        def fileBytes() = pb.finish()

      // The patches depend on labels of other sections or segments they need to
      // be applied during ELF32 generation.
      val flags = ELF32.SHF_EXEC | ELF32.SHF_ALLOC
      val secIndex = elf.addSection(".text", ELF32.SHT_PROGBITS, virtAddr, chunk, flags)

      for label <- pb.getDefinedLabels() do
        elf.addFunSymbol(label.name, labelMap(label), secIndex)

    /////////////// heap section ////////////

    elf.newSegment(SEG_HEAP, ELF32.PT_LOAD, ELF32.PF_R | ELF32.PF_W): virtAddr =>
      val flags = ELF32.SHF_ALLOC
      val chunk = new ELF32.DataChunk:
        def fileSize = 0
        def memorySize = VAL_STACK_SIZE
        def fileBytes() = new Array[Byte](0)

      val secIndex = elf.addSection(".heap", ELF32.SHT_PROGBITS, virtAddr, chunk, flags)

      elf.addDataSymbol(heapStartLabel.name, virtAddr, secIndex)
      labelMap(heapStartLabel) = virtAddr

    ////////////////// write file /////////////////

    labelMap.get(prog.entry) match
      case Some(entry) =>
        elf.write(entry)

      case None =>
        throw new Exception("Entry point not found: " + prog.entry)
  end lower
