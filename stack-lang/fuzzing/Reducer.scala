package fuzzing

import java.nio.file.Files

/** Shrinks a crashing input while preserving the crash's fingerprint.
  *
  * Two strategies live in this package:
  *   - [[AstReducer]]       — parses the input, shrinks the parse tree by
  *     removing list elements (definitions, statements, match cases, ...),
  *     prints the result, and re-runs the target phase. Fast and produces
  *     much smaller reproducers when the input parses.
  *   - [[LineDeltaReducer]] — coarse-to-fine line-delta debugging. Works on
  *     any byte sequence, even inputs the scanner can't tokenize. Slower
  *     and produces coarser reproducers.
  *
  * Prefer [[Reducer.best]] to pick the finest strategy whose precondition
  * holds for a given input.
  */
trait Reducer:
  def reduce(
      input: Array[Byte],
      target: Target,
      expected: Fingerprint,
      maxIterations: Int = Reducer.DefaultMaxIterations,
      trialTimeoutSeconds: Int = Reducer.DefaultTrialTimeoutSeconds,
  ): Array[Byte]

object Reducer:

  val DefaultTrialTimeoutSeconds: Int = 5
  val DefaultMaxIterations: Int       = 500

  /** Pick the finest reducer whose precondition holds for `input`.
    *
    * Currently: [[AstReducer]] if the input parses without crashing, else
    * [[LineDeltaReducer]].
    */
  def best(input: Array[Byte]): Reducer =
    if AstReducer.canReduce(input) then AstReducer
    else LineDeltaReducer

  /** Trial entry point shared by all strategies.
    *
    * Writes `bytes` to a temp file, runs `target`, and reports whether the
    * outcome fingerprints to `expected`.
    */
  private[fuzzing] def triggers(
      bytes: Array[Byte],
      target: Target,
      expected: Fingerprint,
      timeoutSeconds: Int
  ): Boolean =
    val tmp = Files.createTempFile("fuzz-reduce-", ".jo")
    try
      Files.write(tmp, bytes)
      val outcome = Harness.run(tmp.toString, target, timeoutSeconds)
      Oracle.fingerprint(outcome, target).exists(_.bucketId == expected.bucketId)
    finally
      Files.deleteIfExists(tmp)
  end triggers

end Reducer
