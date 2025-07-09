package common

object Base64:

  private val base64Alphabet: Array[Char] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray

  private val base64Inverse: Array[Int] =
    val arr = Array.fill(128)(-1)
    var i = 0
    while i < base64Alphabet.length do
      arr(base64Alphabet(i)) = i
      i += 1
    arr

  /** Represent an integer in base64 without padding
    *
    * It is more space-efficient than byte representation for small integers.
    */
  def intToBase64(value: Int): String =
    if value == 0 then return base64Alphabet(0).toString

    val bitLength = 32 - Integer.numberOfLeadingZeros(value)
    val bitsToProcess = ((bitLength + 5) / 6) * 6
    val sb = new StringBuilder((bitsToProcess + 5) / 6)

    var i = bitsToProcess - 6
    while i >= 0 do
      val index = (value >> i) & 0x3F
      sb.append(base64Alphabet(index))
      i -= 6

    sb.toString()

  def base64ToInt(s: String): Int =
    var result = 0
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      val value =
        if c < 128 && base64Inverse(c) != -1 then base64Inverse(c)
        else throw IllegalArgumentException(s"Invalid Base64 character: $c")
      result = (result << 6) | value
      i += 1
    result
