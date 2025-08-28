package pickle

import common.Base128

class ReadBuffer(private val bytes: Array[Byte]) extends (() => Byte):
  private var pos: Int = 0

  final def apply(): Byte = readByte()

  def readByte(): Byte =
    if pos >= bytes.length then
      throw new Exception(s"ReadBuffer overflow: pos=$pos, length=${bytes.length}")

    val b = bytes(pos)
    pos += 1
    b

  def readBool(): Boolean = Base128.toLong(this) != 0

  def readInt(): Int = Base128.toInt(this)

  def readNat(): Int = Base128.toNat(this)

  def readLong(): Long = Base128.toLong(this)

  def readUtf8(): String =
    val length = readNat()
    val strBytes = new Array[Byte](length)
    readBytes(strBytes, length)
    new String(strBytes, java.nio.charset.StandardCharsets.UTF_8)

  def readBytes(data: Array[Byte], n: Int): Unit =
    if pos + n > bytes.length then
      throw new Exception(s"ReadBuffer overflow: pos=$pos, n=$n, length=${bytes.length}")

    System.arraycopy(bytes, pos, data, 0, n)
    pos += n

  def position: Int = pos

  def setPosition(newPos: Int): Unit =
    if newPos < 0 || newPos > bytes.length then
      throw new Exception(s"Invalid position: $newPos (length=${bytes.length})")
    pos = newPos

  def remaining: Int = bytes.length - pos

  def isEnd: Boolean = pos >= bytes.length
