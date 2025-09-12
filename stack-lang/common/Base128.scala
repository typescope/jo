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
  *    if (y & 1) == 0 then y >>> 1 else Long.MinValue | -(y >>> 1)
  *
  * For small numbers, the encoding saves space as 1 byte suffices.
  */
object Base128:

  def fromInt(x: Int, addByte: Byte => Unit) =
    fromLong(x, addByte)

  def fromLong(x: Long, addByte: Byte => Unit) =
    // Use signed representation to better handle small negative values
    val m = if x >= 0 then x else -x

    // Fact: -Long.MinValue = Long.MinValue

    // Note that -0 and +0 will have different meaning: -0 = Long.MinValue
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
    if (y & 1) == 0 then y >>> 1
    else Long.MinValue | -(y >>> 1)

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

  def main(args: Array[String]): Unit =
    import scala.collection.mutable.ArrayBuffer

    def testValue[T](value: T, encode: (T, Byte => Unit) => Unit, decode: (() => Byte) => T, typeName: String): Unit =
      val buffer = new ArrayBuffer[Byte]
      encode(value, buffer += _)

      var pos = 0
      val readByte = () => {
        val b = buffer(pos)
        pos += 1
        b
      }

      val decoded = decode(readByte)
      assert(decoded == value, s"$typeName: expected $value, got $decoded")
      assert(pos == buffer.size, s"Unused bytes, pos = $pos, buffer.size = ${buffer.size}")
      println(s"$typeName $value -> ${buffer.map(b => f"0x${b & 0xFF}%02X").mkString(" ")} -> $decoded ✓")

    println("Testing Base128 encoding/decoding:")

    // Test signed integers
    val intValues = List(0, 1, -1, 42, -42, 127, -127, 128, -128, 16383, -16383, Int.MaxValue, Int.MinValue)
    for value <- intValues do
      testValue(value, fromInt, toInt, "Int")

    // Test unsigned integers (Nat)
    val natValues = List(0, 1, 42, 127, 128, 16383, Int.MaxValue, Int.MinValue)
    for value <- natValues do
      testValue(value, fromNat, toNat, "Nat")

    // Test signed longs
    val longValues = List(0L, 1L, -1L, 42L, -42L, 127L, -127L, 128L, -128L, 16383L, -16383L, Long.MaxValue, Long.MinValue)
    for value <- longValues do
      testValue(value, fromLong, toLong, "Long")

    // Test unsigned longs (LongNat)
    val longNatValues = List(0L, 1L, 42L, 127L, 128L, 16383L, Long.MaxValue, Long.MinValue)
    for value <- longNatValues do
      testValue(value, fromLongNat, toLongNat, "LongNat")

    println("All Base128 tests passed!")
