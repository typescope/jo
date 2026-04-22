package fuzzing

import java.nio.file.Files

/** Line-based delta-debugging reducer.
  *
  * Repeatedly tries to delete contiguous blocks of lines while preserving the
  * crash's fingerprint. Starts with coarse granularity (half the file) and
  * halves down to one line. Good enough for a first pass; a proper AST-level
  * Perses-style reducer is a later upgrade.
  *
  * The reducer uses a tighter per-trial timeout than the main fuzzing loop —
  * reduction makes many calls, and a single slow trial shouldn't stall us.
  */
object Reducer:

  private val DefaultTrialTimeoutSeconds = 5
  private val DefaultMaxIterations       = 500

  def reduce(
      input: Array[Byte],
      target: Target,
      expected: Fingerprint,
      maxIterations: Int = DefaultMaxIterations,
      trialTimeoutSeconds: Int = DefaultTrialTimeoutSeconds,
  ): Array[Byte] =

    var lines       = splitLines(input)
    var current     = input
    var iter        = 0
    var granularity = math.max(1, lines.size / 2)

    while granularity >= 1 && iter < maxIterations do
      var i        = 0
      var progress = false

      while i + granularity <= lines.size && iter < maxIterations do
        iter += 1
        val trial      = lines.take(i) ++ lines.drop(i + granularity)
        val trialBytes = joinLines(trial)

        if triggers(trialBytes, target, expected, trialTimeoutSeconds) then
          lines = trial
          current = trialBytes
          progress = true
        else
          i += granularity
      end while

      if !progress then granularity /= 2
    end while

    current
  end reduce

  private def splitLines(bytes: Array[Byte]): Vector[String] =
    new String(bytes, "UTF-8").split("\n", -1).toVector

  private def joinLines(lines: Vector[String]): Array[Byte] =
    lines.mkString("\n").getBytes("UTF-8")

  private def triggers(bytes: Array[Byte], target: Target, expected: Fingerprint, timeoutSeconds: Int): Boolean =
    val tmp = Files.createTempFile("fuzz-reduce-", ".jo")
    try
      Files.write(tmp, bytes)
      val outcome = Harness.run(tmp.toString, target, timeoutSeconds)
      Oracle.fingerprint(outcome, target).exists(_.bucketId == expected.bucketId)
    finally
      Files.deleteIfExists(tmp)

end Reducer
