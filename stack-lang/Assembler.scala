import Assembly.*
import Assembler.*
import IO.PatchableBuffer

import scala.collection.mutable

/**
  * Represents an assembler that translates assembly code to machine code.
  */
trait Assembler:
  /** Compile the data to byte stream. */
  def lowerData(data: List[Data])(using PatchableBuffer): Unit

  /** Compile the code to byte stream. */
  def lowerCode(code: List[Instr | Label])(using PatchableBuffer): Unit

  /**
    * Give assmbler the opportunity to add native services.
    *
    * We resort to services for functionalities that cannot be implement
    * directly with the generic assembly.
    *
    * Such functionalities usually depends on particular platform, such
    * as operating system and/or processor.
    *
    * Services are implemented by emitting platform-specific machine code.
    */
  def defineServices()(using pb: PatchableBuffer): Unit

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
    */
  def lower(elf: ELF32, prog: Prog, heapStartLabel: Label, assembler: Assembler): Unit =
    val labelMap: mutable.Map[Label, Int] = mutable.Map.empty

    /////////////// data segment ////////////

    elf.newSegment(SEG_DATA, ELF32.PT_LOAD, ELF32.PF_RW): baseAddr =>
      val pb = new PatchableBuffer(baseAddr, labelMap)
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
      assembler.defineServices()(using pb)
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
