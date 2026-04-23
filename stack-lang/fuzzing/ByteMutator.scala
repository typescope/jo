package fuzzing

import scala.util.Random

/** Language-oblivious byte-level mutator. Fast, always makes progress on any
  * input. Exercises the scanner and parser's error-recovery paths.
  */
object ByteMutator extends Mutator:

  import Mutator.*

  /** Apply 1–4 random edits to `input`. Other seed bytes are used as a splice source. */
  def mutate(input: Array[Byte], rng: Random, others: IndexedSeq[Array[Byte]]): Array[Byte] =
    val k = 1 + rng.nextInt(4)
    (0 until k).foldLeft(input)((bs, _) => mutateOnce(bs, rng, others))

  private def mutateOnce(bytes: Array[Byte], rng: Random, others: IndexedSeq[Array[Byte]]): Array[Byte] =
    if bytes.isEmpty then insertPrintable(bytes, rng)
    else
      rng.nextInt(10) match
        case 0 => flipByte(bytes, rng)
        case 1 => deleteByte(bytes, rng)
        case 2 => insertRandom(bytes, rng)
        case 3 => insertPrintable(bytes, rng)
        case 4 => insertKeyword(bytes, rng)
        case 5 => insertOperator(bytes, rng)
        case 6 => duplicateRegion(bytes, rng)
        case 7 => deleteRegion(bytes, rng)
        case 8 => if others.nonEmpty then splice(bytes, rng, others) else insertPrintable(bytes, rng)
        case _ => if others.nonEmpty then splice(bytes, rng, others) else insertKeyword(bytes, rng)

  private def flipByte(bytes: Array[Byte], rng: Random): Array[Byte] =
    val i = rng.nextInt(bytes.length)
    val out = bytes.clone()
    out(i) = (out(i) ^ (1 << rng.nextInt(8))).toByte
    out

  private def deleteByte(bytes: Array[Byte], rng: Random): Array[Byte] =
    val i = rng.nextInt(bytes.length)
    bytes.take(i) ++ bytes.drop(i + 1)

  private def insertRandom(bytes: Array[Byte], rng: Random): Array[Byte] =
    insertByte(bytes, rng, rng.nextInt(256).toByte)

  private def insertPrintable(bytes: Array[Byte], rng: Random): Array[Byte] =
    insertByte(bytes, rng, (32 + rng.nextInt(95)).toByte)

  private def insertByte(bytes: Array[Byte], rng: Random, b: Byte): Array[Byte] =
    val i = if bytes.isEmpty then 0 else rng.nextInt(bytes.length + 1)
    bytes.take(i) ++ Array(b) ++ bytes.drop(i)

  private def insertKeyword(bytes: Array[Byte], rng: Random): Array[Byte] =
    insertString(bytes, rng, " " + Keywords(rng.nextInt(Keywords.length)) + " ")

  private def insertOperator(bytes: Array[Byte], rng: Random): Array[Byte] =
    val seq = if rng.nextBoolean() then Operators else Punctuation
    insertString(bytes, rng, seq(rng.nextInt(seq.length)))

  private def insertString(bytes: Array[Byte], rng: Random, s: String): Array[Byte] =
    val i = if bytes.isEmpty then 0 else rng.nextInt(bytes.length + 1)
    bytes.take(i) ++ s.getBytes("UTF-8") ++ bytes.drop(i)

  private def duplicateRegion(bytes: Array[Byte], rng: Random): Array[Byte] =
    val len    = 1 + rng.nextInt(math.min(16, bytes.length))
    val src    = rng.nextInt(bytes.length - len + 1)
    val dst    = rng.nextInt(bytes.length + 1)
    val region = bytes.slice(src, src + len)
    bytes.take(dst) ++ region ++ bytes.drop(dst)

  private def deleteRegion(bytes: Array[Byte], rng: Random): Array[Byte] =
    val len = 1 + rng.nextInt(math.min(32, bytes.length))
    val i   = rng.nextInt(bytes.length - len + 1)
    bytes.take(i) ++ bytes.drop(i + len)

  private def splice(bytes: Array[Byte], rng: Random, others: IndexedSeq[Array[Byte]]): Array[Byte] =
    val other = others(rng.nextInt(others.length))
    if other.isEmpty then bytes
    else
      val srcLen = 1 + rng.nextInt(math.min(128, other.length))
      val src    = rng.nextInt(other.length - srcLen + 1)
      val region = other.slice(src, src + srcLen)
      val dst    = if bytes.isEmpty then 0 else rng.nextInt(bytes.length + 1)
      bytes.take(dst) ++ region ++ bytes.drop(dst)

end ByteMutator
