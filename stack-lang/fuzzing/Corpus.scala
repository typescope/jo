package fuzzing

import java.io.File

import common.IO

/** A seed program loaded from disk. */
case class Seed(path: String, content: Array[Byte])

object Corpus:

  /** Recursively collect all `.jo` files under the given roots. Non-existent roots
    * are silently skipped (lets callers pass a list of fallback locations).
    */
  def load(roots: List[String]): IndexedSeq[Seed] =
    roots.iterator.flatMap(listJoFiles).map(p => Seed(p, IO.fileAsBytes(p))).toIndexedSeq

  private def listJoFiles(root: String): Iterator[String] =
    val f = new File(root)
    if !f.exists() then Iterator.empty
    else if f.isFile && root.endsWith(".jo") then Iterator.single(root)
    else if f.isDirectory then f.listFiles.iterator.flatMap(sub => listJoFiles(sub.getPath))
    else Iterator.empty

end Corpus
