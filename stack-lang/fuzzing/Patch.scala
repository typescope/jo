package fuzzing

/** A single edit against a byte array.
  *
  *   `bytes[start, start + length)` is replaced by `replacement`.
  *
  * Shape:
  *   - insertion  ≡ `length = 0`
  *   - deletion   ≡ `replacement` is empty
  *   - overwrite  ≡ both non-zero
  *
  * Patches are a shared currency between mutation strategies:
  *   - [[ByteMutator]]  emits single-byte / small-region patches.
  *   - [[TokenMutator]] emits token-aligned patches.
  *   - Future AST-level mutators will emit patches that span pretty-printed AST
  *     ranges.
  *
  * All patches returned from a single mutation call reference offsets in the
  * *original* byte array. The applier ([[Patch.applyAll]]) sorts them, drops
  * overlaps, and writes the result in a single walk. Ops therefore don't
  * observe each other's edits, which keeps offset arithmetic local to each op.
  */
case class Patch(start: Int, length: Int, replacement: Array[Byte]):
  def end: Int = start + length

object Patch:

  /** Sort `patches` by start offset, drop any patch whose range overlaps a
    * previously kept patch, and splice the survivors into `bytes`.
    *
    * Overlap policy is "first wins" after sorting by start — simple and
    * predictable. Two zero-length patches at the same position are both kept
    * (neither overlaps the other), so multiple insertions at one point are
    * allowed.
    */
  def applyAll(bytes: Array[Byte], patches: Seq[Patch]): Array[Byte] =
    if patches.isEmpty then bytes
    else
      val sorted  = patches.sortBy(_.start)
      val kept    = Vector.newBuilder[Patch]
      var lastEnd = 0
      var first   = true

      for p <- sorted do
        if first || p.start >= lastEnd then
          kept   += p
          lastEnd = p.end
          first   = false

      val filtered = kept.result()
      val out      = new java.io.ByteArrayOutputStream(bytes.length + 32)
      var cursor   = 0

      for p <- filtered do
        if p.start > cursor then
          out.write(bytes, cursor, p.start - cursor)
        if p.replacement.nonEmpty then
          out.write(p.replacement)
        cursor = p.end

      if cursor < bytes.length then
        out.write(bytes, cursor, bytes.length - cursor)

      out.toByteArray
  end applyAll

end Patch
