package fuzzing

import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate

import scala.collection.mutable

import common.IO

/** Tracks crash buckets for a single fuzzing run and persists findings to disk.
  *
  * Layout:
  *   `<baseDir>/<YYYY-MM-DD>/<target>-<kind>-<bucketId>/`
  *     - `input.jo`     — the exact bytes that crashed
  *     - `trace.txt`    — exception class, message, full stack
  *     - `manifest.txt` — fingerprint fields (for later programmatic triage)
  *     - `reduced.jo`   — written by the caller if reduction runs
  *
  * In-memory dedup is per-process. Across runs the same bucket may be
  * re-created, overwriting the previous `input.jo`; that's acceptable for
  * the MVP. Cross-run dedup is a later feature.
  */
class Findings(baseDir: String, verbose: Boolean = false):

  private val seen          = mutable.Map.empty[String, Int]
  private var crashesTotal  = 0
  private var timeoutsTotal = 0

  def recordCrash(fp: Fingerprint, input: Array[Byte], t: Throwable): Boolean =
    crashesTotal += 1
    val prev = seen.getOrElse(fp.bucketId, 0)
    seen(fp.bucketId) = prev + 1
    val isNew = prev == 0

    if isNew then
      writeBucket(fp, input, t)
      if verbose then println(s"[new] ${fp.display}")

    isNew
  end recordCrash

  def recordTimeout(): Unit =
    timeoutsTotal += 1

  /** Filesystem path of the bucket directory for `fp`, creating it if needed. */
  def bucketDir(fp: Fingerprint): Path =
    val dir = Paths.get(baseDir, LocalDate.now.toString, s"${fp.target}-${fp.kind}-${fp.bucketId}")
    IO.ensureExists(dir.toString)
    dir

  def summary: String =
    s"$crashesTotal crashes / ${seen.size} unique buckets / $timeoutsTotal timeouts"

  private def writeBucket(fp: Fingerprint, input: Array[Byte], t: Throwable): Unit =
    val dir = bucketDir(fp)

    IO.writeFile(dir.resolve("input.jo").toString, input)

    IO.withPrintWriter(dir.resolve("trace.txt").toString): pw =>
      pw.println(fp.display)
      pw.println()
      pw.println(s"${t.getClass.getName}: ${t.getMessage}")
      for frame <- t.getStackTrace do pw.println("    at " + frame)

    IO.withPrintWriter(dir.resolve("manifest.txt").toString): pw =>
      pw.println(s"target=${fp.target}")
      pw.println(s"kind=${fp.kind}")
      pw.println(s"bucket=${fp.bucketId}")
      pw.println(s"message=${fp.messagePattern}")
      pw.println("frames:")
      for f <- fp.topFrames do pw.println("  " + f)
  end writeBucket

end Findings
