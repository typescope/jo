package fuzzing

import scala.util.Random

/** Language-oblivious byte-level mutator. Fast, always makes progress on any
  * input. Exercises the scanner and parser's error-recovery paths.
  *
  * Each operator emits an [[Patch]] against the *original* byte array; the
  * main loop picks 1–4 operators, collects their patches, and applies them
  * together via [[Patch.applyAll]]. This keeps offsets simple and lets us
  * share the applier with [[TokenMutator]].
  */
object ByteMutator extends Mutator:

  import Mutator.*

  private type Op = (Array[Byte], Random, IndexedSeq[Array[Byte]]) => Option[Patch]

  def mutate(input: Array[Byte], rng: Random, others: IndexedSeq[Array[Byte]]): Array[Byte] =
    val k       = 1 + rng.nextInt(4)
    val patches = (0 until k).flatMap(_ => pickOp(rng)(input, rng, others))
    Patch.applyAll(input, patches)

  private def pickOp(rng: Random): Op =
    rng.nextInt(10) match
      case 0 => flipByte
      case 1 => deleteByte
      case 2 => insertRandom
      case 3 => insertPrintable
      case 4 => insertKeyword
      case 5 => insertOperator
      case 6 => duplicateRegion
      case 7 => deleteRegion
      case 8 => splice
      case _ => splice
  end pickOp

  //--------------------------------------------------------------------------
  // Ops

  private def flipByte: Op = (bytes, rng, _) =>
    if bytes.isEmpty then insertPrintable(bytes, rng, IndexedSeq.empty)
    else
      val i       = rng.nextInt(bytes.length)
      val flipped = (bytes(i) ^ (1 << rng.nextInt(8))).toByte
      Some(Patch(i, 1, Array(flipped)))

  private def deleteByte: Op = (bytes, rng, _) =>
    if bytes.isEmpty then None
    else
      val i = rng.nextInt(bytes.length)
      Some(Patch(i, 1, Array.emptyByteArray))

  private def insertRandom: Op = (bytes, rng, _) =>
    insertAt(bytes, rng, Array(rng.nextInt(256).toByte))

  private def insertPrintable: Op = (bytes, rng, _) =>
    insertAt(bytes, rng, Array((32 + rng.nextInt(95)).toByte))

  private def insertKeyword: Op = (bytes, rng, _) =>
    insertAt(bytes, rng, (" " + Keywords(rng.nextInt(Keywords.length)) + " ").getBytes("UTF-8"))

  private def insertOperator: Op = (bytes, rng, _) =>
    val seq = if rng.nextBoolean() then Operators else Punctuation
    insertAt(bytes, rng, seq(rng.nextInt(seq.length)).getBytes("UTF-8"))

  private def duplicateRegion: Op = (bytes, rng, _) =>
    if bytes.isEmpty then None
    else
      val len    = 1 + rng.nextInt(math.min(16, bytes.length))
      val src    = rng.nextInt(bytes.length - len + 1)
      val dst    = rng.nextInt(bytes.length + 1)
      val region = bytes.slice(src, src + len)
      Some(Patch(dst, 0, region))

  private def deleteRegion: Op = (bytes, rng, _) =>
    if bytes.isEmpty then None
    else
      val len = 1 + rng.nextInt(math.min(32, bytes.length))
      val i   = rng.nextInt(bytes.length - len + 1)
      Some(Patch(i, len, Array.emptyByteArray))

  private def splice: Op = (bytes, rng, others) =>
    if others.isEmpty then insertPrintable(bytes, rng, others)
    else
      val other = others(rng.nextInt(others.length))
      if other.isEmpty then None
      else
        val srcLen = 1 + rng.nextInt(math.min(128, other.length))
        val src    = rng.nextInt(other.length - srcLen + 1)
        val region = other.slice(src, src + srcLen)
        val dst    = if bytes.isEmpty then 0 else rng.nextInt(bytes.length + 1)
        Some(Patch(dst, 0, region))

  private def insertAt(bytes: Array[Byte], rng: Random, region: Array[Byte]): Option[Patch] =
    val at = if bytes.isEmpty then 0 else rng.nextInt(bytes.length + 1)
    Some(Patch(at, 0, region))

end ByteMutator
