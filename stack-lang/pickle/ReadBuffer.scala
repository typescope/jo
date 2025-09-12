package pickle

import common.Base128

class ReadBuffer(private val bytes: Array[Byte]) extends (() => Byte):
  private var pos: Int = 0

  final def apply(): Byte = readByte()

  def readByte(): Byte =
    if isEnded then
      throw new Exception(s"ReadBuffer overflow: pos=$pos, length=${bytes.length}")

    val b = bytes(pos)
    pos += 1
    b

  def readBool(): Boolean = readByte() != 0

  /** Read base-128 encoding of integer */
  def readInt(): Int = Base128.toInt(this)

  /** Read base-128 encoding of non-negative integer */
  def readNat(): Int = Base128.toNat(this)

  /** Read base-128 encoding of long */
  def readLong(): Long = Base128.toLong(this)

  /** Read base-128 encoding of non-negative long */
  def readLongNat(): Long = Base128.toLongNat(this)

  /** Read 4-byte big-endian 2's complement integer */
  def readIntRaw(): Int =
    ((readByte() & 0xFF) << 24) | ((readByte() & 0xFF) << 16) | ((readByte() & 0xFF) << 8) | (readByte() & 0xFF)

  def readUtf8(): String =
    val length = readNat()
    val strBytes = new Array[Byte](length)
    readBytes(strBytes, length)
    new String(strBytes, java.nio.charset.StandardCharsets.UTF_8)

  private def readBytes(data: Array[Byte], n: Int): Unit =
    if pos + n > bytes.length then
      throw new Exception(s"ReadBuffer overflow: pos=$pos, n=$n, length=${bytes.length}")

    System.arraycopy(bytes, pos, data, 0, n)
    pos += n

  def position: Int = pos

  def withPosition[T](newPos: Int)(work: => T): T =
    val savedPos = pos
    setPosition(newPos)
    val res = work
    pos = savedPos
    res

  def setPosition(newPos: Int): Unit =
    if newPos < 0 || newPos > bytes.length then
      throw new Exception(s"Invalid position: $newPos (length=${bytes.length})")
    pos = newPos

  def fresh(newPos: Int): ReadBuffer =
    val buf = new ReadBuffer(this.bytes)
    buf.setPosition(newPos)
    buf

  def fresh(): ReadBuffer =
    val buf = new ReadBuffer(this.bytes)
    buf.setPosition(this.pos)
    buf

  def isEnded: Boolean = pos >= bytes.length
