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
import common.ByteBuffer

import ELF32.*

import scala.collection.mutable
import java.nio.charset.StandardCharsets


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
    strtable.addAll(str.getBytes(StandardCharsets.UTF_8))
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

  def addDebugAbbrevSection(): Unit =
    val buf = new mutable.ArrayBuffer[Byte]
    def addByte(b: Int): Unit = buf += b.toByte
    def uleb128(v: Int): Unit =
      var x = v
      while { val b = x & 0x7F; x >>>= 7; if x != 0 then buf += (b | 0x80).toByte else buf += b.toByte; x != 0 } do ()

    uleb128(1)              // abbreviation code 1
    uleb128(0x11)           // DW_TAG_compile_unit
    addByte(0)              // DW_CHILDREN_no
    uleb128(0x10); uleb128(0x06)   // DW_AT_stmt_list,  DW_FORM_data4
    uleb128(0x1b); uleb128(0x08)   // DW_AT_comp_dir,   DW_FORM_string
    uleb128(0x03); uleb128(0x08)   // DW_AT_name,       DW_FORM_string
    uleb128(0x13); uleb128(0x05)   // DW_AT_language,   DW_FORM_data2
    addByte(0); addByte(0)         // end of attributes
    addByte(0)                     // end of abbreviation table

    val bytes = buf.toArray
    val chunk = new DataChunk:
      val fileSize   = bytes.length
      val memorySize = bytes.length
      def fileBytes() = bytes
    addSection(".debug_abbrev", baseAddr = 0, chunk, flags = 0)
  end addDebugAbbrevSection

  def addDebugInfoSection(primaryFile: String, compDir: String): Unit =
    val buf = new mutable.ArrayBuffer[Byte]
    def addByte(b: Int): Unit = buf += b.toByte
    def addInt16(v: Int): Unit = { addByte(v); addByte(v >> 8) }
    def addInt32(v: Int): Unit = { addByte(v); addByte(v >> 8); addByte(v >> 16); addByte(v >> 24) }
    def patchInt32(pos: Int, v: Int): Unit =
      buf(pos)     = v.toByte
      buf(pos + 1) = (v >> 8).toByte
      buf(pos + 2) = (v >> 16).toByte
      buf(pos + 3) = (v >> 24).toByte
    def addStr(s: String): Unit =
      for b <- s.getBytes(StandardCharsets.UTF_8) do buf += b
      buf += 0
    def uleb128(v: Int): Unit =
      var x = v
      while { val b = x & 0x7F; x >>>= 7; if x != 0 then buf += (b | 0x80).toByte else buf += b.toByte; x != 0 } do ()

    // CU header
    val unitLengthOffset = buf.size
    addInt32(0)    // unit_length placeholder
    addInt16(2)    // DWARF version 2
    addInt32(0)    // debug_abbrev_offset = 0 (start of .debug_abbrev)
    addByte(4)     // address_size = 4

    // Single DIE: DW_TAG_compile_unit (abbrev code 1)
    uleb128(1)
    addInt32(0)         // DW_AT_stmt_list = 0 (offset into .debug_line)
    addStr(compDir)     // DW_AT_comp_dir
    addStr(primaryFile) // DW_AT_name
    addInt16(1)         // DW_AT_language = DW_LANG_C (1)

    patchInt32(unitLengthOffset, buf.size - unitLengthOffset - 4)

    val bytes = buf.toArray
    val chunk = new DataChunk:
      val fileSize   = bytes.length
      val memorySize = bytes.length
      def fileBytes() = bytes
    addSection(".debug_info", baseAddr = 0, chunk, flags = 0)
  end addDebugInfoSection

  def addDebugLineSection(locMarks: List[(String, Int, Int)]): Unit =
    val validMarks = locMarks.filter(_._1.nonEmpty)
    if validMarks.isEmpty then return

    val buf = new mutable.ArrayBuffer[Byte]

    def addByte(b: Int): Unit = buf += b.toByte
    def addInt16(v: Int): Unit = { addByte(v); addByte(v >> 8) }
    def addInt32(v: Int): Unit = { addByte(v); addByte(v >> 8); addByte(v >> 16); addByte(v >> 24) }
    def patchInt32(pos: Int, v: Int): Unit =
      buf(pos)     = v.toByte
      buf(pos + 1) = (v >> 8).toByte
      buf(pos + 2) = (v >> 16).toByte
      buf(pos + 3) = (v >> 24).toByte
    def addStr(s: String): Unit =
      for b <- s.getBytes(StandardCharsets.UTF_8) do buf += b
      buf += 0
    def uleb128(value: Int): Unit =
      var v = value
      while
        val b = v & 0x7F
        v >>>= 7
        if v != 0 then buf += (b | 0x80).toByte
        else buf += b.toByte
        v != 0
      do ()
    def sleb128(value: Int): Unit =
      var v = value
      var more = true
      while more do
        val b = v & 0x7F
        v >>= 7
        if (v == 0 && (b & 0x40) == 0) || (v == -1 && (b & 0x40) != 0) then
          buf += b.toByte
          more = false
        else
          buf += (b | 0x80).toByte

    // Build directory and file tables
    val uniqueFiles = validMarks.map(_._1).distinct
    val uniqueDirs  = uniqueFiles.map { p => val i = p.lastIndexOf('/'); if i >= 0 then p.substring(0, i) else "" }
                                 .distinct.filter(_.nonEmpty)
    val dirIndex  = uniqueDirs.zipWithIndex.map((d, i) => d -> (i + 1)).toMap
    val fileIndex = uniqueFiles.zipWithIndex.map((f, i) => f -> (i + 1)).toMap

    // ---- Header ----
    val unitLengthOffset = buf.size
    addInt32(0)      // unit_length placeholder
    addInt16(2)      // DWARF version 2
    val headerLengthOffset = buf.size
    addInt32(0)      // header_length placeholder
    val headerBodyStart = buf.size

    addByte(1)       // minimum_instruction_length
    addByte(1)       // default_is_stmt
    addByte(-5)      // line_base (signed)
    addByte(14)      // line_range
    addByte(13)      // opcode_base
    for n <- Array(0, 1, 1, 1, 1, 0, 0, 0, 1, 0, 0, 1) do addByte(n)

    for dir <- uniqueDirs do addStr(dir)
    addByte(0)       // end of include_directories

    for filePath <- uniqueFiles do
      val slash = filePath.lastIndexOf('/')
      val basename = if slash >= 0 then filePath.substring(slash + 1) else filePath
      val dIdx     = if slash >= 0 then dirIndex.getOrElse(filePath.substring(0, slash), 0) else 0
      addStr(basename)
      uleb128(dIdx)
      uleb128(0)     // mtime
      uleb128(0)     // file size
    addByte(0)       // end of file_names

    patchInt32(headerLengthOffset, buf.size - headerBodyStart)

    // ---- Line number program ----
    val LINE_BASE    = -5
    val LINE_RANGE   = 14
    val OPCODE_BASE  = 13

    var curAddr = 0
    var curFile = 1
    var curLine = 1

    val sorted = validMarks.sortBy(_._3)

    // DW_LNE_set_address for the first entry
    addByte(0); uleb128(5); addByte(2); addInt32(sorted.head._3)
    curAddr = sorted.head._3

    for (file, line, addr) <- sorted do
      val fIdx = fileIndex(file)

      if fIdx != curFile then
        addByte(4); uleb128(fIdx)
        curFile = fIdx

      val addrDelta = addr - curAddr
      val lineDelta = line - curLine
      val special   = (lineDelta - LINE_BASE) + LINE_RANGE * addrDelta + OPCODE_BASE

      if lineDelta >= LINE_BASE && lineDelta < LINE_BASE + LINE_RANGE &&
         addrDelta >= 0 && special <= 255 then
        addByte(special)
      else
        if addrDelta != 0 then { addByte(2); uleb128(addrDelta) }
        if lineDelta != 0 then { addByte(3); sleb128(lineDelta) }
        addByte(1)   // DW_LNS_copy

      curAddr = addr
      curLine = line

    // DW_LNE_end_sequence
    addByte(0); uleb128(1); addByte(1)

    patchInt32(unitLengthOffset, buf.size - unitLengthOffset - 4)

    // ---- Register section ----
    val bytes = buf.toArray
    val chunk = new DataChunk:
      val fileSize   = bytes.length
      val memorySize = bytes.length
      def fileBytes() = bytes

    addSection(".debug_line", baseAddr = 0, chunk, flags = 0)
  end addDebugLineSection

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
