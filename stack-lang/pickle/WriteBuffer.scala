package pickle

import common.Base128

class WriteBuffer(initialSize: Int) extends (Byte => Unit):
  private var bytes: Array[Byte] = new Array(initialSize)
  private var size: Int = 0

  final def apply(b: Byte): Unit = addByte(b)

  def addByte(b: Byte): Unit =
    if (size >= bytes.length)
      bytes = WriteBuffer.doubleSize(bytes)

    bytes(size) = b.toByte
    size += 1

  def addInt(x: Int): Unit = Base128.writeInt(x, this)

  def addLong(x: Long): Unit = Base128.writeLong(x, this)

  def addUtf8(s: String): Unit =
    val bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val length = bytes.length
    addInt(length)
    addBytes(bytes, length)

  def addBytes(data: Array[Byte], n: Int): Unit =
    while (bytes.length < size + n) bytes = WriteBuffer.doubleSize(bytes)
    System.arraycopy(data, 0, bytes, size, n)
    size += n

object WriteBuffer:
  def doubleSize(arr: Array[Byte]): Array[Byte] =
    val arr1 = new Array[Byte](arr.length * 2)
    System.arraycopy(arr, 0, arr1, 0, arr.length)
    arr1
