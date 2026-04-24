package fuzzing

/** Line-based delta-debugging reducer.
  *
  * Repeatedly tries to delete contiguous blocks of lines while preserving
  * the crash's fingerprint. Starts with coarse granularity (half the file)
  * and halves down to one line. Always applicable — works on any byte
  * sequence, including inputs the scanner can't tokenize.
  */
object LineDeltaReducer extends Reducer:

  def reduce(
      input: Array[Byte],
      target: Target,
      expected: Fingerprint,
      maxIterations: Int = Reducer.DefaultMaxIterations,
      trialTimeoutSeconds: Int = Reducer.DefaultTrialTimeoutSeconds,
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

        if Reducer.triggers(trialBytes, target, expected, trialTimeoutSeconds) then
          lines    = trial
          current  = trialBytes
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

end LineDeltaReducer
