package tool

import java.nio.file.Path
import scala.collection.mutable.ArrayBuffer

/** Prints a BuildPlan as a sequence of `jo compile` commands for inspection and testing. */
object PlanPrinter:
  def print(plan: BuildPlan, baseDir: Path): String =
    val sb = new StringBuilder
    for (name, lib) <- plan.depBuilds do
      sb.append(s"# lib: $name\n")
      sb.append(libCmd(lib, baseDir))
      sb.append("\n\n")
    plan.rootBuild match
      case lib: RootBuild.LibBuild =>
        sb.append(s"# root (lib)\n")
        sb.append(libCmd(lib, baseDir))
      case app: RootBuild.AppBuild =>
        sb.append(s"# root (app)\n")
        sb.append(appCmd(app, baseDir))
    sb.toString.stripTrailing()

  private def libCmd(lib: RootBuild.LibBuild, base: Path): String =
    val parts = ArrayBuffer[String]("jo compile --sast")
    lib.sources.foreach(s => parts += rel(s, base))
    lib.checkLibs.foreach(l => parts += s"--lib ${rel(l, base)}")
    parts += s"-d ${rel(lib.outDir, base)}"
    parts.mkString(" ")

  private def appCmd(app: RootBuild.AppBuild, base: Path): String =
    val parts = ArrayBuffer[String](s"jo compile --${app.target}")
    app.sources.foreach(s => parts += rel(s, base))
    app.checkLibs.foreach(l => parts += s"--lib ${rel(l, base)}")
    app.linkLibs.foreach(l => parts += s"--runtime ${rel(l, base)}")
    app.links.toSeq.sortBy(_._1).foreach { (k, v) => parts += s"--link $k=$v" }
    parts += s"-o ${rel(app.outFile, base)}"
    parts.mkString(" ")

  private def rel(p: Path, base: Path): String =
    try base.relativize(p).toString
    catch case _: IllegalArgumentException => p.toString
