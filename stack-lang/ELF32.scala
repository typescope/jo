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

import scala.collection.mutable

import IO.ByteBuffer

import ELF32.*

class ELF32(outFile: String, firstSegBaseAddr: Int, align: Int, machine: Short):
  private val segments: mutable.ArrayBuffer[Segment] = new mutable.ArrayBuffer
  private val strtable: mutable.ArrayBuffer[Byte   ] = new mutable.ArrayBuffer
  private val sections: mutable.ArrayBuffer[Section] = new mutable.ArrayBuffer
  private val symbols:  mutable.ArrayBuffer[Symbol ] = new mutable.ArrayBuffer

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

  /**
    * Generate a default virtual address for the next segment.
    *
    * A custom virtual address can be supplied without calling this function.
    */
  def nextSegVirtAddr(): Int =
    nextSegMemoryOffset() + firstSegBaseAddr

  private def nextSegFileOffset(): Int =
    var offset = 0
    val totalSize = contentFileEnd()
    while offset < totalSize do offset += align
    offset

  private def nextSegMemoryOffset(): Int =
    var offset = 0
    val totalSize = contentMemoryEnd()
    while offset < totalSize do offset += align
    offset

  private def contentMemoryEnd(): Int =
    if segments.isEmpty then 0
    else CONTENT_START_OFFSET + content.memorySize

  private def contentFileEnd(): Int =
    if segments.isEmpty then 0
    else CONTENT_START_OFFSET + content.fileSize

  private def addName(str: String): Int =
    val offset = strtable.size
    strtable.addAll(str.getBytes())
    strtable.addOne(0)
    offset

  /** Create a new segment in the ELF file */
  def newSegment(virtualAddr: Int, tp: Int, flags: Int)(fn: => Unit): Unit =
    val segIndex = segments.size
    val offset = nextSegFileOffset()
    val padding = offset - contentFileEnd()

    // must add padding before invoking the callback.
    content.add(segmentEndPadding(padding))

    val fileSizeBefore = content.fileSize
    val memorySizeBefore = content.memorySize

    fn // execute callback

    val fileSize = content.fileSize - fileSizeBefore
    val memorySize = content.memorySize - memorySizeBefore
    if fileSize > 0 || memorySize > 0 then
      val seg = Segment(segIndex, tp, offset, virtualAddr, fileSize, memorySize, flags)
      segments.addOne(seg)

  /**
    * Add a new section.
    *
    * @returns the index of the section in the section header table.
    */
  def addSection(name: String, tp: Int, virtualAddr: Int, chunk: DataChunk, flags: Int): Short =
    val offset = contentFileEnd()
    val index = sections.size

    val sec = Section(addName(name), tp, flags, virtualAddr, offset, chunk.fileSize, link = 0, info = 0, align = 4, entrySize = 0)
    sections.addOne(sec)

    content.add(chunk)

    index.toShort

  def addFunSymbol(name: String, addr: Int, secIndex: Short): Unit =
    val sym = Symbol(addName(name), addr, 0, (STB_GLOBAL << 4) | STT_FUNC, secIndex)
    symbols.addOne(sym)

  def addDataSymbol(name: String, addr: Int, secIndex: Short): Unit =
    val sym = Symbol(addName(name), addr, 0, (STB_GLOBAL << 4) | STT_OBJECT, secIndex)
    symbols.addOne(sym)

  def write(entry: Int): Unit =
    IO.withExeFile(outFile): bb =>
      write(entry)(using bb)

  private def write(entry: Int)(using buf: ByteBuffer) =
    val segNum = segments.size
    val symTabNameIndex = addName(".symtab")

    // Add string table
    val nameIndex = addName(".strtab")
    val strTabOff = contentFileEnd()
    val strSec = Section(nameIndex, SHT_STRTAB, 0, 0, strTabOff, strtable.size, link = 0, info = 0, align = 1, entrySize = 0)
    val strSecIndex = sections.size
    sections.addOne(strSec)

    // Add symbol table
    val symTabOff = strTabOff + strtable.size
    val symTabSize = symbols.size << 4
    val symSec = Section(symTabNameIndex, SHT_SYMTAB, 0, 0, symTabOff, symTabSize, strSecIndex, info = 0, align = 1, entrySize = 16)
    sections.addOne(symSec)

    // Section header table
    val secNum = sections.size
    val secHeaderOff = symTabOff + symTabSize
    val secHeaderSize = secNum * SH_ENTRY_SIZE

    // Segment header table
    val segHeaderOff  = secHeaderOff + secHeaderSize
    val segHeaderSize = PH_ENTRY_SIZE * segNum

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
      buf.addInt(sym.virtualAddr)
      buf.addInt(sym.size)
      buf.addByte(sym.info)
      buf.addByte(0)
      buf.addShort(sym.link)

    //////////////////////////// section table //////////////////////////////////

    for sec <- sections do
      buf.addInt(sec.nameIndex)
      buf.addInt(sec.tp)
      buf.addInt(sec.flags)
      buf.addInt(sec.addr)
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
      buf.addInt(seg.virtualAddr)                         // p_vaddr
      buf.addInt(seg.virtualAddr)                         // p_paddr
      buf.addInt(seg.fileSize)                            // p_filesz
      buf.addInt(seg.memorySize)                          // p_memsz
      buf.addInt(seg.flags)                               // p_flags
      buf.addInt(align)                                   // p_align

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

  private case class Segment(
      index: Int, tp: Int, offset: Int, virtualAddr: Int,
      fileSize: Int, memorySize: Int, flags: Int)

  private case class Section(
      nameIndex: Int, tp: Int, flags: Int, addr: Int, offset: Int,
      size: Int, link: Int, info: Int, align: Int, entrySize: Int)

  private case class Symbol(
      nameIndex: Int, virtualAddr: Int, size: Int, info: Byte, link: Short)

  /** Represent a data chunk which will be ready when generating the file. */
  trait DataChunk:
    def fileSize: Int
    def memorySize: Int
    def fileBytes(): Array[Byte]

  /** ELF32 requires alignment of virtual address & file offset at page boundaries */
  private def segmentEndPadding(size: Int): DataChunk = new DataChunk:
    def fileSize = size
    def memorySize = size
    def fileBytes() = new Array[Byte](size)
