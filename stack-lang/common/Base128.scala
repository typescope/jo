package common

/** Base-128 big-endian byte encoding of long/int
  *
  * == Unsigned numbers
  *
  * An unsigned number is encoded as base-128 big-endian bytes for every 7
  * significant bits. The 8th bit of the lowest byte is used to signify the end
  * of the encoding.
  *
  * == Signed number
  *
  * A signed number is first converted from 2's complement to signed-bit
  * representation:
  *
  *    m = if x >= 0 then x else -x
  *    y = (m << 1) | ((x >>> 31) & 1)
  *
  * The transform makes sure that small negative numbers get compact encoding.
  *
  * The bits of the signed representation is then encoded as base-128 big-endian
  * bytes. The 8th bit of the lowest byte is used to signify the end of the
  * encoding.
  *
  * During decoding, the base-128 bytes are first assembled to the signed-bit
  * representation. The latter is then converted to 2's complement
  * representation:
  *
  *    if (y & 1) == 0 then y >>> 1 else -(y >>> 1)
  *
  * For small numbers, the encoding saves space as 1 byte suffices.
  */
object Base128:

  def fromInt(x: Int, addByte: Byte => Unit) =
    fromLong(x, addByte)

  def fromLong(x: Long, addByte: Byte => Unit) =
    // Use signed representation to better handle small negative values
    val m = if x >= 0 then x else -x
    val y = (m << 1) | ((x >>> 63) & 1)
    fromLongNat(y, addByte)

  def fromNat(x: Int, addByte: Byte => Unit) =
    fromLongNat(x & 0xFFFFFFFF, addByte)

  def fromLongNat(x: Long, addByte: Byte => Unit) =
    val MASK: Long = 0x7F

    def addPrefix(prefix: Long): Unit =
      if prefix != 0 then
        addPrefix(prefix >>> 7)
        addByte((prefix & MASK).toByte)

    addPrefix(x >>> 7)
    addByte(((x & MASK) | 0x80).toByte)

  def toInt(readByte: () => Byte): Int =
    val x = toLong(readByte)
    assert(x >= Int.MinValue && x <= Int.MaxValue, x)
    x.toInt

  def toLong(readByte: () => Byte): Long =
    val y = toLongNat(readByte)
    if (y & 1) == 0 then y >>> 1 else -(y >>> 1)

  def toNat(readByte: () => Byte): Int =
    val y = toLongNat(readByte)
    assert(y <= Int.MaxValue && y >= Int.MinValue, "not valid int: " + y)
    y.toInt

  def toLongNat(readByte: () => Byte): Long =
    val MASK: Long = 0x7F

    var y: Long = 0
    var continue = true
    while continue do
      val b = readByte()
      y = (y << 7) | (b & MASK)
      continue = (b & 0x80) == 0
    end while
    y
