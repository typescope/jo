package common

/**
 * A byte buffer
 */
abstract class ByteBuffer:
  def addByte(value: Byte): Unit

  /** Little endian encoding of Short */
  def addShort(value: Int): Unit =
    val MASK: Int = 0xFF
    addByte((value & MASK).toByte)
    addByte(((value >> 8) & MASK).toByte)

  /** Little endian encoding of Int */
  def addInt(value: Int): Unit =
    val MASK: Int = 0xFF
    addByte((value & MASK).toByte)
    addByte(((value >> 8) & MASK).toByte)
    addByte(((value >> 16) & MASK).toByte)
    addByte(((value >> 24) & MASK).toByte)

  def addBytes(bytes: Seq[Byte]): Unit =
    for byte <- bytes do addByte(byte)

  def addBytes(byte: Byte, bytes: Byte*): Unit =
    addByte(byte)
    addBytes(bytes)

  def addZeros(n: Int): Unit =
    for _ <-0 until n do addByte(0)
