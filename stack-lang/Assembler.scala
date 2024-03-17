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

  /**
    * Generate ELF with the given program and assembler
    */
  def lower(elf: ELF32, prog: Prog, heapStartLabel: Label, assembler: Assembler): Unit =
    val labelMap: mutable.Map[Label, Int]    = mutable.Map.empty

    /////////////// data section ////////////

    val dataSegBaseAddr = elf.nextSegVirtAddr()
    elf.newSegment(dataSegBaseAddr, ELF32.PT_LOAD, ELF32.PF_R | ELF32.PF_W):
      val pb = new PatchableBuffer(dataSegBaseAddr, labelMap)
      assembler.lowerData(prog.data)(using pb)

      assert(pb.getPatches().isEmpty, "patch size non empty for data section")

      val chunk = new ELF32.DataChunk:
        val fileSize = pb.size
        val memorySize = pb.size
        def fileBytes() = pb.finish()

      val flags = ELF32.SHF_WRITE | ELF32.SHF_ALLOC
      val secIndex = elf.addSection(".bss", ELF32.SHT_PROGBITS, dataSegBaseAddr, chunk, flags)

      for label <- pb.getDefinedLabels() do
        elf.addDataSymbol(label.name, labelMap(label), secIndex)

    /////////////// code section ////////////

    val codeSegBaseAddr = elf.nextSegVirtAddr()
    elf.newSegment(codeSegBaseAddr, ELF32.PT_LOAD, ELF32.PF_X | ELF32.PF_R | ELF32.PF_W):
      val pb = new PatchableBuffer(codeSegBaseAddr, labelMap)

      assembler.lowerCode(prog.instrs)(using pb)

      val chunk = new ELF32.DataChunk:
        val fileSize = pb.size
        val memorySize = pb.size
        def fileBytes() = pb.finish()

      // The patches depend on labels of other sections or segments they need to
      // be applied during ELF32 generation.
      val flags = ELF32.SHF_EXEC | ELF32.SHF_ALLOC
      val secIndex = elf.addSection(".text", ELF32.SHT_PROGBITS, codeSegBaseAddr, chunk, flags)

      for label <- pb.getDefinedLabels() do
        elf.addFunSymbol(label.name, labelMap(label), secIndex)

    /////////////// heap section ////////////

    val heapSegBaseAddr = elf.nextSegVirtAddr()
    elf.newSegment(heapSegBaseAddr, ELF32.PT_LOAD, ELF32.PF_R | ELF32.PF_W):
      val flags = ELF32.SHF_ALLOC
      val chunk = new ELF32.DataChunk:
        def fileSize = 0
        def memorySize = VAL_STACK_SIZE
        def fileBytes() = new Array[Byte](0)

      val secIndex = elf.addSection(".heap", ELF32.SHT_PROGBITS, heapSegBaseAddr, chunk, flags)

      elf.addDataSymbol(heapStartLabel.name, heapSegBaseAddr, secIndex)
      labelMap(heapStartLabel) = heapSegBaseAddr

    ////////////////// write file /////////////////

    labelMap.get(prog.entry) match
      case Some(entry) =>
        elf.write(entry)

      case None =>
        throw new Exception("Entry point not found: " + prog.entry)
  end lower
