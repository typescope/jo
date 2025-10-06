package common

object Base64:

  private val Base64Alphabet: Array[Char] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray

  private val Base64Inverse: Array[Int] =
    val arr = Array.fill(128)(-1)
    var i = 0
    while i < Base64Alphabet.length do
      arr(Base64Alphabet(i)) = i
      i += 1
    arr

  /** Represent an integer in base64 without padding
    *
    * It is more space-efficient than byte representation for small integers.
    */
  def intToBase64(value: Int): String =
    if value == 0 then return "A"

    val sb = new StringBuilder(6)
    val bitsToProcess = 36

    var i = bitsToProcess - 6
    while i >= 0 do
      val index = (value >> i) & 0x3F
      // value == 0 is handled as a special case at the begining
      if index > 0 || sb.length > 0 then
        sb.append(Base64Alphabet(index))
      i -= 6

    sb.toString()

  def base64ToInt(s: String): Int =
    var result = 0
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      val value =
        if c < 128 && Base64Inverse(c) != -1 then Base64Inverse(c)
        else throw IllegalArgumentException(s"Invalid Base64 character: $c")
      result = (result << 6) | value
      i += 1
    result
