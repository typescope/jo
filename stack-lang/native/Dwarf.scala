package native

import ELF32.DataChunk
import java.nio.charset.StandardCharsets
import scala.collection.mutable

/**
  * Generates DWARF 2 debug sections as raw DataChunk values.
  *
  * Reference:
  *   DWARF Debugging Information Format, Version 2
  *   https://dwarfstd.org/doc/dwarf-2.0.0.pdf
  */
object Dwarf:

  // ---- Tags ----
  private val DW_TAG_compile_unit = 0x11

  // ---- Attributes ----
  private val DW_AT_name      = 0x03
  private val DW_AT_stmt_list = 0x10
  private val DW_AT_low_pc    = 0x11
  private val DW_AT_high_pc   = 0x12
  private val DW_AT_language  = 0x13
  private val DW_AT_comp_dir  = 0x1b

  // ---- Forms ----
  private val DW_FORM_addr   = 0x01
  private val DW_FORM_data2  = 0x05
  private val DW_FORM_data4  = 0x06
  private val DW_FORM_string = 0x08

  // ---- Misc ----
  private val DW_CHILDREN_no = 0
  private val DW_LANG_C      = 1

  // ---- Line-number standard opcodes ----
  private val DW_LNS_copy         = 1
  private val DW_LNS_advance_pc   = 2
  private val DW_LNS_advance_line = 3
  private val DW_LNS_set_file     = 4

  // ---- Line-number extended opcodes ----
  private val DW_LNE_end_sequence = 1
  private val DW_LNE_set_address  = 2

  // ---- Byte buffer with DWARF encoding helpers ----

  private class DwarfBuffer:
    private val buf = new mutable.ArrayBuffer[Byte]

    def byte(b: Int): Unit = buf += b.toByte
    def int16(v: Int): Unit = { byte(v); byte(v >> 8) }
    def int32(v: Int): Unit = { byte(v); byte(v >> 8); byte(v >> 16); byte(v >> 24) }
    def patch32(pos: Int, v: Int): Unit =
      buf(pos)     = v.toByte
      buf(pos + 1) = (v >> 8).toByte
      buf(pos + 2) = (v >> 16).toByte
      buf(pos + 3) = (v >> 24).toByte
    def str(s: String): Unit =
      for b <- s.getBytes(StandardCharsets.UTF_8) do buf += b
      buf += 0
    def uleb128(v: Int): Unit =
      var x = v
      while
        val b = x & 0x7F
        x >>>= 7
        if x != 0 then buf += (b | 0x80).toByte else buf += b.toByte
        x != 0
      do ()
    def sleb128(v: Int): Unit =
      var x = v
      var more = true
      while more do
        val b = x & 0x7F
        x >>= 7
        if (x == 0 && (b & 0x40) == 0) || (x == -1 && (b & 0x40) != 0) then
          buf += b.toByte; more = false
        else
          buf += (b | 0x80).toByte
    def size: Int = buf.size
    def toDataChunk: DataChunk =
      val bytes = buf.toArray
      new DataChunk:
        val fileSize   = bytes.length
        val memorySize = bytes.length
        def fileBytes() = bytes
  end DwarfBuffer

  /** DWARF 2 abbreviation table: one entry for DW_TAG_compile_unit. */
  def abbrevSection(): DataChunk =
    val dw = new DwarfBuffer
    import dw.*

    uleb128(1); uleb128(DW_TAG_compile_unit); byte(DW_CHILDREN_no)
    uleb128(DW_AT_low_pc);    uleb128(DW_FORM_addr)
    uleb128(DW_AT_high_pc);   uleb128(DW_FORM_addr)
    uleb128(DW_AT_stmt_list); uleb128(DW_FORM_data4)
    uleb128(DW_AT_comp_dir);  uleb128(DW_FORM_string)
    uleb128(DW_AT_name);      uleb128(DW_FORM_string)
    uleb128(DW_AT_language);  uleb128(DW_FORM_data2)
    byte(0); byte(0)   // end of attributes
    byte(0)            // end of abbreviation table

    dw.toDataChunk

  /** DWARF 2 compile-unit DIE pointing at the line-number program. */
  def infoSection(primaryFile: String, compDir: String, lowPc: Int, highPc: Int): DataChunk =
    val dw = new DwarfBuffer
    import dw.*

    val unitLengthOffset = size
    int32(0); int16(2); int32(0); byte(4)   // unit_length, version=2, abbrev_offset=0, addr_size=4

    uleb128(1)        // DW_TAG_compile_unit (abbrev code 1)
    int32(lowPc)      // DW_AT_low_pc
    int32(highPc)     // DW_AT_high_pc
    int32(0)          // DW_AT_stmt_list = 0
    str(compDir)      // DW_AT_comp_dir
    str(primaryFile)  // DW_AT_name
    int16(DW_LANG_C)  // DW_AT_language

    patch32(unitLengthOffset, size - unitLengthOffset - 4)

    dw.toDataChunk

  /** DWARF 2 line-number program mapping addresses to source file/line pairs. */
  def lineSection(locMarks: List[(String, Int, Int)]): Option[DataChunk] =
    val validMarks = locMarks.filter(_._1.nonEmpty)
    if validMarks.isEmpty then return None

    val dw = new DwarfBuffer
    import dw.*

    // Build directory and file tables
    val uniqueFiles = validMarks.map(_._1).distinct
    val uniqueDirs  = uniqueFiles
                        .map { p => val i = p.lastIndexOf('/'); if i >= 0 then p.substring(0, i) else "" }
                        .distinct.filter(_.nonEmpty)
    val dirIndex  = uniqueDirs.zipWithIndex.map((d, i) => d -> (i + 1)).toMap
    val fileIndex = uniqueFiles.zipWithIndex.map((f, i) => f -> (i + 1)).toMap

    // ---- Header ----
    val unitLengthOffset = size
    int32(0); int16(2)   // unit_length placeholder, DWARF version 2
    val headerLengthOffset = size
    int32(0)             // header_length placeholder
    val headerBodyStart = size

    // minimum_instruction_length, default_is_stmt, line_base, line_range, opcode_base
    byte(1); byte(1); byte(-5); byte(14); byte(13)
    for n <- Array(0, 1, 1, 1, 1, 0, 0, 0, 1, 0, 0, 1) do byte(n)

    for dir <- uniqueDirs do str(dir)
    byte(0)   // end of include_directories

    for filePath <- uniqueFiles do
      val slash    = filePath.lastIndexOf('/')
      val basename = if slash >= 0 then filePath.substring(slash + 1) else filePath
      val dIdx     = if slash >= 0 then dirIndex.getOrElse(filePath.substring(0, slash), 0) else 0
      str(basename); uleb128(dIdx); uleb128(0); uleb128(0)   // name, dir, mtime, size
    byte(0)   // end of file_names

    patch32(headerLengthOffset, size - headerBodyStart)

    // ---- Line number program ----
    val LINE_BASE   = -5
    val LINE_RANGE  = 14
    val OPCODE_BASE = 13

    var curAddr = 0
    var curFile = 1
    var curLine = 1

    // Sort by address, then drop exact duplicate (addr, file, line) rows
    val rows = validMarks.sortBy(_._3).distinctBy(m => (m._3, m._1, m._2))

    byte(0); uleb128(5); byte(DW_LNE_set_address); int32(rows.head._3)
    curAddr = rows.head._3

    for (file, line, addr) <- rows do
      val fIdx = fileIndex(file)

      if fIdx != curFile then
        byte(DW_LNS_set_file); uleb128(fIdx)
        curFile = fIdx

      val addrDelta = addr - curAddr
      val lineDelta = line - curLine
      val special   = (lineDelta - LINE_BASE) + LINE_RANGE * addrDelta + OPCODE_BASE

      if lineDelta >= LINE_BASE && lineDelta < LINE_BASE + LINE_RANGE &&
         addrDelta >= 0 && special <= 255 then
        byte(special)
      else
        if addrDelta != 0 then { byte(DW_LNS_advance_pc); uleb128(addrDelta) }
        if lineDelta != 0 then { byte(DW_LNS_advance_line); sleb128(lineDelta) }
        byte(DW_LNS_copy)

      curAddr = addr
      curLine = line

    byte(0); uleb128(1); byte(DW_LNE_end_sequence)

    patch32(unitLengthOffset, size - unitLengthOffset - 4)

    Some(dw.toDataChunk)
  end lineSection

end Dwarf
