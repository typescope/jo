package fuzzing

import java.security.MessageDigest

/** Stable crash identity. Two inputs with the same `bucketId` are considered
  * duplicates for triage purposes. The fingerprint is intentionally coarse —
  * we want inputs that differ only in surface whitespace, positions, or
  * temp-file names to collapse into a single bucket.
  */
case class Fingerprint(
    target: Target,
    kind: CrashKind,
    messagePattern: String,
    topFrames: List[String],
):
  def bucketId: String =
    val joined = s"$target|$kind|$messagePattern|${topFrames.mkString("|")}"
    val bytes  = MessageDigest.getInstance("SHA-1").digest(joined.getBytes("UTF-8"))
    bytes.take(6).map("%02x".format(_)).mkString

  def display: String =
    val loc = topFrames.headOption.getOrElse("<no-project-frame>")
    s"[$target/$kind] $messagePattern @ $loc"
end Fingerprint

object Oracle:

  /** Only crashes have fingerprints. Ok/Rejected/Timeout produce `None`. */
  def fingerprint(outcome: Outcome, target: Target): Option[Fingerprint] =
    outcome match
      case Outcome.Crashed(kind, t) =>
        Some(Fingerprint(target, kind, normalizeMessage(t.getMessage), topFrames(t)))

      case _ => None

  private val TopFrameDepth = 5

  /** Project-internal packages whose frames we keep when building a fingerprint.
    * Frames from `scala.*`, `java.*`, etc. are dropped — they're shared by many
    * bugs and would wash out the signal.
    */
  private val ProjectPackages = List(
    "parsing.", "typing.", "sast.", "ast.", "phases.", "pickle.", "reporting.",
  )

  /** Strip transient tokens from the exception message so positional noise doesn't
    * fragment a single bug into many buckets.
    */
  private def normalizeMessage(msg: String | Null): String =
    if msg == null then ""
    else
      msg
        .replaceAll("""/tmp/[^\s,)\]]+""",  "<tmp>")
        .replaceAll("""\bline\s+\d+""",     "line N")
        .replaceAll("""\bcolumn\s+\d+""",   "column N")
        .replaceAll("""\boffset\s+\d+""",   "offset N")
        .replaceAll("""\b\d{3,}\b""",       "N")
        .trim
        .take(200)

  private def topFrames(t: Throwable): List[String] =
    t.getStackTrace.iterator
      .filter(f => isProjectFrame(f.getClassName))
      .map(f => s"${f.getClassName}.${f.getMethodName}:${f.getLineNumber}")
      .take(TopFrameDepth)
      .toList

  private def isProjectFrame(cls: String): Boolean =
    ProjectPackages.exists(cls.startsWith)

end Oracle
