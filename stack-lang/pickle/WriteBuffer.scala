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

  def addBool(x: Boolean): Unit = Base128.fromLong(if x then 1 else 0, this)

  def addInt(x: Int): Unit = Base128.fromInt(x, this)

  def addNat(x: Int): Unit = Base128.fromNat(x, this)

  def addLong(x: Long): Unit = Base128.fromLong(x, this)

  def addUtf8(s: String): Unit =
    val bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val length = bytes.length
    addNat(length)
    addBytes(bytes, length)

  def addBytes(data: Array[Byte], n: Int): Unit =
    while (bytes.length < size + n) bytes = WriteBuffer.doubleSize(bytes)
    System.arraycopy(data, 0, bytes, size, n)
    size += n

  /** Reserve 4-byte slot for big-endian 2's complement integer
    *
    * Returns an index to the byte array which can be used to write the actual
    * value.
    */
  def reserveInt(): Int =
    val addr = size
    addByte(0)
    addByte(0)
    addByte(0)
    addByte(0)
    addr

  def patchInt(addr: Int, value: Int) =
    bytes(addr) = (value >>> 24).toByte
    bytes(addr + 1) = (value >>> 16).toByte
    bytes(addr + 2) = (value >>> 8).toByte
    bytes(addr + 3) = value.toByte

  def length: Int = size

  def getBytes: Array[Byte] = bytes

object WriteBuffer:
  def doubleSize(arr: Array[Byte]): Array[Byte] =
    val arr1 = new Array[Byte](arr.length * 2)
    System.arraycopy(arr, 0, arr1, 0, arr.length)
    arr1
