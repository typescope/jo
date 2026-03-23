package tool

import java.nio.file.Path

/** Prints a BuildPlan as a sequence of `jo compile` commands for inspection and testing. */
object PlanPrinter:
  def print(plan: BuildPlan, baseDir: Path): String =
    val sb = new StringBuilder
    for (name, lib) <- plan.depBuilds do
      sb.append(s"# lib: $name\n")
      sb.append(libCmd(lib, baseDir))
      sb.append("\n\n")
    plan.rootBuild match
      case Left(lib)  =>
        sb.append(s"# root (lib)\n")
        sb.append(libCmd(lib, baseDir))
      case Right(app) =>
        sb.append(s"# root (app)\n")
        sb.append(appCmd(app, baseDir))
    sb.toString.stripTrailing()

  private def libCmd(lib: LibBuild, base: Path): String =
    val parts = List.newBuilder[String]
    parts += "jo compile --sast"
    lib.sources.foreach(s => parts += rel(s, base))
    lib.checkLibs.foreach(l => parts += s"--lib ${rel(l, base)}")
    parts += s"-d ${rel(lib.outDir, base)}"
    parts.result().mkString(" ")

  private def appCmd(app: AppBuild, base: Path): String =
    val parts = List.newBuilder[String]
    parts += s"jo compile --${app.target}"
    app.sources.foreach(s => parts += rel(s, base))
    app.checkLibs.foreach(l => parts += s"--lib ${rel(l, base)}")
    app.linkLibs.foreach(l => parts += s"--runtime ${rel(l, base)}")
    app.links.toSeq.sortBy(_._1).foreach { (k, v) => parts += s"--link $k=$v" }
    parts += s"-o ${rel(app.outFile, base)}"
    parts.result().mkString(" ")

  private def rel(p: Path, base: Path): String =
    try base.relativize(p).toString
    catch case _: IllegalArgumentException => p.toString
