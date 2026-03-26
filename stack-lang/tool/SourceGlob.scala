package tool

import java.nio.file.{Path, Files, FileSystems}
import scala.jdk.CollectionConverters.*

object SourceGlob:
  val defaultMainSrc = List("src/**/*.jo")
  val defaultTestSrc = List("tests/**/*.jo")

  /** Expand a list of glob patterns relative to baseDir. Returns sorted .jo paths. */
  def expand(patterns: List[String], baseDir: Path, default: List[String] = defaultMainSrc): List[Path] =
    val effective = if patterns.isEmpty then default else patterns
    val fs = FileSystems.getDefault
    // Java's PathMatcher requires ** to match at least one path segment, so
    // "src/**/*.jo" does not match "src/Foo.jo". Work around this by also emitting
    // the zero-segment variant: "src/**/*.jo" → also "src/*.jo".
    val expanded = effective.flatMap: p =>
      if p.contains("/**/") then List(p, p.replace("/**/", "/")) else List(p)
    expanded
      .flatMap: pattern =>
        val normalized = if pattern.endsWith("/") then s"${pattern}{*.jo,**/*.jo}" else pattern
        val matcher = fs.getPathMatcher(s"glob:$normalized")
        if !Files.exists(baseDir) then Nil
        else
          Files.walk(baseDir).iterator.asScala
            .filter(p => Files.isRegularFile(p))
            .filter(p => matcher.matches(baseDir.relativize(p)))
            .toList

      .distinct
      .sorted
