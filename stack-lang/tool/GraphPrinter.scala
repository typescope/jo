package tool

import java.nio.file.Path

object GraphPrinter:
  /** Print a ResolvedGraph with paths relativized to baseDir for stable output. */
  def print(graph: ResolvedGraph, baseDir: Path): String =
    val sb = new StringBuilder
    if graph.deps.isEmpty then
      sb.append("deps = []\n")
    else
      for dep <- graph.deps do
        val relSpecDir = baseDir.relativize(dep.specDir)
        val relSast    = baseDir.relativize(dep.sastDir)
        val kind       = if dep.link == DepLink.Link then "link" else "check"
        sb.append(s"dep ${dep.name}:\n")
        sb.append(s"  dir = $relSpecDir\n")
        sb.append(s"  sast = $relSast\n")
        sb.append(s"  kind = $kind\n")
    sb.append(s"check-libs = ${graph.checkLibs.map(p => baseDir.relativize(p)).mkString("[", ", ", "]")}\n")
    sb.append(s"link-libs  = ${graph.linkLibs.map(p => baseDir.relativize(p)).mkString("[", ", ", "]")}")
    sb.toString
