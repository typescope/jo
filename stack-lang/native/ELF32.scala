/************************************************************************
 *                                                                      *
 * Produce ELF executable file.                                         *
 *                                                                      *
 * Reference:                                                           *
 *                                                                      *
 *   [1] Executable and Linking Format (ELF) Specification Version 1.2  *
 *       https://refspecs.linuxbase.org/elf/elf.pdf                     *
 *                                                                      *
 ************************************************************************/
package native

import Assembler.PatchableBuffer

import common.IO
import common.IO.ByteBuffer

import ELF32.*

import scala.collection.mutable

class ELF32(outFile: String, layout: Layout, machine: Short):
  private val strtable: mutable.ArrayBuffer[Byte   ] = new mutable.ArrayBuffer
  private val sections: mutable.ArrayBuffer[Section] = new mutable.ArrayBuffer
  private val symbols:  mutable.ArrayBuffer[Symbol ] = new mutable.ArrayBuffer

  private val builders: mutable.Map[String, LayoutInfo => Segment] = mutable.Map.empty

  /** The main content of ELF32 with possible segment paddings
    *
    * The file header, program header table and section header table are excluded.
    */
  private class Content extends ByteBuffer:
    private var curFileSize = 0
    private var curMemorySize = 0
    private val items: mutable.ArrayBuffer[Byte | DataChunk] = new mutable.ArrayBuffer

    def write(buf: ByteBuffer): Unit =
      for item <- items do
        item match
         case byte: Byte => buf.addByte(byte)
         case chunk: DataChunk => buf.addBytes(chunk.fileBytes().toIndexedSeq)

    def addByte(b: Byte) =
      items.addOne(b)
      curFileSize += 1
      curMemorySize += 1

    def add(chunk: DataChunk) =
      items.addOne(chunk)
      curFileSize += chunk.fileSize
      curMemorySize += chunk.memorySize

    def fileSize: Int = curFileSize
    def memorySize: Int = curMemorySize
  end Content

  private val content = new Content

  private val CONTENT_START_OFFSET = E_HEADER_SIZE

  // First entry in section table is a dummy section
  private val section0 = Section(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
  sections.addOne(section0)

  // First byte is 0 in string table
  strtable.addOne(0)

  // First dummy symbol in symbol table
  symbols.addOne(Symbol(0, 0, 0, 0, 0))

  private def currentOffset(): Int =
    CONTENT_START_OFFSET + content.fileSize

  private def addName(str: String): Int =
    val offset = strtable.size
    strtable.addAll(str.getBytes())
    strtable.addOne(0)
    offset

  /** Create a new segment in the ELF file */
  def newSegment(id: String, tp: Int, flags: Int)(fn: Int => Unit): Unit =
    assert(!builders.contains(id), "The segment " + id + " already exists")

    builders(id) = info => {
      val padding = info.fileOffset - currentOffset()
      if padding > 0 then
        content.add(segmentEndPadding(padding))

      val fileSizeBefore = content.fileSize
      val memorySizeBefore = content.memorySize

      // First segment includes the file header
      val contentBaseAddr =
        if info.index == 0 then info.baseAddr + E_HEADER_SIZE
        else info.baseAddr

      fn(contentBaseAddr) // execute callback

      var fileSize = content.fileSize - fileSizeBefore
      var memorySize = content.memorySize - memorySizeBefore
      if info.index == 0 then
        fileSize += E_HEADER_SIZE
        memorySize += E_HEADER_SIZE
      Segment(
          info.index, tp, info.fileOffset, info.baseAddr, fileSize, memorySize,
          info.align, flags
      )
    }

  /**
    * Add a new section.
    *
    * @returns the index of the section in the section header table.
    */
  def addSection(name: String, baseAddr: Int, chunk: DataChunk, flags: Int): Short =
    val offset = currentOffset()
    val index = sections.size

    val sec = Section(
        addName(name), SHT_PROGBITS, flags, baseAddr, offset,
        chunk.fileSize, link = 0, info = 0, align = 4, entrySize = 0
    )
    sections.addOne(sec)

    content.add(chunk)

    index.toShort

  def addFunSymbol(name: String, addr: Int, secIndex: Short): Unit =
    val sym = Symbol(addName(name), addr, 0, (STB_GLOBAL << 4) | STT_FUNC, secIndex)
    symbols.addOne(sym)

  def addDataSymbol(name: String, addr: Int, secIndex: Short): Unit =
    val sym = Symbol(addName(name), addr, 0, (STB_GLOBAL << 4) | STT_OBJECT, secIndex)
    symbols.addOne(sym)

  def layoutSegments(): List[Segment] =
    layout.run(builders.toMap)

  def write(entry: Int, segments: List[Segment]): Unit =
    IO.withExeFile(outFile): bb =>
      write(entry, segments)(using bb)

  private def write(entry: Int, segments: List[Segment])(using buf: ByteBuffer) =
    val segNum = segments.size
    val symTabNameIndex = addName(".symtab")

    // Add string table
    val nameIndex = addName(".strtab")
    val strTabOff = currentOffset()
    val strSec = Section(
        nameIndex, SHT_STRTAB, 0, 0, strTabOff, strtable.size, link = 0,
        info = 0, align = 1, entrySize = 0
    )
    val strSecIndex = sections.size
    sections.addOne(strSec)

    // Add symbol table
    val symTabOff = strTabOff + strtable.size
    val symTabSize = symbols.size << 4
    val symSec = Section(
        symTabNameIndex, SHT_SYMTAB, 0, 0, symTabOff, symTabSize, strSecIndex,
        info = 0, align = 1, entrySize = 16
    )
    sections.addOne(symSec)

    // Section header table
    val secNum = sections.size
    val secHeaderOff = symTabOff + symTabSize
    val secHeaderSize = secNum * SH_ENTRY_SIZE

    // Segment header table
    val segHeaderOff  = secHeaderOff + secHeaderSize

    //////////////////////////////  file header  ////////////////////////////////

    buf.addByte(0x7F)          // EI_MAG
    buf.addByte('E')
    buf.addByte('L')
    buf.addByte('F')
    buf.addByte(ELFCLASS32)    // EI_CLASS
    buf.addByte(ELFDATA2LSB)   // EI_DATA
    buf.addByte(EV_CURRENT)    // EI_VERSION
    buf.addByte(0)             // EI_PAD
    buf.addByte(0)             // EI_PAD
    buf.addByte(0)             // EI_PAD
    buf.addByte(0)             // EI_PAD
    buf.addByte(0)             // EI_PAD
    buf.addByte(0)             // EI_PAD
    buf.addByte(0)             // EI_PAD
    buf.addByte(0)             // EI_PAD
    buf.addByte(0)             // EI_PAD

    buf.addShort(ET_EXEC)      // e_type
    buf.addShort(machine)      // e_machine
    buf.addInt(EV_CURRENT)     // e_version
    buf.addInt(entry)          // e_entry
    buf.addInt(segHeaderOff)   // e_phoff
    buf.addInt(secHeaderOff)   // e_shoff
    buf.addInt(0)              // e_flags
    buf.addShort(E_HEADER_SIZE)// e_ehsize
    buf.addShort(PH_ENTRY_SIZE)// e_phentsize
    buf.addShort(segNum)       // e_phnum
    buf.addShort(SH_ENTRY_SIZE)// e_shentsize
    buf.addShort(secNum)       // e_shnum
    buf.addShort(strSecIndex)  // e_shstrndx

    ////////////////////////////  segment content  /////////////////////////////

    content.write(buf)

    //////////////////////////// string table ///////////////////////////////////

    for b <- strtable do buf.addByte(b)

    //////////////////////////// symbol table ///////////////////////////////////

    for sym <- symbols do
      buf.addInt(sym.nameIndex)
      buf.addInt(sym.addr)
      buf.addInt(sym.size)
      buf.addByte(sym.info)
      buf.addByte(0)
      buf.addShort(sym.link)

    //////////////////////////// section table //////////////////////////////////

    for sec <- sections do
      buf.addInt(sec.nameIndex)
      buf.addInt(sec.tp)
      buf.addInt(sec.flags)
      buf.addInt(sec.baseAddr)
      buf.addInt(sec.offset)
      buf.addInt(sec.size)
      buf.addInt(sec.link)
      buf.addInt(sec.info)
      buf.addInt(sec.align)
      buf.addInt(sec.entrySize)

    ///////////////////////////// program header  ///////////////////////////////

    for seg <- segments do
      buf.addInt(seg.tp)                                  // p_type
      buf.addInt(seg.offset)                              // p_offset
      buf.addInt(seg.baseAddr)                            // p_vaddr
      buf.addInt(seg.baseAddr)                            // p_paddr
      buf.addInt(seg.fileSize)                            // p_filesz
      buf.addInt(seg.memorySize)                          // p_memsz
      buf.addInt(seg.flags)                               // p_flags
      buf.addInt(seg.align)                               // p_align

object ELF32:
  final val ELFCLASS32  = 1
  final val ELFDATA2LSB = 1
  final val EV_CURRENT  = 1

  final val ET_EXEC = 2
  final val EM_386  = 3

  final val PT_NULL    = 0
  final val PT_LOAD    = 1
  final val PT_DYNAMIC = 2
  final val PT_INTERP  = 3
  final val PT_NOTE    = 4
  final val PT_SHLIB   = 5

  final val PF_X = 0x1
  final val PF_W = 0x2
  final val PF_R = 0x4

  final val PF_RW = ELF32.PF_R | ELF32.PF_W
  final val PF_RX = ELF32.PF_R | ELF32.PF_X

  final val E_HEADER_SIZE = 0x34
  final val PH_ENTRY_SIZE = 0x20
  final val SH_ENTRY_SIZE = 0x28

  final val SHN_UNDEF = 0

  final val SHT_PROGBITS = 1
  final val SHT_SYMTAB   = 2
  final val SHT_STRTAB   = 3

  final val SHF_WRITE = 0x1
  final val SHF_ALLOC = 0x2
  final val SHF_EXEC  = 0x4

  final val STB_LOCAL  = 0
  final val STB_GLOBAL = 1

  final val STT_OBJECT = 1
  final val STT_FUNC   = 2

  case class Segment(
      index: Int, tp: Int, offset: Int, baseAddr: Int,
      fileSize: Int, memorySize: Int, align: Int, flags: Int)

  private case class Section(
      nameIndex: Int, tp: Int, flags: Int, baseAddr: Int, offset: Int,
      size: Int, link: Int, info: Int, align: Int, entrySize: Int)

  private case class Symbol(
      nameIndex: Int, addr: Int, size: Int, info: Byte, link: Short)

  /** Represent a data chunk which will be ready when generating the file. */
  trait DataChunk:
    def fileSize: Int
    def memorySize: Int
    def fileBytes(): Array[Byte]

  /** ELF32 requires alignment of memory address & file offset at page boundaries
    *
    * Executable and Linking Format (ELF) Specification, Version 1.2:
    *
    *     Loadable process segments must have congruent values for p_vaddr and
    *     p_offset, modulo the page size.
    *
    *     p_align should be a positive, integral power of 2, and p_addr should
    *     equal p_offset, modulo p_align.
    */
  private def segmentEndPadding(size: Int): DataChunk = new DataChunk:
    def fileSize = size
    def memorySize = size
    def fileBytes() = new Array[Byte](size)

  def dataChunk(pb: PatchableBuffer): DataChunk = new DataChunk:
    val fileSize = pb.size
    val memorySize = pb.size
    def fileBytes() = pb.finish()

  case class LayoutInfo(
    baseAddr: Int, fileOffset: Int, index: Int, align: Int)

  trait Layout:
    def run(segments: Map[String, LayoutInfo => Segment]): List[Segment]


  class ContinuousLayout(segOrder: List[String], baseAddr: Int, align: Int)
  extends Layout:

    /**
      * Generate memory address for the next segment
      *
      * The first segment contains the file header.
      */
    private def nextSegAddr(segments: mutable.Seq[Segment]): Int =
      if segments.isEmpty then baseAddr
      else
        val seg = segments.last
        var segAlloc = 0
        val totalSize = seg.memorySize
        while segAlloc < totalSize do segAlloc += align
        seg.baseAddr + segAlloc

    /**
      * Compute file offset for the next segment
      *
      * The first segment contains the file header.
      */
    private def nextSegFileOffset(segments: mutable.Seq[Segment]): Int =
      if segments.isEmpty then 0
      else
        val seg = segments.last
        var segAlloc = 0
        val totalSize = seg.fileSize
        while segAlloc < totalSize do segAlloc += align
        seg.offset + segAlloc

    def run(segBuilders: Map[String, LayoutInfo => Segment]): List[Segment] =
      if segOrder.size != segBuilders.size then
        throw new Exception(
            "Segment size mismatch, given = " + segOrder + ", found = " +
            segBuilders.keys)

      val segments: mutable.ArrayBuffer[Segment] = new mutable.ArrayBuffer

      for seg <- segOrder do
        segBuilders.get(seg) match
          case Some(fn) =>
            val baseAddr = nextSegAddr(segments)
            val fileOffset = nextSegFileOffset(segments)
            val info = LayoutInfo(baseAddr, fileOffset, segments.size, align)
            val seg = fn(info)
            if seg.memorySize > 0 || seg.fileSize > 0 then
              segments += seg

          case None =>
            throw new Exception(
                "Unknown segment " + seg + ", found = " + segBuilders.keys)
      end for

      segments.toList
