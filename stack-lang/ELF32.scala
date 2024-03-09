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

import IO.{ ByteBuffer, Patch }

import ELF32.*

class ELF32(firstSegBaseAddr: Int, align: Int, machine: Short):
  private val segments: mutable.ArrayBuffer[Segment] = new mutable.ArrayBuffer
  private val strtable: mutable.ArrayBuffer[Byte   ] = new mutable.ArrayBuffer
  private val sections: mutable.ArrayBuffer[Section] = new mutable.ArrayBuffer
  private val symbols:  mutable.ArrayBuffer[Symbol ] = new mutable.ArrayBuffer
  private val patches:  mutable.ArrayBuffer[Patch ]  = new mutable.ArrayBuffer

  /** The section content with possible segment paddings as in final ELF file. */
  private val content: mutable.ArrayBuffer[Byte   ] = new mutable.ArrayBuffer
  private val contentView: ByteBuffer = (b: Byte) => content.addOne(b)

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
    nextSegOffset() + firstSegBaseAddr

  private def nextSegOffset(): Int =
    var offset = 0
    val totalSize = curSegEnd()
    while offset < totalSize do offset += align
    offset

  private def curSegEnd(): Int = contentEnd()

  private def contentEnd(): Int =
    if segments.isEmpty then 0 else CONTENT_START_OFFSET + content.size

  private def addName(str: String): Int =
    val offset = strtable.size
    strtable.addAll(str.getBytes())
    strtable.addOne(0)
    offset

  /**
    * Create a new segment.
    *
    * The callback function will be called with the base virtual address
    * of the segment.
    */
  def newSegment(virtualAddr: Int, tp: Int, flags: Int)(fn: => Unit): Unit =
    val segIndex = segments.size
    val offset = nextSegOffset()
    val paddingBefore = offset - curSegEnd()
    val startSectionIndex = sections.size

    // must add padding before invoking the callback.
    contentView.addZeros(paddingBefore)

    fn // execute callback

    val endSectionIndex = sections.size - 1
    var length = 0
    for secIndex <- (startSectionIndex to endSectionIndex) do
      length += sections(secIndex).size

    val seg = Segment(segIndex, tp, offset, virtualAddr, length, flags, paddingBefore)
    segments.addOne(seg)

  /**
    * Add a new section.
    *
    * @returns the index of the section in the section header table.
    */
  def addSection(name: String, tp: Int, virtualAddr: Int,  bytes: Array[Byte], flags: Int, patches: List[Patch]): Short =
    val offset = contentEnd()
    val index = sections.size

    val sec = Section(addName(name), tp, flags, virtualAddr, offset, bytes.length, 0, 0, 4, 0)
    sections.addOne(sec)

    // relocate patches
    val patchDelta = content.size
    contentView.addBytes(bytes.toIndexedSeq)
    for patch <- patches do
      this.patches.addOne(patch.copy(offset = patch.offset + patchDelta))

    index.toShort

  def addFunSymbol(name: String, addr: Int, secIndex: Short): Unit =
    val sym = Symbol(addName(name), addr, 0, (STB_GLOBAL << 4) | STT_FUNC, secIndex)
    symbols.addOne(sym)

  def addDataSymbol(name: String, addr: Int, secIndex: Short): Unit =
    val sym = Symbol(addName(name), addr, 0, (STB_GLOBAL << 4) | STT_OBJECT, secIndex)
    symbols.addOne(sym)

  def write(entry: Int)(using buf: ByteBuffer) =
    val segNum = segments.size
    val symTabNameIndex = addName(".symtab")

    // First apply patches to content
    for patch <- patches do
      patch.apply { (i, b) => content(i) = b }

    // Add string table
    val nameIndex = addName(".strtab")
    val strTabOff = contentEnd()
    val strSec = Section(nameIndex, SHT_STRTAB, 0, 0, strTabOff, strtable.size, 0, 0, 1, 0)
    val strSecIndex = sections.size
    sections.addOne(strSec)

    // Add symbol table
    val symTabOff = strTabOff + strtable.size
    val symTabSize = symbols.size << 4
    val symSec = Section(symTabNameIndex, SHT_SYMTAB, 0, 0, symTabOff, symTabSize, strSecIndex, 0, 1, 16)
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

    ////////////////////////////  segments  /////////////////////////////////////

    buf.addBytes(content.toSeq)

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
      buf.addInt(seg.size)                                // p_filesz
      buf.addInt(seg.size)                                // p_memsz
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
      size: Int, flags: Int, paddingBefore: Int)

  private case class Section(
      nameIndex: Int, tp: Int, flags: Int, addr: Int, offset: Int,
      size: Int, link: Int, info: Int, align: Int, entrySize: Int)

  private case class Symbol(
      nameIndex: Int, virtualAddr: Int, size: Int, info: Byte, link: Short)
