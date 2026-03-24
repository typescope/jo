package tool

import java.nio.file.Path

object GraphPrinter:
  /** Print a ResolvedGraph with paths relativized to baseDir for stable output. */
  def print(graph: ResolvedGraph, baseDir: Path, joLabel: Option[String]): String =
    val sb = new StringBuilder

    def sastDir(dep: ResolvedDep): Path =
      val base = dep.specDir.resolve(s".build/${dep.spec.name}")
      joLabel.fold(base)(base.resolve).resolve("sast")

    def printDeps(deps: List[ResolvedDep]): Unit =
      for dep <- deps do
        val kind = if dep.link == DepLink.Link then "link" else "check"
        sb.append(s"dep ${dep.name}:\n")
        sb.append(s"  dir = ${baseDir.relativize(dep.specDir)}\n")
        sb.append(s"  sast = ${baseDir.relativize(sastDir(dep))}\n")
        sb.append(s"  kind = $kind\n")

    if graph.deps.isEmpty then sb.append("deps = []\n")
    else printDeps(graph.deps)

    if graph.testDeps.nonEmpty then printDeps(graph.testDeps)

    val checkLibs = graph.deps.collect { case d if d.link == DepLink.Check => baseDir.relativize(sastDir(d)) }
    val linkLibs  = graph.deps.collect { case d if d.link == DepLink.Link  => baseDir.relativize(sastDir(d)) }
    sb.append(s"check-libs = ${checkLibs.mkString("[", ", ", "]")}\n")
    sb.append(s"link-libs  = ${linkLibs.mkString("[", ", ", "]")}")
    sb.toString
