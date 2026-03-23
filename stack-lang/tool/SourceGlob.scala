package tool

import java.nio.file.{Path, Files, FileSystems}
import scala.jdk.CollectionConverters.*

object SourceGlob:
  val defaultMainSrc = List("src/**/*.jo")
  val defaultTestSrc = List("tests/**/*.jo")

  /** Expand a list of glob patterns relative to baseDir. Returns sorted .jo paths. */
  def expand(patterns: List[String], baseDir: Path): List[Path] =
    val effective = if patterns.isEmpty then defaultMainSrc else patterns
    val fs = FileSystems.getDefault
    effective
      .flatMap: pattern =>
        val matcher = fs.getPathMatcher(s"glob:$pattern")
        if !Files.exists(baseDir) then Nil
        else
          Files.walk(baseDir).iterator.asScala
            .filter(p => Files.isRegularFile(p))
            .filter(p => matcher.matches(baseDir.relativize(p)))
            .toList

      .distinct
      .sorted
